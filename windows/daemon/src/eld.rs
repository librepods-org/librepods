//! AAC-ELD decoder — thin Rust wrapper over the FFmpeg C shim (`eld_shim.c`).
//! Decodes the AirPods' hi-res mic frames (AAC-ELD, mono 48 kHz) to i16 PCM.

use std::ffi::c_void;
use std::os::raw::c_int;

extern "C" {
    fn eld_open(asc: *const u8, asc_len: c_int, sample_rate: c_int) -> *mut c_void;
    fn eld_decode(
        h: *mut c_void,
        au: *const u8,
        au_len: c_int,
        out: *mut i16,
        out_cap: c_int,
    ) -> c_int;
    fn eld_close(h: *mut c_void);
}

/// AudioSpecificConfig for the AirPods hi-res mic: AOT 39 (ER AAC ELD), 48 kHz,
/// mono. From LibrePods PR #655.
const ASC: [u8; 4] = [0xF8, 0xE6, 0x30, 0x00];
const SAMPLE_RATE: c_int = 48_000;

pub struct Decoder {
    h: *mut c_void,
    out: Vec<i16>,
}

// The handle is only ever used from the receive thread.
unsafe impl Send for Decoder {}

impl Decoder {
    pub fn new() -> Option<Decoder> {
        let h = unsafe { eld_open(ASC.as_ptr(), ASC.len() as c_int, SAMPLE_RATE) };
        if h.is_null() {
            None
        } else {
            Some(Decoder { h, out: vec![0i16; 8192] })
        }
    }

    /// Decode one access unit into i16 mono samples (48 kHz). May return empty
    /// (the decoder buffers a frame of latency before the first output).
    pub fn decode(&mut self, au: &[u8]) -> &[i16] {
        let n = unsafe {
            eld_decode(
                self.h,
                au.as_ptr(),
                au.len() as c_int,
                self.out.as_mut_ptr(),
                self.out.len() as c_int,
            )
        };
        if n <= 0 {
            &[]
        } else {
            &self.out[..n as usize]
        }
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        unsafe { eld_close(self.h) };
    }
}
