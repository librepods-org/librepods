//! Hi-res microphone lifecycle: a persistent virtual input device plus a monitor
//! that runs the AACP 0x58 capture only while an app is recording from it.

use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use log::{error, info, warn};
use tokio::sync::Notify;
use tokio::sync::mpsc;
use tokio::task::JoinHandle as TokioHandle;

use crate::audio::eld::{ELD_CHANNELS, ELD_FRAME_SAMPLES, ELD_SAMPLE_RATE, EldDecoder};
use crate::audio::output::{self, Output, VirtualMic};
use crate::bluetooth::aacp::AACPManager;
use crate::bluetooth::aacp_audio;

/// Delay after sending 0x58 START before resetting the A2DP transport
const A2DP_RESET_DELAY: Duration = Duration::from_millis(800);
/// How often the monitor polls the virtual source for activity
const POLL_INTERVAL: Duration = Duration::from_millis(400);
/// If no audio SDUs arrive for this long while a capture is active, the stream
/// is considered stalled and the capture is torn down and restarted.
const STALL_TIMEOUT: Duration = Duration::from_millis(2000);
const LEVEL_RELEASE: f32 = 0.85;

#[derive(Clone, Default)]
pub struct MicStatus {
    level: Arc<AtomicU32>,
    active: Arc<AtomicBool>,
    app: Arc<Mutex<Option<String>>>,
    last_sdu: Arc<Mutex<Option<Instant>>>,
}

impl MicStatus {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn set_level(&self, level: f32) {
        self.level.store(level.to_bits(), Ordering::Relaxed);
    }

    pub fn level(&self) -> f32 {
        f32::from_bits(self.level.load(Ordering::Relaxed))
    }

    pub fn active(&self) -> bool {
        self.active.load(Ordering::Relaxed)
    }

    pub fn app(&self) -> Option<String> {
        self.app.lock().unwrap().clone()
    }

    fn set_capture(&self, app: Option<String>) {
        self.active.store(app.is_some(), Ordering::Relaxed);
        *self.app.lock().unwrap() = app;
    }

    fn reset(&self) {
        self.active.store(false, Ordering::Relaxed);
        *self.app.lock().unwrap() = None;
        *self.last_sdu.lock().unwrap() = None;
        self.set_level(0.0);
    }

    /// Record that an audio SDU just arrived from the device.
    pub fn mark_sdu(&self) {
        *self.last_sdu.lock().unwrap() = Some(Instant::now());
    }

    /// Time since the last audio SDU, or None if none has arrived yet.
    fn since_last_sdu(&self) -> Option<Duration> {
        self.last_sdu.lock().unwrap().map(|t| t.elapsed())
    }
}

pub struct HiResMic {
    _vmic: VirtualMic,
    stop: Arc<Notify>,
    monitor: Option<TokioHandle<()>>,
}

impl HiResMic {
    // Create the persistent virtual input and spawn the activity monitor.
    pub async fn start(aacp: &AACPManager, addr: String, status: MicStatus) -> Option<HiResMic> {
        let vmic = VirtualMic::open(ELD_CHANNELS as u8)?;
        let stop = Arc::new(Notify::new());
        // Spawn on the backend runtime, not the caller's (the UI toggle uses a
        // throwaway runtime that would abort this task immediately).
        let monitor = aacp
            .runtime()
            .spawn(monitor_loop(aacp.clone(), addr, status, stop.clone()));
        Some(HiResMic {
            _vmic: vmic,
            stop,
            monitor: Some(monitor),
        })
    }

    // Stop the monitor (tearing down any active capture) and unload the device.
    pub async fn stop(mut self) {
        self.stop.notify_one();
        if let Some(monitor) = self.monitor.take() {
            let _ = monitor.await;
        }
    }
}

// A live capture session: the playback stream feeding the sink plus its decode
// thread, started when an app opens the mic.
struct Capture {
    decode_thread: Option<JoinHandle<()>>,
}

async fn monitor_loop(aacp: AACPManager, addr: String, status: MicStatus, stop: Arc<Notify>) {
    let mut capture: Option<Capture> = None;
    info!(
        "[hires] activity monitor started, watching '{}'",
        output::SOURCE_NAME
    );

    loop {
        tokio::select! {
            _ = stop.notified() => break,
            _ = tokio::time::sleep(POLL_INTERVAL) => {}
        }

        let app = tokio::task::spawn_blocking(|| output::source_consumer(output::SOURCE_NAME))
            .await
            .unwrap_or(None);

        match (app.is_some(), capture.is_some()) {
            (true, false) => {
                info!("[hires] recorder detected ({:?}), starting capture", app);
                if let Some(c) = start_capture(&aacp, &addr, &status).await {
                    status.set_capture(app);
                    capture = Some(c);
                }
            }
            (false, true) => {
                info!("[hires] recorder gone, stopping capture");
                stop_capture(capture.take().unwrap(), &aacp, &addr).await;
                status.reset();
            }
            (true, true) => {
                status.set_capture(app);
                let stalled = status.since_last_sdu().is_some_and(|d| d > STALL_TIMEOUT);
                if capture.is_some() && stalled {
                    warn!(
                        "[hires] no audio from device for {}ms, restarting capture",
                        STALL_TIMEOUT.as_millis()
                    );
                    stop_capture(capture.take().unwrap(), &aacp, &addr).await;
                    capture = start_capture(&aacp, &addr, &status).await;
                    if capture.is_none() {
                        warn!("[hires] capture restart failed; will retry on next poll");
                        status.reset();
                    }
                }
            }
            _ => {}
        }
    }

    if let Some(c) = capture.take() {
        stop_capture(c, &aacp, &addr).await;
    }
    status.reset();
}

async fn start_capture(aacp: &AACPManager, addr: &str, status: &MicStatus) -> Option<Capture> {
    let decoder = EldDecoder::new()?;
    let output = Output::open(ELD_SAMPLE_RATE, ELD_CHANNELS as u8)?;

    let rx = aacp.take_audio_channel().await;
    // Start the stall grace period now; the device should deliver SDUs (which
    // refresh this) well within STALL_TIMEOUT.
    status.mark_sdu();
    let decode_thread = spawn_decode_thread(rx, decoder, output, status.clone());

    if let Err(e) = aacp.send_start_audio().await {
        error!("failed to send 0x58 START: {}", e);
        aacp.clear_audio_channel().await;
        return None;
    }
    info!("[aacp] microphone stream started");

    let reset_addr = addr.to_string();
    tokio::spawn(async move {
        tokio::time::sleep(A2DP_RESET_DELAY).await;
        let _ = tokio::task::spawn_blocking(move || output::reset_a2dp(&reset_addr)).await;
    });

    Some(Capture {
        decode_thread: Some(decode_thread),
    })
}

async fn stop_capture(mut capture: Capture, aacp: &AACPManager, addr: &str) {
    if let Err(e) = aacp.send_stop_audio().await {
        warn!("failed to send 0x58 STOP: {}", e);
    }
    aacp.clear_audio_channel().await;

    if let Some(handle) = capture.decode_thread.take() {
        let _ = tokio::task::spawn_blocking(move || handle.join()).await;
    }

    let addr = addr.to_string();
    let _ = tokio::task::spawn_blocking(move || output::reset_a2dp(&addr)).await;
}

fn spawn_decode_thread(
    mut rx: mpsc::Receiver<Vec<u8>>,
    mut decoder: EldDecoder,
    mut output: Output,
    status: MicStatus,
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
                aacp_audio::demux_type58(&sdu, |au| match decoder.decode(au, &mut pcm) {
                    Some(_) => frames += 1,
                    None => {
                        // insert a silent frame
                        errors += 1;
                        pcm.resize(pcm.len() + ELD_FRAME_SAMPLES * ELD_CHANNELS as usize, 0);
                    }
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
                status.set_level(env);

                if frames > 0 && frames % 400 == 0 {
                    let secs = frames as f64 * ELD_FRAME_SAMPLES as f64 / ELD_SAMPLE_RATE as f64;
                    info!(
                        "[audio] {} frames ({:.0}s), {} errors, level {:.2}",
                        frames, secs, errors, env
                    );
                }
            }
            status.set_level(0.0);
            info!(
                "[audio] hi-res decode loop ended ({} frames, {} errors)",
                frames, errors
            );
        })
        .expect("failed to spawn hires-decode thread")
}
