//! Hi-res microphone audio pipeline: AAC-ELD decode and PCM output.
//!
//! Ported from the reference daemon at
//! `airpods-highres-bidirectional/daemon/src` (`eld.c`, `output.c`).

pub mod agc;
pub mod eld;
pub mod hires_mic;
pub mod output;
