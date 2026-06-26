//! Hi-res microphone lifecycle: ties together the AACP 0x58 control stream, the
//! AAC-ELD decoder, and the PipeWire output.

use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};
use std::thread::JoinHandle;
use std::time::Duration;

use log::{error, info, warn};
use tokio::sync::mpsc;

use crate::audio::eld::{ELD_CHANNELS, ELD_FRAME_SAMPLES, ELD_SAMPLE_RATE, EldDecoder};
use crate::audio::output::{self, Output};
use crate::bluetooth::aacp::AACPManager;
use crate::bluetooth::aacp_audio;

/// Delay after sending 0x58 START before resetting the A2DP transport
const A2DP_RESET_DELAY: Duration = Duration::from_millis(800);

const LEVEL_RELEASE: f32 = 0.85;

#[derive(Clone, Default)]
pub struct MicLevel(Arc<AtomicU32>);

impl MicLevel {
    pub fn new() -> Self {
        Self(Arc::new(AtomicU32::new(0)))
    }

    pub fn set(&self, level: f32) {
        self.0.store(level.to_bits(), Ordering::Relaxed);
    }

    pub fn get(&self) -> f32 {
        f32::from_bits(self.0.load(Ordering::Relaxed))
    }
}

pub struct HiResMic {
    addr: String,
    decode_thread: Option<JoinHandle<()>>,
}

impl HiResMic {
    // Start the proprietary hi-res mic stream:
    // - build the decoder + output
    // - setup audio routing
    // - spawn the decode thread
    // - send 0x58 START
    // - schedule the A2DP transport reset
    pub async fn start(aacp: &AACPManager, addr: String, level: MicLevel) -> Option<HiResMic> {
        let decoder = EldDecoder::new()?;
        let output = Output::open(ELD_SAMPLE_RATE, ELD_CHANNELS as u8)?;

        // Arm recv routing BEFORE the device starts emitting audio.
        let rx = aacp.take_audio_channel().await;
        let decode_thread = spawn_decode_thread(rx, decoder, output, level);

        if let Err(e) = aacp.send_start_audio().await {
            error!("failed to send 0x58 START: {}", e);
            // Tear the just-spawned thread back down.
            aacp.clear_audio_channel().await;
            return None;
        }
        info!("[aacp] microphone stream started");

        let reset_addr = addr.clone();
        tokio::spawn(async move {
            tokio::time::sleep(A2DP_RESET_DELAY).await;
            let _ = tokio::task::spawn_blocking(move || output::reset_a2dp(&reset_addr)).await;
        });

        Some(HiResMic {
            addr,
            decode_thread: Some(decode_thread),
        })
    }

    // Teardown:
    // - 0x58 STOP
    // - join the thread
    // - A2DP transport reset
    pub async fn stop(mut self, aacp: &AACPManager) {
        if let Err(e) = aacp.send_stop_audio().await {
            warn!("failed to send 0x58 STOP: {}", e);
        }
        aacp.clear_audio_channel().await;

        if let Some(handle) = self.decode_thread.take() {
            let _ = tokio::task::spawn_blocking(move || handle.join()).await;
        }

        let addr = std::mem::take(&mut self.addr);
        let _ = tokio::task::spawn_blocking(move || output::reset_a2dp(&addr)).await;
    }
}

// The decode loop:
// - drain raw 0x58 SDUs
// - demux to AUs
// - decode to PCM
// - write to the virtual sink
// Runs on a seperate thread
fn spawn_decode_thread(
    mut rx: mpsc::Receiver<Vec<u8>>,
    mut decoder: EldDecoder,
    mut output: Output,
    level: MicLevel,
) -> JoinHandle<()> {
    std::thread::Builder::new()
        .name("hires-decode".into())
        .spawn(move || {
            let mut frames: u64 = 0;
            let mut errors: u64 = 0;
            let mut env: f32 = 0.0;
            let mut pcm: Vec<i16> = Vec::with_capacity(4096);

            while let Some(sdu) = rx.blocking_recv() {
                pcm.clear();
                // Decode every AU in this SDU, then issue a single PCM write.
                aacp_audio::demux_type58(&sdu, |au| match decoder.decode(au, &mut pcm) {
                    Some(_) => frames += 1,
                    None => errors += 1,
                });

                let peak = if pcm.is_empty() {
                    0.0
                } else {
                    match output.write(&pcm) {
                        Ok(peak) => peak,
                        Err(()) => {
                            warn!("hi-res output broke; stopping decode loop");
                            break;
                        }
                    }
                };

                env = if peak >= env {
                    peak
                } else {
                    env * LEVEL_RELEASE
                };
                level.set(env);

                if frames > 0 && frames % 400 == 0 {
                    let secs = frames as f64 * ELD_FRAME_SAMPLES as f64 / ELD_SAMPLE_RATE as f64;
                    info!(
                        "[audio] {} frames ({:.0}s), {} errors, level {:.2}",
                        frames, secs, errors, env
                    );
                }
            }
            level.set(0.0);
            info!(
                "[audio] hi-res decode loop ended ({} frames, {} errors)",
                frames, errors
            );
            // `output` drops here -> virtual sink/source unloaded.
        })
        .expect("failed to spawn hires-decode thread")
}
