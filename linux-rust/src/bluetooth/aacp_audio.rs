//! AACP framing for the AirPods proprietary hi-res microphone stream
//! (AAC-ELD @ 64 kHz, message_type 0x58 over L2CAP PSM 0x1001).

/// type-0x58 audio SDU layout: 22-byte header, then N x [ts:u32 LE][len:u8][au].
const TYPE58_HEADER_LEN: usize = 22;

#[inline]
fn u16le(b: &[u8], off: usize) -> u16 {
    (b[off] as u16) | ((b[off + 1] as u16) << 8)
}

/// Predicate for 0x58 *audio* frames (subtype 0x0001)
#[inline]
pub fn is_audio(sdu: &[u8]) -> bool {
    sdu.len() >= 8
        && sdu[0] == 0x04
        && sdu[2] == 0x04
        && u16le(sdu, 4) == 0x58
        && u16le(sdu, 6) == 0x0001
}

/// Walk the sub-frames of one 0x58 audio SDU, invoking `emit` per AAC-ELD AU.
/// Returns the number of AUs emitted
pub fn demux_type58(sdu: &[u8], mut emit: impl FnMut(&[u8])) -> usize {
    let mut off = TYPE58_HEADER_LEN;
    let mut n = 0;

    while off + 5 <= sdu.len() {
        let au_len = sdu[off + 4] as usize;
        let start = off + 5;
        let end = start + au_len;

        if end > sdu.len() {
            break;
        }
        emit(&sdu[start..end]);
        n += 1;
        off = end;
    }
    n
}
