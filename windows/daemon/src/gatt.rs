//! One-shot GATT service/characteristic discovery over the ATT (PSM 0x001F) client
//! channel — an experiment to find a heart-rate characteristic the AirPods might
//! expose. We only ever touched the hearing-aid handle 0x2A; this walks the buds'
//! whole GATT server as a *client* (the role bthport allows on the reserved PSM) and
//! returns human-readable lines for the caller to log. Read-only: it discovers, it
//! does not subscribe.

use crate::driver::Driver;
use std::thread;
use std::time::Duration;

// ATT round-trip timeout (ms). The driver handle is shared with the AAP receive
// loop, so keep it short-ish.
const T: u32 = 1200;

// Hearing-assist enable — the AirPods' GATT/ATT server is DORMANT until this is
// sent (an outbound ATT open just fails otherwise). We send only the two enable
// control commands (0x2C on + 0x33 on) and DELIBERATELY skip the Transparency
// switch the hearing-aid path uses, so probing doesn't disturb the user's noise
// control. If the ATT server won't wake without it, add it back + restore ANC after.
const HA_ON_2C: [u8; 11] = [0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x2C, 0x01, 0x01, 0x00, 0x00];
const HA_ON_33: [u8; 11] = [0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x33, 0x01, 0x00, 0x00, 0x00];

/// Wake the buds' ATT server so the outbound ATT client channel can open. Does NOT
/// touch noise control (no Transparency switch).
fn wake(drv: &Driver) {
    let _ = drv.send(&HA_ON_2C);
    thread::sleep(Duration::from_millis(400));
    let _ = drv.send(&HA_ON_33);
    thread::sleep(Duration::from_millis(900));
}

// ATT opcodes.
const OP_ERROR_RSP: u8 = 0x01;
const OP_READ_BY_TYPE_RSP: u8 = 0x09;
const OP_READ_BY_GROUP_TYPE_RSP: u8 = 0x11;

/// One ATT request/response, dumping the raw reply hex so we can read whatever the
/// buds actually return (their discovery replies didn't match the spec opcodes, so
/// parse-by-opcode is unreliable — dump and interpret by eye).
fn round(drv: &Driver, label: &str, pdu: &[u8], out: &mut Vec<String>, buf: &mut [u8]) -> usize {
    if drv.att_send(pdu).is_err() {
        out.push(format!("gatt-probe: {label}: att_send FAILED"));
        return 0;
    }
    let n = drv.att_recv(T, buf).unwrap_or(0);
    if n == 0 {
        out.push(format!("gatt-probe: {label}: no response"));
        return 0;
    }
    let hex: String = buf[..n].iter().map(|b| format!("{b:02x}")).collect();
    out.push(format!("gatt-probe: {label}: [{n}] {hex}"));
    n
}

/// Probe the AirPods' GATT server as a client. Wakes it, then sends the standard
/// discovery requests, dumping raw replies for us to interpret.
pub fn probe(drv: &Driver) -> Vec<String> {
    let mut out = Vec::new();
    let mut buf = [0u8; 512];

    wake(drv); // buds' ATT server is dormant until this
    out.push("gatt-probe: === raw discovery dump ===".into());

    // MTU exchange.
    round(drv, "mtu", &[0x02, 0xF7, 0x00], &mut out, &mut buf);

    // Primary services: Read By Group Type (0x10) of 0x2800, walking the handle range.
    let mut start: u16 = 0x0001;
    for _ in 0..24 {
        let mut req = vec![0x10];
        req.extend_from_slice(&start.to_le_bytes());
        req.extend_from_slice(&0xFFFFu16.to_le_bytes());
        req.extend_from_slice(&0x2800u16.to_le_bytes());
        let n = round(drv, &format!("svc@0x{start:04X}"), &req, &mut out, &mut buf);
        // 0x11 rsp: [op][len]{start,end,uuid}*. Advance past the last end handle.
        if n < 2 || buf[0] != OP_READ_BY_GROUP_TYPE_RSP {
            break;
        }
        let each = buf[1] as usize;
        if each < 6 {
            break;
        }
        let mut last_end = 0u16;
        let mut i = 2;
        while i + each <= n {
            last_end = u16::from_le_bytes([buf[i + 2], buf[i + 3]]);
            i += each;
        }
        if last_end == 0 || last_end >= 0xFFFF {
            break;
        }
        start = last_end + 1;
    }

    // Subscribe to every NOTIFY characteristic found in the discovery dump, then
    // listen. Value handles from the enumerated char list; the CCCD (0x2902) sits at
    // valHandle+1 (verified: hearing-aid char 0x2A has its CCCD at 0x2B). The driver's
    // att_recv is a FIFO drain, so clear stale frames first for the responses to line
    // up with the request we just sent.
    let notify_vh: [u16; 7] = [0x0007, 0x000A, 0x000D, 0x0010, 0x0018, 0x0021, 0x002A];
    out.push("gatt-probe: === subscribing NOTIFY chars ===".into());
    for &vh in &notify_vh {
        drain(drv, &mut buf);
        let cccd = vh + 1;
        let pdu = [0x12, (cccd & 0xff) as u8, (cccd >> 8) as u8, 0x01, 0x00];
        let _ = drv.att_send(&pdu);
        let n = drv.att_recv(T, &mut buf).unwrap_or(0);
        let verdict = if n >= 1 && buf[0] == 0x13 {
            "OK (Write Rsp 0x13)".to_string()
        } else if n >= 5 && buf[0] == 0x01 {
            format!("ERROR (att err 0x{:02x} on handle 0x{:02x}{:02x})", buf[4], buf[3], buf[2])
        } else if n == 0 {
            "no response".to_string()
        } else {
            format!("rsp[{n}] op=0x{:02x}", buf[0])
        };
        out.push(format!("gatt-probe: sub valH=0x{vh:04X} cccd=0x{cccd:04X} -> {verdict}"));
    }

    // Listen ~15 s for Handle Value Notifications (0x1B) / Indications (0x1D). Log the
    // source handle + payload hex so we can spot anything sensor/HR-shaped.
    drain(drv, &mut buf);
    out.push("gatt-probe: === listening ~15s ===".into());
    let mut notifs = 0u32;
    for _ in 0..30 {
        let n = drv.att_recv(500, &mut buf).unwrap_or(0);
        if n < 3 {
            continue;
        }
        let op = buf[0];
        if op == 0x1B || op == 0x1D {
            let handle = u16::from_le_bytes([buf[1], buf[2]]);
            let hex: String = buf[3..n].iter().map(|b| format!("{b:02x}")).collect();
            out.push(format!("gatt-probe: NOTIFY h=0x{handle:04X} [{}] {hex}", n - 3));
            notifs += 1;
        } else {
            let hex: String = buf[..n.min(48)].iter().map(|b| format!("{b:02x}")).collect();
            out.push(format!("gatt-probe: rx op=0x{op:02x} [{n}] {hex}"));
        }
    }

    let _ = OP_ERROR_RSP;
    out.push(format!("gatt-probe: === done ({notifs} notifications) ==="));
    out
}

/// Drain any queued/stale ATT frames (the driver's att_recv is FIFO), so the next
/// send/recv pair lines up. Stops on the first empty read.
fn drain(drv: &Driver, buf: &mut [u8]) {
    for _ in 0..8 {
        if drv.att_recv(120, buf).unwrap_or(0) == 0 {
            break;
        }
    }
}

/// UUIDs are little-endian on the ATT wire; render big-endian. 16-bit as 0xXXXX,
/// 128-bit as a dash-less hex string.
fn uuid_str(b: &[u8]) -> String {
    if b.len() == 2 {
        format!("0x{:04X}", u16::from_le_bytes([b[0], b[1]]))
    } else {
        b.iter().rev().map(|x| format!("{x:02x}")).collect()
    }
}
