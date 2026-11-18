//! AAC-ELD decoder using libavcodec (LGPL) for the AirPods proprietary hi-res uplink.
//!   AOT 39, mono, 64000 Hz, 480-sample frame (7.5 ms), ~80 kbps VBR.
//! See https://ffmpeg-d.dpldocs.info/v3.1.1/ffmpeg.libavcodec.avcodec.AVCodecContext.html

use ffmpeg_sys_next as ff;
use std::os::raw::c_int;
use std::ptr;

pub const ELD_SAMPLE_RATE: u32 = 64000;
pub const ELD_FRAME_SAMPLES: usize = 480;
pub const ELD_CHANNELS: i32 = 1;

const ELD_ASC: [u8; 4] = [0xF8, 0xE6, 0x30, 0x00];
const ELD_CODING_RATE: c_int = 48000;
const ELD_INBUF_MAX: usize = 512;

const PAD: usize = ff::AV_INPUT_BUFFER_PADDING_SIZE as usize;

pub struct EldDecoder {
    ctx: *mut ff::AVCodecContext,
    pkt: *mut ff::AVPacket,
    frame: *mut ff::AVFrame,
    inbuf: Vec<u8>,
}

unsafe impl Send for EldDecoder {}

#[inline]
fn f_to_s16(s: f32) -> i16 {
    (s.clamp(-1.0, 1.0) * 32767.0).round() as i16
}

impl EldDecoder {
    // Open the AAC-ELD decoder. Returns None on failure.
    pub fn new() -> Option<Self> {
        unsafe {
            // stop libavcodec from spamming stderr
            ff::av_log_set_level(ff::AV_LOG_FATAL);

            let codec = ff::avcodec_find_decoder(ff::AVCodecID::AV_CODEC_ID_AAC);
            if codec.is_null() {
                return None;
            }
            let ctx = ff::avcodec_alloc_context3(codec);
            if ctx.is_null() {
                return None;
            }

            let mut d = EldDecoder {
                ctx,
                pkt: ptr::null_mut(),
                frame: ptr::null_mut(),
                inbuf: vec![0u8; ELD_INBUF_MAX + PAD],
            };

            // extradata = ASC
            let extradata = ff::av_mallocz(ELD_ASC.len() + PAD) as *mut u8;
            if extradata.is_null() {
                d.free();
                return None;
            }
            ptr::copy_nonoverlapping(ELD_ASC.as_ptr(), extradata, ELD_ASC.len());
            (*ctx).extradata = extradata;
            (*ctx).extradata_size = ELD_ASC.len() as c_int;
            (*ctx).sample_rate = ELD_CODING_RATE;
            ff::av_channel_layout_default(&mut (*ctx).ch_layout, ELD_CHANNELS);

            if ff::avcodec_open2(ctx, codec, ptr::null_mut()) < 0 {
                d.free();
                return None;
            }

            d.pkt = ff::av_packet_alloc();
            d.frame = ff::av_frame_alloc();
            if d.pkt.is_null() || d.frame.is_null() {
                d.free();
                return None;
            }
            Some(d)
        }
    }

    // Free all FFmpeg resources.
    unsafe fn free(&mut self) {
        unsafe {
            if !self.frame.is_null() {
                ff::av_frame_free(&mut self.frame);
            }
            if !self.pkt.is_null() {
                ff::av_packet_free(&mut self.pkt);
            }
            if !self.ctx.is_null() {
                ff::avcodec_free_context(&mut self.ctx);
            }
        }
    }

    // Decode one access unit, appending interleaved i16 PCM to `out`.
    // Returns the number of samples appended, or None on a decode error.
    pub fn decode(&mut self, au: &[u8], out: &mut Vec<i16>) -> Option<usize> {
        if au.is_empty() || au.len() > ELD_INBUF_MAX {
            return None;
        }
        unsafe {
            self.inbuf[..au.len()].copy_from_slice(au);
            self.inbuf[au.len()..au.len() + PAD].fill(0);
            (*self.pkt).data = self.inbuf.as_mut_ptr();
            (*self.pkt).size = au.len() as c_int;

            if ff::avcodec_send_packet(self.ctx, self.pkt) < 0 {
                return None;
            }
            if ff::avcodec_receive_frame(self.ctx, self.frame) < 0 {
                return None;
            }

            let nch = (*self.frame).ch_layout.nb_channels;
            let ns = (*self.frame).nb_samples;
            if nch <= 0 || ns <= 0 {
                return None;
            }
            let (nch, ns) = (nch as usize, ns as usize);
            let total = ns * nch;
            out.reserve(total);

            let data = &(*self.frame).data;
            match (*self.frame).format {
                f if f == ff::AVSampleFormat::AV_SAMPLE_FMT_FLTP as c_int => {
                    // native aac: planar float
                    for i in 0..ns {
                        for c in 0..nch {
                            let plane = data[c] as *const f32;
                            out.push(f_to_s16(*plane.add(i)));
                        }
                    }
                }
                f if f == ff::AVSampleFormat::AV_SAMPLE_FMT_FLT as c_int => {
                    let p = data[0] as *const f32;
                    for i in 0..total {
                        out.push(f_to_s16(*p.add(i)));
                    }
                }
                f if f == ff::AVSampleFormat::AV_SAMPLE_FMT_S16P as c_int => {
                    for i in 0..ns {
                        for c in 0..nch {
                            let plane = data[c] as *const i16;
                            out.push(*plane.add(i));
                        }
                    }
                }
                f if f == ff::AVSampleFormat::AV_SAMPLE_FMT_S16 as c_int => {
                    let p = data[0] as *const i16;
                    for i in 0..total {
                        out.push(*p.add(i));
                    }
                }
                _ => return None,
            }
            Some(total)
        }
    }
}

impl Drop for EldDecoder {
    fn drop(&mut self) {
        unsafe { self.free() };
    }
}
