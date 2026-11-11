//! PipeWire/PulseAudio output for the hi-res microphone

use libpulse_binding::callbacks::ListResult;
use libpulse_binding::context::{Context, FlagSet as ContextFlagSet};
use libpulse_binding::def::Retval;
use libpulse_binding::mainloop::standard::{IterateResult, Mainloop};
use libpulse_binding::operation::{Operation, State as OperationState};
use log::{error, info, warn};
use std::cell::{Cell, RefCell};
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::rc::Rc;
use std::time::{Duration, Instant};

use dbus::blocking::Connection;
use dbus::blocking::stdintf::org_freedesktop_dbus::Properties;

use crate::audio::agc::Agc;

pub const SOURCE_NAME: &str = "AirPodsHiRes";

// FIFO the pipe-source reads from and that Output writes PCM into.
fn fifo_path() -> String {
    let dir = std::env::var("XDG_RUNTIME_DIR").unwrap_or_else(|_| "/tmp".to_string());
    format!("{dir}/librepods-hires.fifo")
}

pub struct VirtualMic {
    module: u32,
}

impl VirtualMic {
    /// `description` is the name shown in sound settings (e.g. "AirPods Pro
    /// Microphone"); the node name stays SOURCE_NAME so saved defaults and app
    /// selections keep working.
    pub fn open(sample_rate: u32, channels: u8, description: &str) -> Option<VirtualMic> {
        unload_stale_modules();

        let chan_map = if channels == 1 {
            "mono"
        } else {
            "front-left,front-right"
        };

        let fifo = fifo_path();
        let _ = std::fs::remove_file(&fifo); // drop any stale FIFO from a prior run

        // node.virtual=false: pipe-sources are virtual by default, so they lack the HARDWARE
        // flag and Plasma's volume applet hides them unless "Show virtual devices" is on.
        // Quoting: the property list in single quotes, the description (which
        // may contain spaces) in double quotes inside it.
        let description: String = description
            .chars()
            .filter(|c| !matches!(c, '"' | '\'' | '\\'))
            .collect();
        let args = format!(
            "source_name={SOURCE_NAME} file={fifo} format=s16le rate={sample_rate} \
             channels={channels} channel_map={chan_map} \
             source_properties='device.description=\"{description}\" node.driver=false priority.driver=0 node.virtual=false'"
        );
        let module = match load_module("module-pipe-source", &args) {
            Some(i) => i,
            None => {
                warn!("could not load module-pipe-source");
                return None;
            }
        };

        info!(
            "[pw] hi-res mic ready: select '{}' ({}) as your microphone",
            description, SOURCE_NAME
        );
        Some(VirtualMic { module })
    }

    /// Take over a virtual mic left loaded by an earlier LibrePods run instead
    /// of unloading it and creating a new one: some apps (Discord) crash when a
    /// microphone appears or disappears while they record. Only adopted if the
    /// source exists and its FIFO is still there; otherwise every leftover module
    /// is unloaded and None returned.
    pub fn adopt() -> Option<VirtualMic> {
        let modules = our_modules();
        if modules.is_empty() {
            return None;
        }
        let fifo = fifo_path();
        let fifo_ok = std::fs::metadata(&fifo).is_ok_and(|m| {
            use std::os::unix::fs::FileTypeExt;
            m.file_type().is_fifo()
        });
        let keep = if fifo_ok && source_index(SOURCE_NAME).is_some() {
            modules
                .iter()
                .find(|(_, arg)| arg.contains(&format!("file={fifo} ")))
                .map(|(i, _)| *i)
        } else {
            None
        };
        for (index, _) in &modules {
            if Some(*index) != keep {
                warn!("[pw] unloading stale hi-res module {}", index);
                unload_module(*index);
            }
        }
        let module = keep?;
        info!("[pw] reusing the hi-res mic left by an earlier run (module {})", module);
        Some(VirtualMic { module })
    }
}

impl Drop for VirtualMic {
    fn drop(&mut self) {
        unload_module(self.module);
        let _ = std::fs::remove_file(fifo_path());
    }
}

// Writes PCM into the pipe-source's FIFO. Opened only while an app is recording.
pub struct Output {
    fifo: File,
    agc: Option<Agc>,
    /// Bytes dropped because the FIFO was full (reader suspended).
    dropped: u64,
}

/// O_NONBLOCK (Linux generic value; libc isn't a direct dependency).
const O_NONBLOCK: i32 = 0o4000;
/// Writes up to PIPE_BUF are atomic on a pipe, so a full FIFO never leaves a
/// partial (misaligned) sample behind.
const PIPE_BUF: usize = 4096;

impl Output {
    pub fn open(_sample_rate: u32, _channels: u8) -> Option<Output> {
        // O_RDWR never blocks on a FIFO and keeps the pipe from ever seeing
        // "all writers closed"; we only ever write to it.
        let path = fifo_path();
        // Non-blocking: when no app is actively reading (e.g. a recorder paused
        // its stream), PipeWire suspends the pipe-source and stops draining the
        // FIFO. A blocking write would then hang the decode thread once the
        // 64 KB pipe fills (~0.5 s at 64 kHz), and stopping the capture would
        // wait on that thread forever. It's live audio, so drop it instead.
        use std::os::unix::fs::OpenOptionsExt;
        let fifo = match OpenOptions::new()
            .read(true)
            .write(true)
            .custom_flags(O_NONBLOCK)
            .open(&path)
        {
            Ok(f) => f,
            Err(e) => {
                error!("could not open hi-res fifo {}: {}", path, e);
                return None;
            }
        };

        let agc = crate::utils::AppSettings::load()
            .hires_mic_agc
            .then(Agc::new);
        if agc.is_none() {
            info!("[pw] AGC disabled; passing through raw hi-res capture");
        }
        Some(Output {
            fifo,
            agc,
            dropped: 0,
        })
    }

    // Write s16 PCM into the FIFO, returning the (post-AGC) peak
    pub fn write(&mut self, pcm: &[i16]) -> Result<f32, ()> {
        let processed;
        let pcm: &[i16] = if let Some(agc) = &mut self.agc {
            let mut buf = pcm.to_vec();
            agc.process(&mut buf);
            processed = buf;
            &processed
        } else {
            pcm
        };

        let peak = pcm
            .iter()
            .map(|&s| (s as f32 / 32768.0).abs())
            .fold(0.0f32, f32::max);

        let bytes = unsafe {
            std::slice::from_raw_parts(pcm.as_ptr() as *const u8, std::mem::size_of_val(pcm))
        };

        for chunk in bytes.chunks(PIPE_BUF) {
            match self.fifo.write(chunk) {
                Ok(_) => {}
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    // Reader not consuming (source suspended): drop this audio.
                    if self.dropped == 0 {
                        warn!("[pw] hi-res FIFO full (source not being read); dropping audio");
                    }
                    self.dropped += chunk.len() as u64;
                }
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
                Err(e) => {
                    error!("hi-res fifo write broke: {}", e);
                    return Err(());
                }
            }
        }
        Ok(peak)
    }
}

/// Streams below this rate are level meters (pavucontrol and KDE's volume
/// applet open 25 Hz peak-detect streams to draw mic levels), not recorders.
const MIN_RECORDER_RATE: u32 = 1000;

/// Who records from the virtual source.
pub enum SourceUse {
    /// The source doesn't exist (e.g. pipewire-pulse restarted and dropped it).
    Missing,
    /// It exists; Some(app) if a real recorder is attached.
    Present(Option<String>),
}

// Name of the application recording from the virtual source, or None if idle.
pub fn source_consumer(name: &str) -> SourceUse {
    let Some((mut mainloop, context)) = connect() else {
        // Can't tell; don't report it missing (that would recreate it).
        return SourceUse::Present(None);
    };
    let introspect = context.introspect();

    let index = Rc::new(Cell::new(u32::MAX));
    let op = introspect.get_source_info_by_name(name, {
        let index = index.clone();
        move |result| {
            if let ListResult::Item(item) = result {
                index.set(item.index);
            }
        }
    });
    if !wait_op(&mut mainloop, &op) {
        // Can't tell; don't report it missing (that would recreate it).
        return SourceUse::Present(None);
    }

    let app = Rc::new(RefCell::new(None::<String>));
    let idx = index.get();
    if idx != u32::MAX {
        let op = introspect.get_source_output_info_list({
            let app = app.clone();
            move |result| {
                if let ListResult::Item(item) = result {
                    if item.source == idx
                        && item.sample_spec.rate >= MIN_RECORDER_RATE
                        && app.borrow().is_none()
                    {
                        let label = item
                            .proplist
                            .get_str("application.name")
                            .or_else(|| item.name.as_ref().map(|n| n.to_string()));
                        app.replace(label);
                    }
                }
            }
        });
        wait_op(&mut mainloop, &op);
    }
    mainloop.quit(Retval(0));

    if idx == u32::MAX {
        return SourceUse::Missing;
    }
    let result = app.borrow().clone();
    SourceUse::Present(result)
}

// A2DP transport reset:
// After a 0x58 mic STOP the AirPods keep playing A2DP audio from one earbud only
// until the transport is re-established. A sink suspend/resume does that without
// removing the sink; cycling the card profile is the fallback.
pub fn reset_a2dp(bdaddr: &str) {
    if !crate::utils::AppSettings::load().a2dp_reset {
        return;
    }
    let card = format!("bluez_card.{}", bdaddr.replace(':', "_"));

    // Wait for an A2DP profile if the card is still being (re)activated (e.g. a
    // bud just went back in); resetting a card that is off does nothing.
    if !crate::audio::card::wait_for_a2dp(&card, A2DP_RESET_WAIT) {
        return;
    }
    // Suspend/resume first: the sink stays, so the default output doesn't change
    // and players aren't interrupted.
    if crate::audio::card::suspend_resume(&card) {
        return;
    }
    warn!("[pw] falling back to a card profile cycle for {}", card);
    // Cycling the card profile pauses MPRIS players; remember who was playing
    // (after the wait, so media resumed meanwhile is included).
    let players = playing_media_players();
    if crate::audio::card::cycle_a2dp(&card) {
        resume_media_players(&players);
    }
}

/// How long a reset waits for the card to reach an A2DP profile.
const A2DP_RESET_WAIT: Duration = Duration::from_secs(5);

// MPRIS players currently reporting "Playing" (kdeconnect proxies excluded).
fn playing_media_players() -> Vec<String> {
    let Ok(conn) = Connection::new_session() else {
        return Vec::new();
    };
    let proxy = conn.with_proxy(
        "org.freedesktop.DBus",
        "/org/freedesktop/DBus",
        Duration::from_secs(5),
    );
    let names: (Vec<String>,) = match proxy.method_call("org.freedesktop.DBus", "ListNames", ()) {
        Ok(n) => n,
        Err(_) => return Vec::new(),
    };
    names
        .0
        .into_iter()
        .filter(|s| {
            s.starts_with("org.mpris.MediaPlayer2.")
                && !s.starts_with("org.mpris.MediaPlayer2.kdeconnect.mpris_")
        })
        .filter(|s| {
            let proxy = conn.with_proxy(s, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
            proxy
                .get::<String>("org.mpris.MediaPlayer2.Player", "PlaybackStatus")
                .map(|st| st == "Playing")
                .unwrap_or(false)
        })
        .collect()
}

fn resume_media_players(services: &[String]) {
    if services.is_empty() {
        return;
    }
    let Ok(conn) = Connection::new_session() else {
        return;
    };
    for service in services {
        let proxy = conn.with_proxy(service, "/org/mpris/MediaPlayer2", Duration::from_secs(5));
        if proxy
            .method_call::<(), _, &str, &str>("org.mpris.MediaPlayer2.Player", "Play", ())
            .is_ok()
        {
            info!("[pw] resumed media player after A2DP reset: {}", service);
        }
    }
}

/// Upper bound for connecting to the sound server and for each request to it.
/// A hung pipewire-pulse must not hang the caller (the hi-res monitor, and
/// through it disconnect handling) forever.
const PULSE_TIMEOUT: Duration = Duration::from_secs(5);

/// Run the mainloop once without blocking; if nothing was dispatched, sleep
/// briefly instead of spinning. Returns false if the mainloop quit or failed.
fn pump(mainloop: &mut Mainloop) -> bool {
    match mainloop.iterate(false) {
        IterateResult::Quit(_) | IterateResult::Err(_) => false,
        IterateResult::Success(0) => {
            std::thread::sleep(Duration::from_millis(1));
            true
        }
        IterateResult::Success(_) => true,
    }
}

pub(crate) fn connect() -> Option<(Mainloop, Context)> {
    let mut mainloop = Mainloop::new()?;
    let mut context = Context::new(&mainloop, "LibrePods")?;
    context
        .connect(None, ContextFlagSet::NOAUTOSPAWN, None)
        .ok()?;
    let deadline = Instant::now() + PULSE_TIMEOUT;
    loop {
        if !pump(&mut mainloop) {
            return None;
        }
        match context.get_state() {
            libpulse_binding::context::State::Ready => break,
            libpulse_binding::context::State::Failed
            | libpulse_binding::context::State::Terminated => return None,
            _ => {}
        }
        if Instant::now() >= deadline {
            warn!("[pw] timed out connecting to the sound server");
            return None;
        }
    }
    Some((mainloop, context))
}

/// Wait for a request to the sound server to finish. Returns false if it
/// didn't complete (timed out, or the connection went away); the callers'
/// result cells then still hold their "nothing found / failed" defaults.
pub(crate) fn wait_op<T: ?Sized>(mainloop: &mut Mainloop, op: &Operation<T>) -> bool {
    let deadline = Instant::now() + PULSE_TIMEOUT;
    while op.get_state() == OperationState::Running {
        if !pump(mainloop) {
            return false;
        }
        if Instant::now() >= deadline {
            warn!("[pw] a request to the sound server timed out");
            return false;
        }
    }
    op.get_state() == OperationState::Done
}

/// Loaded modules that create our virtual mic: (index, argument).
fn our_modules() -> Vec<(u32, String)> {
    let Some((mut mainloop, context)) = connect() else {
        return Vec::new();
    };
    let found: Rc<RefCell<Vec<(u32, String)>>> = Rc::new(RefCell::new(Vec::new()));
    let introspect = context.introspect();
    let op = introspect.get_module_info_list({
        let found = found.clone();
        move |result| {
            if let ListResult::Item(item) = result
                && let Some(arg) = &item.argument
                && arg.contains(&format!("source_name={SOURCE_NAME} "))
            {
                found.borrow_mut().push((item.index, arg.to_string()));
            }
        }
    });
    wait_op(&mut mainloop, &op);
    drop(op);
    mainloop.quit(Retval(0));
    let result = found.borrow().clone();
    result
}

fn unload_stale_modules() {
    for (index, _) in our_modules() {
        warn!("[pw] unloading stale hi-res module {}", index);
        unload_module(index);
    }
}

/// Index of a source by name, or None if it doesn't exist.
fn source_index(name: &str) -> Option<u32> {
    let (mut mainloop, context) = connect()?;
    let index: Rc<Cell<Option<u32>>> = Rc::new(Cell::new(None));
    let op = context.introspect().get_source_info_by_name(name, {
        let index = index.clone();
        move |result| {
            if let ListResult::Item(item) = result {
                index.set(Some(item.index));
            }
        }
    });
    wait_op(&mut mainloop, &op);
    drop(op);
    mainloop.quit(Retval(0));
    index.get()
}

/// A recording stream (source-output). The app name is kept with the index so
/// a reused index isn't mistaken for it later.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Recorder {
    pub index: u32,
    pub app: Option<String>,
}

/// All real recorders (level meters excluded) with the index of their source.
fn list_recorders() -> Vec<(Recorder, u32)> {
    let Some((mut mainloop, context)) = connect() else {
        return Vec::new();
    };
    let found: Rc<RefCell<Vec<(Recorder, u32)>>> = Rc::new(RefCell::new(Vec::new()));
    let op = context.introspect().get_source_output_info_list({
        let found = found.clone();
        move |result| {
            if let ListResult::Item(item) = result
                && item.sample_spec.rate >= MIN_RECORDER_RATE
            {
                let app = item
                    .proplist
                    .get_str("application.name")
                    .or_else(|| item.name.as_ref().map(|n| n.to_string()));
                found.borrow_mut().push((
                    Recorder {
                        index: item.index,
                        app,
                    },
                    item.source,
                ));
            }
        }
    });
    wait_op(&mut mainloop, &op);
    drop(op);
    mainloop.quit(Retval(0));
    let result = found.borrow().clone();
    result
}

/// Real recorders on a source (level meters excluded).
pub fn recorders_on(source: &str) -> Vec<Recorder> {
    let Some(index) = source_index(source) else {
        return Vec::new();
    };
    list_recorders()
        .into_iter()
        .filter(|(_, on)| *on == index)
        .map(|(r, _)| r)
        .collect()
}

/// True if any app records from any microphone.
pub fn any_recorder() -> bool {
    !list_recorders().is_empty()
}

/// Name of the current default source.
pub fn default_source() -> Option<String> {
    let (mut mainloop, context) = connect()?;
    let current: Rc<RefCell<Option<String>>> = Rc::new(RefCell::new(None));
    let op = context.introspect().get_server_info({
        let current = current.clone();
        move |info| {
            *current.borrow_mut() = info.default_source_name.as_ref().map(|n| n.to_string());
        }
    });
    wait_op(&mut mainloop, &op);
    drop(op);
    mainloop.quit(Retval(0));
    let result = current.borrow().clone();
    result
}

/// Make `name` the default source. Streams that follow the default are moved
/// to it by the session manager, the same as when the user picks a microphone
/// in the desktop's sound settings.
pub fn set_default_source(name: &str) -> bool {
    let Some((mut mainloop, mut context)) = connect() else {
        return false;
    };
    let ok = Rc::new(Cell::new(false));
    let op = context.set_default_source(name, {
        let ok = ok.clone();
        move |success| ok.set(success)
    });
    wait_op(&mut mainloop, &op);
    drop(op);
    mainloop.quit(Retval(0));
    ok.get()
}

/// The microphone to send apps to while ours can't deliver audio: the real
/// (non-monitor) source with the highest session priority other than `exclude`,
/// i.e. the one the session manager would fall back to.
pub fn fallback_source(exclude: &str) -> Option<String> {
    let (mut mainloop, context) = connect()?;
    let best: Rc<RefCell<Option<(i64, String)>>> = Rc::new(RefCell::new(None));
    let op = context.introspect().get_source_info_list({
        let best = best.clone();
        let exclude = exclude.to_string();
        move |result| {
            if let ListResult::Item(item) = result
                && item.monitor_of_sink.is_none()
                && let Some(name) = item.name.as_ref()
                && *name != *exclude
            {
                let priority = item
                    .proplist
                    .get_str("priority.session")
                    .and_then(|p| p.parse::<i64>().ok())
                    .unwrap_or(0);
                let mut best = best.borrow_mut();
                if best.as_ref().is_none_or(|(p, _)| priority > *p) {
                    *best = Some((priority, name.to_string()));
                }
            }
        }
    });
    wait_op(&mut mainloop, &op);
    drop(op);
    mainloop.quit(Retval(0));
    let result = best.borrow().clone().map(|(_, name)| name);
    result
}

/// Move recorders that still exist (same index *and* app) and aren't on
/// `target` yet to the source `target`. Returns the ones moved.
pub fn move_recorders(recorders: &[Recorder], target: &str) -> Vec<Recorder> {
    if recorders.is_empty() {
        return Vec::new();
    }
    let Some(target_index) = source_index(target) else {
        return Vec::new();
    };
    let current = list_recorders();
    let Some((mut mainloop, context)) = connect() else {
        return Vec::new();
    };
    let mut introspect = context.introspect();
    let mut moved = Vec::new();
    for rec in recorders {
        if !current.iter().any(|(r, on)| r == rec && *on != target_index) {
            continue;
        }
        let ok = Rc::new(Cell::new(false));
        let cb: Box<dyn FnMut(bool) + 'static> = {
            let ok = ok.clone();
            Box::new(move |success| ok.set(success))
        };
        let op = introspect.move_source_output_by_name(rec.index, target, Some(cb));
        wait_op(&mut mainloop, &op);
        if ok.get() {
            info!("[pw] moved recorder {:?} to {}", rec.app, target);
            moved.push(rec.clone());
        } else {
            warn!("[pw] could not move recorder {:?} to {}", rec.app, target);
        }
    }
    mainloop.quit(Retval(0));
    moved
}

fn load_module(name: &str, args: &str) -> Option<u32> {
    let (mut mainloop, mut context) = connect()?;
    let idx: Rc<Cell<u32>> = Rc::new(Cell::new(u32::MAX));
    let mut introspect = context.introspect();
    let op = introspect.load_module(name, args, {
        let idx = idx.clone();
        move |index| idx.set(index)
    });
    wait_op(&mut mainloop, &op);
    mainloop.quit(Retval(0));

    match idx.get() {
        u32::MAX => None,
        i => Some(i),
    }
}

fn unload_module(index: u32) {
    if index == u32::MAX {
        return;
    }
    if let Some((mut mainloop, mut context)) = connect() {
        let mut introspect = context.introspect();
        let op = introspect.unload_module(index, |_| {});
        wait_op(&mut mainloop, &op);
        mainloop.quit(Retval(0));
    }
}
