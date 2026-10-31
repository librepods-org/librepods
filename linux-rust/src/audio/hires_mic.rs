//! Hi-res microphone lifecycle: a persistent virtual input device plus a monitor
//! that runs the AACP 0x58 capture only while an app is recording from it.

use std::sync::Arc;
use std::sync::Mutex;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use log::{debug, error, info, warn};
use tokio::sync::Notify;
use tokio::sync::mpsc;
use tokio::task::JoinHandle as TokioHandle;

use crate::audio::eld::{ELD_CHANNELS, ELD_FRAME_SAMPLES, ELD_SAMPLE_RATE, EldDecoder};
use crate::audio::card::NOT_WORN_GRACE;
use crate::audio::output::{self, Output, VirtualMic};
use crate::bluetooth::aacp::AACPManager;
use crate::bluetooth::aacp_audio;

/// Delay after sending 0x58 STOP before resetting the A2DP transport
const A2DP_RESET_DELAY: Duration = Duration::from_millis(800);
/// How often the monitor polls the virtual source for activity
const POLL_INTERVAL: Duration = Duration::from_millis(400);
/// If no audio SDUs arrive for this long while a capture is active, the stream
/// is considered stalled and the capture is torn down and restarted.
const STALL_TIMEOUT: Duration = Duration::from_millis(2000);
/// How long the AirPods mic stream keeps running after the last app stopped
/// recording. Every STOP needs an A2DP reset (a short audible dip), and apps
/// close and reopen the mic often: push-to-talk dictation does it for every
/// sentence, voice chat apps when rejoining a call or changing settings.
/// Lingering turns a burst of open/close into one START and one STOP.
const RECORDER_GONE_GRACE: Duration = Duration::from_secs(60);
/// Upper bound for the delay between stall restarts that keep failing.
const MAX_RESTART_BACKOFF: Duration = Duration::from_secs(30);
/// After this many restarts in a row without audio, stop retrying until the ear
/// state changes (e.g. the remaining bud doesn't stream its mic); retrying
/// forever would only churn the AACP channel.
const MAX_FAILED_RESTARTS: u32 = 3;
const LEVEL_RELEASE: f32 = 0.85;

#[derive(Debug, PartialEq, Eq)]
enum CaptureAction {
    /// An app started recording while a bud is in an ear: send 0x58 START.
    Start,
    /// Capturing and the device delivers audio: nothing to do.
    Keep,
    /// Capturing but no audio SDUs for STALL_TIMEOUT while worn: STOP + START.
    Restart,
    /// Capture must end (STOP, then an A2DP reset).
    Stop,
    /// Not capturing and nothing to start.
    Idle,
}

/// Decide what the hi-res monitor does this poll. The AirPods only deliver mic
/// audio while a bud is in an ear, so capture is tied to `worn` (which includes
/// the not-worn grace period); otherwise the stall watchdog would keep
/// restarting the stream while the buds are out.
fn capture_action(
    enabled: bool,
    recording: bool,
    worn: bool,
    capturing: bool,
    stalled: bool,
) -> CaptureAction {
    let want = enabled && recording && worn;
    match (want, capturing) {
        (true, false) => CaptureAction::Start,
        (true, true) if stalled => CaptureAction::Restart,
        (true, true) => CaptureAction::Keep,
        (false, true) => CaptureAction::Stop,
        (false, false) => CaptureAction::Idle,
    }
}

/// Process-wide "an app is recording from the hi-res mic right now" (there is
/// one AirPods connection at a time). Used to avoid disruptive actions such as a
/// WirePlumber restart in the middle of a call.
static ANY_CAPTURE_ACTIVE: AtomicBool = AtomicBool::new(false);

pub fn any_capture_active() -> bool {
    ANY_CAPTURE_ACTIVE.load(Ordering::Relaxed)
}

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
        ANY_CAPTURE_ACTIVE.store(app.is_some(), Ordering::Relaxed);
        *self.app.lock().unwrap() = app;
    }

    fn reset(&self) {
        self.active.store(false, Ordering::Relaxed);
        ANY_CAPTURE_ACTIVE.store(false, Ordering::Relaxed);
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
    stop: Arc<Notify>,
    monitor: Option<TokioHandle<()>>,
}

impl HiResMic {
    // Create the persistent virtual input and spawn the activity monitor. The
    // monitor owns the virtual device and unloads it when it exits.
    pub async fn start(aacp: &AACPManager, addr: String, status: MicStatus) -> Option<HiResMic> {
        // Name it after the AirPods, like their output: "<AirPods name> Microphone".
        let device_name = aacp
            .state
            .lock()
            .await
            .devices
            .get(&addr)
            .map(|d| d.name.trim().to_string())
            .filter(|n| !n.is_empty() && n != &addr);
        let mic_name = match device_name {
            Some(name) => format!("{name} Microphone"),
            None => "AirPods Microphone".to_string(),
        };
        let vmic = open_virtual_mic(&mic_name).await?;
        refresh_desktop_default_source(aacp.runtime());
        let stop = Arc::new(Notify::new());
        let wake = aacp.hires_wake();
        // Spawn on the backend runtime, not the caller's (the UI toggle uses a
        // throwaway runtime that would abort this task immediately).
        let monitor = aacp.runtime().spawn(monitor_loop(
            aacp.clone(),
            addr,
            status,
            stop.clone(),
            wake,
            vmic,
            mic_name,
        ));
        Some(HiResMic {
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

    pub fn is_running(&self) -> bool {
        self.monitor.as_ref().is_some_and(|h| !h.is_finished())
    }
}

// A live capture session: the playback stream feeding the sink plus its decode
// thread, started when an app opens the mic.
struct Capture {
    decode_thread: Option<JoinHandle<()>>,
}

async fn monitor_loop(
    aacp: AACPManager,
    addr: String,
    status: MicStatus,
    stop: Arc<Notify>,
    wake: Arc<Notify>,
    vmic: VirtualMic,
    mic_name: String,
) {
    // None while hidden: with no bud worn (past the grace period) the virtual mic
    // is removed so apps fall back to another mic, like the output does.
    let mut vmic: Option<VirtualMic> = Some(vmic);
    let mut capture: Option<Capture> = None;
    // Conversation detection value saved while we override it off for capture.
    let mut old_convo_state: Option<bool> = None;
    let mut restart_backoff = STALL_TIMEOUT;
    let mut next_restart_at = Instant::now();
    let mut failed_restarts: u32 = 0;
    // Ear generation at which we gave up restarting; retry once it changes.
    let mut gave_up_at_ear: Option<u64> = None;
    // When the recorder disappeared while capturing (stop grace period).
    let mut recorder_gone_since: Option<Instant> = None;
    let mut source_missing_polls: u32 = 0;
    // When the last bud left an ear (None while worn).
    let mut out_since: Option<Instant> = None;
    // A STOP happened with the buds out and the card stayed on: the transport is
    // in the post-STOP state, so reset it once a bud is back (unless a START does).
    let mut reset_when_worn = false;
    info!(
        "[hires] activity monitor started, watching '{}'",
        output::SOURCE_NAME
    );

    loop {
        tokio::select! {
            _ = stop.notified() => break,
            _ = wake.notified() => {}
            _ = tokio::time::sleep(POLL_INTERVAL) => {}
        }

        // --- Wearing, with a grace period -----------------------------------
        // Taking both buds out briefly (adjusting them) must not stop the mic,
        // hide it or switch outputs.
        let worn_now = aacp.is_worn().await;
        if worn_now {
            out_since = None;
        } else if out_since.is_none() {
            out_since = Some(Instant::now());
        }
        let worn = worn_now || out_since.is_some_and(|t| t.elapsed() < NOT_WORN_GRACE);

        // Show the virtual mic again as soon as a bud is back.
        if worn_now && vmic.is_none() {
            info!("[hires] bud back in; restoring '{}'", output::SOURCE_NAME);
            vmic = open_virtual_mic(&mic_name).await;
            if vmic.is_some() {
                refresh_desktop_default_source(&tokio::runtime::Handle::current());
            }
            source_missing_polls = 0;
        }

        let usage = if vmic.is_some() {
            tokio::task::spawn_blocking(|| output::source_consumer(output::SOURCE_NAME))
                .await
                .unwrap_or(output::SourceUse::Present(None))
        } else {
            output::SourceUse::Present(None)
        };
        let app = match usage {
            output::SourceUse::Present(app) => {
                source_missing_polls = 0;
                app
            }
            output::SourceUse::Missing => {
                // pipewire-pulse restarted (or the module was unloaded): the
                // virtual mic is gone and would never come back until the AirPods
                // reconnect. Recreate it after two polls in a row.
                source_missing_polls += 1;
                if source_missing_polls >= 2 {
                    warn!("[hires] '{}' disappeared; recreating it", output::SOURCE_NAME);
                    if let Some(c) = capture.take() {
                        // It writes into the old, now orphaned FIFO.
                        stop_capture(c, &aacp).await;
                        schedule_a2dp_reset(&addr);
                        if let Some(prev) = old_convo_state.take() {
                            restore_conversation_detection(&aacp, prev).await;
                        }
                        status.reset();
                    }
                    if let Some(fresh) = open_virtual_mic(&mic_name).await {
                        // Don't run the old one's Drop: its module index belongs
                        // to the dead server and may now name someone else's module.
                        if let Some(old) = vmic.replace(fresh) {
                            std::mem::forget(old);
                        }
                        refresh_desktop_default_source(&tokio::runtime::Handle::current());
                        source_missing_polls = 0;
                    }
                }
                None
            }
        };
        let enabled = aacp.hires_mic_enabled();
        // Apps close and reopen their mic stream often (push-to-talk, rejoining
        // a call, changing audio settings). Keep capturing through the gap; see
        // RECORDER_GONE_GRACE.
        if app.is_some() {
            recorder_gone_since = None;
        } else if capture.is_some() && recorder_gone_since.is_none() {
            recorder_gone_since = Some(Instant::now());
        }
        let in_grace = capture.is_some()
            && recorder_gone_since.is_some_and(|t| t.elapsed() < RECORDER_GONE_GRACE);
        let recording = app.is_some() || in_grace;

        // A stall only counts while a bud is really in: during the not-worn
        // grace the AirPods simply don't send mic audio.
        let stalled = worn_now
            && capture.is_some()
            && status.since_last_sdu().is_some_and(|d| d > STALL_TIMEOUT);

        let action = capture_action(enabled, recording, worn, capture.is_some(), stalled);
        if worn_now && reset_when_worn && action != CaptureAction::Start {
            info!("[hires] bud back after a STOP with buds out; resetting A2DP");
            schedule_a2dp_reset(&addr);
        }
        if worn_now || action == CaptureAction::Start {
            reset_when_worn = false;
        }

        match action {
            CaptureAction::Start => {
                info!("[hires] recorder detected ({:?}), starting capture", app);
                if let Some(c) = start_capture(&aacp, &addr, &status).await {
                    status.set_capture(app);
                    capture = Some(c);

                    // Only disable (and remember to restore) if it was on.
                    aacp.take_convo_user_changed();
                    if crate::utils::AppSettings::load().hires_mic_pause_convo
                        && aacp.conversation_detection_enabled().await
                    {
                        old_convo_state = Some(true);
                        aacp.set_conversation_detection(false).await;
                    }
                }
            }
            CaptureAction::Keep => {
                if app.is_some() {
                    status.set_capture(app);
                }
                if worn_now {
                    restart_backoff = STALL_TIMEOUT;
                    failed_restarts = 0;
                    gave_up_at_ear = None;
                }
            }
            CaptureAction::Restart => {
                if app.is_some() {
                    status.set_capture(app);
                }
                if let Some(generation) = gave_up_at_ear {
                    if aacp.ear_generation() == generation {
                        continue;
                    }
                    info!("[hires] ear state changed; retrying the mic stream");
                    gave_up_at_ear = None;
                    failed_restarts = 0;
                    restart_backoff = STALL_TIMEOUT;
                    next_restart_at = Instant::now();
                }
                if failed_restarts >= MAX_FAILED_RESTARTS {
                    warn!(
                        "[hires] no mic audio after {} restarts; waiting for an ear change",
                        failed_restarts
                    );
                    gave_up_at_ear = Some(aacp.ear_generation());
                    continue;
                }
                if Instant::now() >= next_restart_at {
                    failed_restarts += 1;
                    warn!(
                        "[hires] no audio from device for {}ms, restarting capture",
                        STALL_TIMEOUT.as_millis()
                    );
                    stop_capture(capture.take().unwrap(), &aacp).await;
                    capture = start_capture(&aacp, &addr, &status).await;
                    if capture.is_none() {
                        warn!("[hires] capture restart failed; will retry on next poll");
                        status.reset();
                    }
                    // Back off if the device keeps not delivering (e.g. another
                    // device holds the AirPods' mic) instead of restarting every
                    // couple of seconds.
                    next_restart_at = Instant::now() + restart_backoff;
                    restart_backoff = (restart_backoff * 2).min(MAX_RESTART_BACKOFF);
                }
            }
            // Disabled, recorder gone, or no bud worn (past the grace): end the
            // 0x58 stream.
            CaptureAction::Stop => {
                info!(
                    "[hires] stopping capture (enabled={}, recording={}, worn={})",
                    enabled, recording, worn
                );
                stop_capture(capture.take().unwrap(), &aacp).await;
                if worn_now {
                    // After a STOP the AirPods keep playing from one earbud only
                    // until the A2DP transport is reset (e.g. after leaving a call).
                    schedule_a2dp_reset(&addr);
                } else if !crate::utils::AppSettings::load().audio_off_when_not_wearing {
                    // Buds out and the card stays on: reset once a bud is back.
                    // When the card is being switched off instead, there's nothing
                    // to do: switching it back on creates a fresh transport.
                    reset_when_worn = true;
                }
                if let Some(prev) = old_convo_state.take() {
                    restore_conversation_detection(&aacp, prev).await;
                }
                status.reset();
                restart_backoff = STALL_TIMEOUT;
                failed_restarts = 0;
                gave_up_at_ear = None;
                recorder_gone_since = None;
            }
            CaptureAction::Idle => {
                recorder_gone_since = None;
            }
        }

        // No bud worn past the grace period: hide the virtual mic so apps (and
        // the default source) fall back to another mic, matching the output that
        // the media controller switches away at the same moment.
        if !worn
            && capture.is_none()
            && vmic.is_some()
            && crate::utils::AppSettings::load().audio_off_when_not_wearing
        {
            info!(
                "[hires] no bud in ear; hiding '{}' so apps use another mic",
                output::SOURCE_NAME
            );
            vmic = None;
        }

        // Once disabled and nothing is recording from the source, unload it.
        if !enabled && !recording {
            break;
        }
    }

    if let Some(c) = capture.take() {
        stop_capture(c, &aacp).await;
        if let Some(prev) = old_convo_state.take() {
            restore_conversation_detection(&aacp, prev).await;
        }
    }
    status.reset();
}

/// The virtual mic was just (re)created: make desktop volume controls pick up
/// the default source again (see card::refresh_default).
fn refresh_desktop_default_source(runtime: &tokio::runtime::Handle) {
    crate::audio::card::schedule_default_refresh(
        runtime,
        crate::audio::card::DefaultKind::Source,
        output::SOURCE_NAME.to_string(),
        Duration::from_millis(1500),
    );
}

async fn open_virtual_mic(name: &str) -> Option<VirtualMic> {
    let name = name.to_string();
    tokio::task::spawn_blocking(move || VirtualMic::open(ELD_SAMPLE_RATE, ELD_CHANNELS as u8, &name))
        .await
        .ok()
        .flatten()
}

/// Turn conversation awareness back on after a capture. If the AirPods are
/// already gone (disconnected mid-capture), remember it so the next connection
/// restores it; the setting lives on the AirPods and would otherwise stay off.
async fn restore_conversation_detection(aacp: &AACPManager, enabled: bool) {
    if aacp.take_convo_user_changed() {
        info!("[hires] conversation awareness was changed by the user; not restoring it");
        return;
    }
    if !aacp.set_conversation_detection(enabled).await && enabled {
        info!("[hires] AirPods gone; will restore conversation awareness on next connect");
        let mut settings = crate::utils::AppSettings::load();
        settings.convo_restore_pending = true;
        settings.save();
    }
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

    // No A2DP reset after START: playback keeps working in both buds when the
    // mic stream starts (tested with AirPods Pro, model A3063). Only STOP needs
    // one.
    let _ = addr;

    Some(Capture {
        decode_thread: Some(decode_thread),
    })
}

// Reset the A2DP transport shortly after a 0x58 STOP: afterwards the AirPods
// play from one earbud only until the transport is reset (see
// output::reset_a2dp).
//
// Coalesced: requests close together run one reset, after the last of them.
fn schedule_a2dp_reset(addr: &str) {
    let now = Instant::now();
    {
        let mut pending = RESET_AT.lock().unwrap_or_else(|e| e.into_inner());
        let already_pending = pending.is_some();
        // (deadline, time of the latest START/STOP that asked for it)
        *pending = Some((now + A2DP_RESET_DELAY, now));
        if already_pending {
            debug!("[hires] A2DP reset already pending; pushed back");
            return;
        }
    }
    let reset_addr = addr.to_string();
    tokio::spawn(async move {
        let requested_at = loop {
            let pending = *RESET_AT.lock().unwrap_or_else(|e| e.into_inner());
            let Some((deadline, requested_at)) = pending else { return };
            let now = Instant::now();
            if now >= deadline {
                *RESET_AT.lock().unwrap_or_else(|e| e.into_inner()) = None;
                break requested_at;
            }
            tokio::time::sleep(deadline - now).await;
        };
        // The card was switched back on after the request (a bud went back in):
        // that already created a fresh transport, so no reset is needed.
        if crate::audio::card::activated_since(requested_at) {
            info!("[hires] A2DP was just re-activated; no reset needed");
            return;
        }
        let _ = tokio::task::spawn_blocking(move || output::reset_a2dp(&reset_addr)).await;
    });
}

/// Pending A2DP reset: (when to run it, when it was last requested).
static RESET_AT: Mutex<Option<(Instant, Instant)>> = Mutex::new(None);

async fn stop_capture(mut capture: Capture, aacp: &AACPManager) {
    if let Err(e) = aacp.send_stop_audio().await {
        warn!("failed to send 0x58 STOP: {}", e);
    }
    aacp.clear_audio_channel().await;

    if let Some(handle) = capture.decode_thread.take() {
        let _ = tokio::task::spawn_blocking(move || handle.join()).await;
    }
    // The A2DP reset after a STOP is scheduled by the caller (monitor_loop): a
    // stall restart (STOP immediately followed by START) doesn't need one.
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

#[cfg(test)]
mod tests {
    use super::{CaptureAction, capture_action};

    #[test]
    fn starts_only_when_enabled_recording_and_worn() {
        assert_eq!(capture_action(true, true, true, false, false), CaptureAction::Start);
        assert_eq!(capture_action(false, true, true, false, false), CaptureAction::Idle);
        assert_eq!(capture_action(true, false, true, false, false), CaptureAction::Idle);
        // Both buds out: an app holding the mic must not start the 0x58 stream.
        assert_eq!(capture_action(true, true, false, false, false), CaptureAction::Idle);
    }

    #[test]
    fn buds_out_stops_and_never_restarts() {
        assert_eq!(capture_action(true, true, false, true, true), CaptureAction::Stop);
        assert_eq!(capture_action(true, true, false, true, false), CaptureAction::Stop);
    }

    #[test]
    fn app_closing_mic_or_disabling_stops() {
        // Leaving a call: recorder gone, buds still in.
        assert_eq!(capture_action(true, false, true, true, false), CaptureAction::Stop);
        // Feature disabled mid-capture.
        assert_eq!(capture_action(false, true, true, true, false), CaptureAction::Stop);
    }

    #[test]
    fn stall_restarts_only_while_capturing_and_worn() {
        assert_eq!(capture_action(true, true, true, true, true), CaptureAction::Restart);
        assert_eq!(capture_action(true, true, true, true, false), CaptureAction::Keep);
    }
}
