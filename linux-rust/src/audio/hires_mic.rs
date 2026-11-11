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
/// After connecting, how long a parked mic waits for the first ear-detection
/// report before it is unparked anyway (ear detection may be switched off).
const EAR_REPORT_WAIT: Duration = Duration::from_secs(3);

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
    // Make the shared virtual input available (see SharedMic) and spawn the
    // activity monitor. When the monitor exits the input is kept (and parked
    // later), or unloaded if the hi-res mic was turned off.
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
        let created = {
            let name = mic_name.clone();
            blocking(move || acquire_mic(&name)).await.flatten()?
        };
        if created {
            refresh_desktop_default_source(aacp.runtime());
        }
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
    mic_name: String,
) {
    // False while parked: with no bud worn (past the grace period) apps are sent
    // to another mic, like the output is. A mic that was parked before this
    // connection (disconnect, earlier run) stays parked until a bud is worn.
    let mut shown = !blocking(mic_is_parked).await.unwrap_or(false);
    let started = Instant::now();
    let mut capture: Option<Capture> = None;
    // Conversation detection value saved while we override it off for capture.
    let mut old_convo_state: Option<bool> = None;
    let mut restart_backoff = STALL_TIMEOUT;
    let mut next_restart_at = Instant::now();
    // Earliest next START after one failed (don't retry on every poll).
    let mut next_start_at = Instant::now();
    let mut failed_restarts: u32 = 0;
    // Ear generation at which we gave up restarting; retry once it changes.
    let mut gave_up_at_ear: Option<u64> = None;
    // When the recorder disappeared while capturing (stop grace period).
    let mut recorder_gone_since: Option<Instant> = None;
    let mut source_missing_polls: u32 = 0;
    // When the last bud left an ear (None while worn).
    let mut out_since: Option<Instant> = None;
    // A bud was reported in an ear on this connection. The not-worn grace only
    // applies after that: AirPods that connect from the open case were never
    // worn, so there is nothing to ride out.
    let mut seen_worn = false;
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
        // Right after connecting is_worn() is only a guess until the AirPods
        // report their ear state (they connect when the case opens, not yet
        // worn): wait for the report, or briefly in case ear detection is off.
        // The report also marks the end of the AACP handshake; a mic START sent
        // before that is ignored by the AirPods (the capture then stalls and has
        // to be restarted) and lands in the middle of A2DP setup.
        let ear_known = aacp.ear_status_reported().await || started.elapsed() > EAR_REPORT_WAIT;
        if worn_now {
            out_since = None;
            seen_worn |= ear_known;
        } else if out_since.is_none() {
            out_since = Some(Instant::now());
        }
        let worn = worn_now
            || (seen_worn && out_since.is_some_and(|t| t.elapsed() < NOT_WORN_GRACE));

        // Bring apps back to the virtual mic as soon as a bud is back.
        if worn_now && !shown && ear_known {
            info!("[hires] bud back in; unparking '{}'", output::SOURCE_NAME);
            blocking(unpark_mic).await;
            shown = true;
        }

        let usage = blocking(|| output::source_consumer(output::SOURCE_NAME))
            .await
            .unwrap_or(output::SourceUse::Present(None));
        let app = match usage {
            // Parked: whoever still records from it gets silence; don't start
            // the AirPods mic for them.
            output::SourceUse::Present(_) if !shown => {
                source_missing_polls = 0;
                None
            }
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
                    let name = mic_name.clone();
                    if blocking(move || recreate_mic(&name)).await.unwrap_or(false) {
                        if !shown {
                            // No-op if the parked state was carried over.
                            blocking(park_mic).await;
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
            CaptureAction::Start if Instant::now() < next_start_at => {}
            CaptureAction::Start => {
                info!("[hires] recorder detected ({:?}), starting capture", app);
                let new_capture = start_capture(&aacp, &addr, &status).await;
                if new_capture.is_none() {
                    warn!("[hires] could not start the capture; retrying shortly");
                    next_start_at = Instant::now() + STALL_TIMEOUT;
                }
                if let Some(c) = new_capture {
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
                        warn!("[hires] capture restart failed; retrying shortly");
                        status.reset();
                        // The STOP went out without a START after it.
                        schedule_a2dp_reset(&addr);
                        next_start_at = Instant::now() + STALL_TIMEOUT;
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

        // No bud worn past the grace period: park the virtual mic so apps (and
        // the default source) switch to another mic, matching the output that
        // the media controller switches away at the same moment.
        if !worn
            && capture.is_none()
            && shown
            && crate::utils::AppSettings::load().audio_off_when_not_wearing
        {
            info!(
                "[hires] no bud in ear; parking '{}' so apps use another mic",
                output::SOURCE_NAME
            );
            blocking(park_mic).await;
            shown = false;
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
        // The AirPods are still connected (LibrePods is exiting, or the monitor
        // is being replaced): the STOP needs its A2DP reset like any other, or
        // they keep playing from one earbud. Done here rather than scheduled,
        // since the process may exit before a scheduled one runs.
        if aacp.is_connected() {
            tokio::time::sleep(A2DP_RESET_DELAY).await;
            let reset_addr = addr.clone();
            blocking(move || output::reset_a2dp(&reset_addr)).await;
        }
    }
    status.reset();

    if aacp.hires_mic_enabled() {
        // Disconnected (or LibrePods exiting): keep the virtual mic.
        release_mic(&tokio::runtime::Handle::current());
    } else {
        blocking(remove_mic).await;
    }
}

/// The virtual mic was just created: make desktop volume controls pick up the
/// default source again (see card::refresh_default). That briefly switches the
/// default to another mic and back, which moves every app recording from the
/// default along with it, so it's skipped while anything records.
fn refresh_desktop_default_source(runtime: &tokio::runtime::Handle) {
    runtime.spawn(async {
        tokio::time::sleep(Duration::from_millis(1500)).await;
        let _ = tokio::task::spawn_blocking(|| {
            if output::any_recorder() {
                debug!("[hires] an app is recording; not refreshing the default source");
                return;
            }
            crate::audio::card::refresh_default(
                crate::audio::card::DefaultKind::Source,
                output::SOURCE_NAME,
            );
        })
        .await;
    });
}

// --- The virtual mic, shared by every AirPods connection --------------------
//
// Some apps crash when a microphone is added or removed while they record:
// Discord's voice engine aborts (in libpulse, from its mic volume control) on
// any source appearing or disappearing, even one it doesn't use. So the
// virtual mic is created once and then kept: across disconnects, with no bud
// worn, and across LibrePods restarts (a new run adopts the loaded module). It
// is only unloaded when the hi-res mic is turned off.
//
// While the AirPods can't deliver mic audio the mic is "parked": another mic
// becomes the default and apps pinned to ours are moved to it, then both are
// undone when the AirPods are back. Apps see an ordinary device switch.

/// After a disconnect, how long the mic stays as it is before it is parked. A
/// short drop (the AirPods reconnect right away) then changes nothing at all.
const DISCONNECT_PARK_DELAY: Duration = Duration::from_secs(15);
/// After switching the default mic, time for the session manager to move the
/// streams that follow the default before we look for the ones left behind.
const DEFAULT_SWITCH_SETTLE: Duration = Duration::from_millis(400);

struct SharedMic {
    vmic: VirtualMic,
    /// Some while parked.
    parked: Option<Parked>,
    /// AirPods connection monitors using it (normally 0 or 1; briefly 2 while a
    /// reconnect replaces a stale connection).
    users: u32,
    /// Bumped on every acquire, so a delayed park only acts on the release it
    /// was scheduled for.
    generation: u64,
}

/// What parking changed, to undo it when the AirPods are back.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
struct Parked {
    /// The mic made default while parked (only if ours was the default).
    default_set: Option<String>,
    /// Recorders still on our mic after the default switch (apps pinned to it)
    /// that were moved to the other mic.
    moved: Vec<output::Recorder>,
}

impl Parked {
    fn serialize(&self) -> String {
        let mut s = String::new();
        if let Some(d) = &self.default_set {
            s.push_str(&format!("default\t{d}\n"));
        }
        for r in &self.moved {
            s.push_str(&format!("moved\t{}\t{}\n", r.index, r.app.as_deref().unwrap_or("")));
        }
        s
    }

    fn parse(text: &str) -> Parked {
        let mut p = Parked::default();
        for line in text.lines() {
            let mut fields = line.splitn(3, '\t');
            match (fields.next(), fields.next(), fields.next()) {
                (Some("default"), Some(name), None) if !name.is_empty() => {
                    p.default_set = Some(name.to_string());
                }
                (Some("moved"), Some(index), app) => {
                    if let Ok(index) = index.parse() {
                        p.moved.push(output::Recorder {
                            index,
                            app: app.filter(|a| !a.is_empty()).map(str::to_string),
                        });
                    }
                }
                _ => {}
            }
        }
        p
    }
}

static SHARED_MIC: Mutex<Option<SharedMic>> = Mutex::new(None);

/// Parked state, kept next to the FIFO so a restarted LibrePods that adopts the
/// mic can still undo it.
fn parked_path() -> String {
    let dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| "/tmp".to_string());
    format!("{dir}/librepods-hires.parked")
}

fn save_parked(parked: Option<&Parked>) {
    match parked {
        Some(p) => {
            if let Err(e) = crate::utils::write_atomic(
                std::path::Path::new(&parked_path()),
                p.serialize().as_bytes(),
            ) {
                warn!("[hires] could not save the parked mic state: {}", e);
            }
        }
        None => {
            let _ = std::fs::remove_file(parked_path());
        }
    }
}

fn shared_mic() -> std::sync::MutexGuard<'static, Option<SharedMic>> {
    SHARED_MIC.lock().unwrap_or_else(|e| e.into_inner())
}

/// Send apps to another mic (see the section comment). Blocking.
fn park() -> Parked {
    let mut parked = Parked::default();
    let Some(fallback) = output::fallback_source(output::SOURCE_NAME) else {
        info!("[hires] no other microphone to switch apps to; leaving them on '{}'", output::SOURCE_NAME);
        return parked;
    };
    if output::default_source().as_deref() == Some(output::SOURCE_NAME) {
        if output::set_default_source(&fallback) {
            info!("[hires] AirPods mic unavailable; default microphone is now {}", fallback);
            parked.default_set = Some(fallback.clone());
            std::thread::sleep(DEFAULT_SWITCH_SETTLE);
        } else {
            warn!("[hires] could not make {} the default microphone", fallback);
        }
    }
    let left = output::recorders_on(output::SOURCE_NAME);
    parked.moved = output::move_recorders(&left, &fallback);
    parked
}

/// Undo park(): restore our mic as the default (unless the user picked another
/// one meanwhile) and move the apps that were moved back. Blocking.
fn unpark(parked: &Parked) {
    if let Some(fallback) = &parked.default_set {
        if output::default_source().as_deref() == Some(fallback.as_str()) {
            if output::set_default_source(output::SOURCE_NAME) {
                info!("[hires] AirPods mic back; it's the default microphone again");
            }
        } else {
            info!("[hires] default microphone was changed meanwhile; leaving it");
        }
    }
    if !parked.moved.is_empty() {
        output::move_recorders(&parked.moved, output::SOURCE_NAME);
    }
}

/// Make the virtual mic available to an AirPods connection: reuse the existing
/// one or adopt/create it. Returns Some(true) if a new device was
/// created, Some(false) if an existing one is used. Blocking.
fn acquire_mic(description: &str) -> Option<bool> {
    let mut guard = shared_mic();
    let created = match guard.as_mut() {
        // If it's parked it stays parked: the monitor unparks it once a bud
        // is worn (see monitor_loop).
        Some(_) => false,
        None => {
            let (vmic, created) = match VirtualMic::adopt() {
                Some(vmic) => (vmic, false),
                None => (
                    VirtualMic::open(ELD_SAMPLE_RATE, ELD_CHANNELS as u8, description)?,
                    true,
                ),
            };
            // An earlier run may have parked the adopted mic; keep that state
            // so the monitor can undo it once a bud is worn. A freshly
            // created mic has nothing to undo.
            let parked = if created {
                save_parked(None);
                None
            } else {
                std::fs::read_to_string(parked_path())
                    .ok()
                    .map(|t| Parked::parse(&t))
            };
            *guard = Some(SharedMic {
                vmic,
                parked,
                users: 0,
                generation: 0,
            });
            created
        }
    };
    let mic = guard.as_mut().expect("set above");
    mic.users += 1;
    mic.generation += 1;
    Some(created)
}

/// True if the mic exists and is parked. Blocking.
fn mic_is_parked() -> bool {
    shared_mic().as_ref().is_some_and(|m| m.parked.is_some())
}

/// Park the mic now (no bud worn, or LibrePods exiting). Blocking.
fn park_mic() {
    let mut guard = shared_mic();
    if let Some(mic) = guard.as_mut()
        && mic.parked.is_none()
    {
        let parked = park();
        save_parked(Some(&parked));
        mic.parked = Some(parked);
    }
}

/// Unpark the mic (a bud is back in). Blocking.
fn unpark_mic() {
    let mut guard = shared_mic();
    if let Some(mic) = guard.as_mut()
        && let Some(parked) = mic.parked.take()
    {
        unpark(&parked);
        save_parked(None);
    }
}

/// The AirPods connection ended: park the mic after DISCONNECT_PARK_DELAY
/// unless a new connection took it meanwhile.
fn release_mic(runtime: &tokio::runtime::Handle) {
    let generation = {
        let mut guard = shared_mic();
        let Some(mic) = guard.as_mut() else { return };
        mic.users = mic.users.saturating_sub(1);
        mic.generation
    };
    runtime.spawn(async move {
        tokio::time::sleep(DISCONNECT_PARK_DELAY).await;
        let _ = tokio::task::spawn_blocking(move || {
            let mut guard = shared_mic();
            let Some(mic) = guard.as_mut() else { return };
            if mic.users > 0 || mic.generation != generation || mic.parked.is_some() {
                return;
            }
            info!("[hires] AirPods still gone; switching apps to another microphone");
            let parked = park();
            save_parked(Some(&parked));
            mic.parked = Some(parked);
        })
        .await;
    });
}

/// The hi-res mic was turned off: unload the virtual mic. Blocking.
fn remove_mic() {
    let mic = shared_mic().take();
    if let Some(mic) = mic {
        info!("[hires] hi-res mic turned off; removing '{}'", output::SOURCE_NAME);
        drop(mic.vmic);
    }
    save_parked(None);
}

/// pipewire-pulse restarted and dropped the virtual mic: create a new one. The
/// old module index belongs to the dead server (and may now name someone else's
/// module), so it's forgotten rather than unloaded. Blocking.
fn recreate_mic(description: &str) -> bool {
    let mut guard = shared_mic();
    let Some(fresh) = VirtualMic::open(ELD_SAMPLE_RATE, ELD_CHANNELS as u8, description) else {
        return false;
    };
    save_parked(None);
    match guard.as_mut() {
        Some(mic) => {
            std::mem::forget(std::mem::replace(&mut mic.vmic, fresh));
            mic.parked = None;
        }
        None => {
            *guard = Some(SharedMic {
                vmic: fresh,
                parked: None,
                users: 1,
                generation: 1,
            });
        }
    }
    true
}

/// LibrePods is exiting: keep the virtual mic loaded (the next run adopts it)
/// but send apps to another mic, since nothing will feed this one.
pub fn park_on_exit() {
    park_mic();
}

async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> Option<T> {
    tokio::task::spawn_blocking(f).await.ok()
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
        crate::utils::AppSettings::update(|s| s.convo_restore_pending = true);
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
    use super::{CaptureAction, Parked, capture_action};
    use crate::audio::output::Recorder;

    #[test]
    fn parked_state_round_trips() {
        let parked = Parked {
            default_set: Some("alsa_input.usb-mic.mono-fallback".into()),
            moved: vec![
                Recorder { index: 117144, app: Some("WEBRTC VoiceEngine".into()) },
                Recorder { index: 7, app: None },
            ],
        };
        assert_eq!(Parked::parse(&parked.serialize()), parked);
        assert_eq!(Parked::parse(""), Parked::default());
        assert_eq!(Parked::parse("garbage\nmoved\tx\tapp\n"), Parked::default());
    }

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
