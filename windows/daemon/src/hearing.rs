//! AirPods Pro 3 hearing assistance: enable it over AAP (control commands 0x2C /
//! 0x33), switch to Transparency, then write the full settings to the ATT/GATT
//! (PSM 0x001F, handle 0x2A) via a read-modify-write. Layout + semantics are ported
//! from the Linux `hearing-aid-adjustments.py`: an 8-band audiogram (hearing loss in
//! dB HL) per ear, per-ear amplification, tone, conversation-boost, ambient-noise-
//! reduction and own-voice — all little-endian f32 at fixed offsets.

use crate::aap;
use crate::driver::Driver;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::{thread, time::Duration};

// Serializes hearing-aid applies AND remembers whether the buds are already in
// hearing-assist mode. Holding this across a whole apply() stops two applies (a
// toggle + a slider tweak, or two quick tweaks — each spawned on its own thread by
// main.rs) from interleaving I/O on the shared ATT channel and tearing it down.
// The bool is the "already enabled" flag: true once the AAP handshake has run.
static HEARING_STATE: Mutex<bool> = Mutex::new(false);

// Latest-wins coalescing. Filling in the 8-band audiogram fires one command per box;
// each apply hammers the exclusive driver handle (shared with the AAP receive loop)
// with ATT round-trips, and a burst of them starves AAP reads until the link looks
// dead and gets torn down. So every command bumps this generation; an apply that
// finds a newer generation waiting bails before touching the driver — a flood of N
// applies collapses to just the last one.
static HEARING_GEN: AtomicU64 = AtomicU64::new(0);

/// Register a new hearing-aid command and get its generation. The apply started for
/// this generation will no-op if a later one arrives before it reaches the driver.
pub fn next_gen() -> u64 {
    HEARING_GEN.fetch_add(1, Ordering::SeqCst) + 1
}

// AAP hearing-assist enable/disable (0x09 control commands 0x2C / 0x33).
const HA_ON_2C: [u8; 11] = [0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x2C, 0x01, 0x01, 0x00, 0x00];
const HA_ON_33: [u8; 11] = [0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x33, 0x01, 0x00, 0x00, 0x00];
const HA_OFF_2C: [u8; 11] = [0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x2C, 0x01, 0x02, 0x00, 0x00];
const HA_OFF_33: [u8; 11] = [0x04, 0x00, 0x04, 0x00, 0x09, 0x00, 0x33, 0x02, 0x00, 0x00, 0x00];

const H_SETTINGS: u16 = 0x002A; // hearing-aid settings characteristic
const H_CCCD: u16 = 0x002B; // its client-config descriptor

// ATT round-trip timeout. Kept short: the driver handle is exclusive and shared with
// the AAP receive loop, so a long ATT stall here starves AAP reads and can trip the
// link-lost teardown. Better to fail fast and let the caller retry than to block.
const ATT_TIMEOUT_MS: u32 = 900;

// f32 offsets into the settings value (bytes after the ATT opcode) — mirror the
// Linux reference exactly.
const OFF_MODE: usize = 2;
const OFF_LEFT_EQ: usize = 4; // + i*4, 8 bands
const OFF_LEFT_AMP: usize = 36;
const OFF_LEFT_TONE: usize = 40;
const OFF_LEFT_CONV: usize = 44;
const OFF_LEFT_ANR: usize = 48;
const OFF_RIGHT_EQ: usize = 52; // + i*4, 8 bands
const OFF_RIGHT_AMP: usize = 84;
const OFF_RIGHT_TONE: usize = 88;
const OFF_RIGHT_CONV: usize = 92;
const OFF_RIGHT_ANR: usize = 96;
const OFF_OWN_VOICE: usize = 100;

fn put_f32(buf: &mut [u8], off: usize, v: f32) {
    if off + 4 <= buf.len() {
        buf[off..off + 4].copy_from_slice(&v.to_le_bytes());
    }
}

fn att_read_req(handle: u16) -> [u8; 3] {
    [0x0A, (handle & 0xff) as u8, (handle >> 8) as u8]
}

fn att_write_pdu(handle: u16, value: &[u8]) -> Vec<u8> {
    let mut p = vec![0x12u8, (handle & 0xff) as u8, (handle >> 8) as u8];
    p.extend_from_slice(value);
    p
}

/// Wake the buds' hearing-aid ATT server (dormant until enabled), switch to
/// Transparency (mode 3) so ambient sound passes through to be amplified, and enable
/// notifications on the settings CCCD. Only needed on the off→on transition (or to
/// recover a dropped ATT channel) — NOT on every slider tweak, since re-sending the
/// AAP enable makes the buds reset their ATT server and drop our channel.
fn enable_handshake(drv: &Driver) {
    let _ = drv.send(&HA_ON_2C);
    thread::sleep(Duration::from_millis(300));
    let _ = drv.send(&aap::anc_command(3));
    thread::sleep(Duration::from_millis(200));
    let _ = drv.send(&HA_ON_33);
    thread::sleep(Duration::from_millis(900));

    let mut b = [0u8; 512];
    let _ = drv.att_send(&att_write_pdu(H_CCCD, &[0x01, 0x00]));
    let _ = drv.att_recv(ATT_TIMEOUT_MS, &mut b);
}

/// Read-modify-write the settings characteristic over the (already-open) ATT channel.
/// This is the only step a settings tweak needs — no AAP re-enable, so the ATT
/// channel stays up.
#[allow(clippy::too_many_arguments)]
fn write_settings(
    drv: &Driver,
    left_eq: &[f32],
    right_eq: &[f32],
    amplification: f32,
    balance: f32,
    tone: f32,
    conv_boost: bool,
    anr: f32,
    own_voice: f32,
) -> Result<String, String> {
    let mut b = [0u8; 512];

    // 1) Read the current settings value (read-modify-write).
    let _ = drv.att_send(&att_read_req(H_SETTINGS));
    let n = drv
        .att_recv(ATT_TIMEOUT_MS, &mut b)
        .map_err(|e| format!("ATT read err: {e}"))?;
    if n < 104 || b[0] != 0x0B {
        return Err(format!("bad ATT read resp [{n}]"));
    }
    let mut val = b[1..n].to_vec(); // the characteristic value (~104 bytes)

    // 2) Patch: audiogram (per-frequency loss in dB HL) + per-ear amplification from
    // amplification/balance + tone/conversation-boost/ANR/own-voice.
    let amp = amplification.clamp(0.0, 1.0);
    let bal = balance.clamp(-1.0, 1.0);
    let left_amp = (amp - bal / 2.0).clamp(-1.0, 1.0);
    let right_amp = (amp + bal / 2.0).clamp(-1.0, 1.0);
    let cb = if conv_boost { 1.0f32 } else { 0.0f32 };
    let tone = tone.clamp(-1.0, 1.0);
    let anr = anr.clamp(0.0, 1.0);
    let own_voice = own_voice.clamp(0.0, 1.0);

    if val.len() > OFF_MODE {
        val[OFF_MODE] = 0x64;
    }
    for i in 0..8usize {
        put_f32(&mut val, OFF_LEFT_EQ + i * 4, *left_eq.get(i).unwrap_or(&0.0));
        put_f32(&mut val, OFF_RIGHT_EQ + i * 4, *right_eq.get(i).unwrap_or(&0.0));
    }
    put_f32(&mut val, OFF_LEFT_AMP, left_amp);
    put_f32(&mut val, OFF_LEFT_TONE, tone);
    put_f32(&mut val, OFF_LEFT_CONV, cb);
    put_f32(&mut val, OFF_LEFT_ANR, anr);
    put_f32(&mut val, OFF_RIGHT_AMP, right_amp);
    put_f32(&mut val, OFF_RIGHT_TONE, tone);
    put_f32(&mut val, OFF_RIGHT_CONV, cb);
    put_f32(&mut val, OFF_RIGHT_ANR, anr);
    put_f32(&mut val, OFF_OWN_VOICE, own_voice);

    // 3) Write it back.
    let _ = drv.att_send(&att_write_pdu(H_SETTINGS, &val));
    let wn = drv.att_recv(ATT_TIMEOUT_MS, &mut b).unwrap_or(0);
    let wr = if wn >= 1 && b[0] == 0x13 { "ok" } else { "no-resp" };

    Ok(format!(
        "hearing aid ON: wrote {} bytes leftAmp={left_amp:.2} rightAmp={right_amp:.2} conv={conv_boost} eqL={left_eq:?} write={wr}",
        val.len()
    ))
}

/// Apply hearing-assist settings. Requires the AAP + ATT channels to be up (the
/// driver opens ATT on connect). Returns a short summary for the daemon log.
#[allow(clippy::too_many_arguments)]
pub fn apply(
    drv: &Driver,
    gen: u64,
    on: bool,
    left_eq: &[f32],
    right_eq: &[f32],
    amplification: f32,
    balance: f32,
    tone: f32,
    conv_boost: bool,
    anr: f32,
    own_voice: f32,
) -> Result<String, String> {
    // Serialize applies and read the "already enabled" flag under the same lock, so
    // overlapping applies can't interleave ATT I/O on the shared channel.
    let mut state = HEARING_STATE.lock().unwrap_or_else(|p| p.into_inner());

    // A newer command arrived while we waited for the lock — drop this stale apply
    // before touching the driver (latest-wins coalescing).
    if HEARING_GEN.load(Ordering::SeqCst) != gen {
        return Ok(format!("hearing aid apply superseded (gen {gen})"));
    }

    if !on {
        let _ = drv.send(&HA_OFF_33);
        thread::sleep(Duration::from_millis(300));
        let _ = drv.send(&HA_OFF_2C);
        *state = false;
        return Ok("hearing aid OFF".into());
    }

    // Only run the AAP enable handshake on the off→on transition; a settings tweak
    // while already on goes straight to the ATT write and leaves the channel alone.
    if !*state {
        enable_handshake(drv);
        *state = true;
    }

    match write_settings(
        drv, left_eq, right_eq, amplification, balance, tone, conv_boost, anr, own_voice,
    ) {
        Ok(s) => Ok(s),
        // The ATT channel may have idle-closed since we last used it — re-run the
        // enable handshake once (which reopens the buds' ATT server) and retry.
        Err(e) => {
            enable_handshake(drv);
            write_settings(
                drv, left_eq, right_eq, amplification, balance, tone, conv_boost, anr, own_voice,
            )
            .map_err(|e2| format!("{e}; after re-enable: {e2}"))
        }
    }
}
