//! PipeWire/PulseAudio output for the hi-res microphone: a private null-sink
//! fed by a playback stream, exposed as a real input via a remap-source

use libpulse_binding::callbacks::ListResult;
use libpulse_binding::context::{Context, FlagSet as ContextFlagSet};
use libpulse_binding::def::{BufferAttr, Retval};
use libpulse_binding::mainloop::standard::{IterateResult, Mainloop};
use libpulse_binding::operation::State as OperationState;
use libpulse_binding::sample::{Format, Spec};
use libpulse_binding::stream::Direction;
use libpulse_simple_binding::Simple;
use log::{error, info, warn};
use std::cell::{Cell, RefCell};
use std::rc::Rc;

use crate::audio::agc::Agc;

const SINK_NAME: &str = "AirPodsHiRes_raw";
pub const SOURCE_NAME: &str = "AirPodsHiRes";

pub struct VirtualMic {
    sink_module: u32,
    source_module: u32,
}

unsafe impl Send for VirtualMic {}

impl VirtualMic {
    pub fn open(channels: u8) -> Option<VirtualMic> {
        unload_stale_modules();

        let chan_map = if channels == 1 {
            "mono"
        } else {
            "front-left,front-right"
        };

        let sink_args = format!(
            "sink_name={SINK_NAME} channel_map={chan_map} \
             sink_properties=\"device.description=AirPods_HiRes_Sink node.driver=false priority.driver=0\""
        );
        let sink_module = match load_module("module-null-sink", &sink_args) {
            Some(i) => i,
            None => {
                warn!("could not load module-null-sink");
                return None;
            }
        };

        let source_args = format!(
            "master={SINK_NAME}.monitor source_name={SOURCE_NAME} channel_map={chan_map} \
             source_properties=\"device.description=AirPods_HiRes_Mic node.driver=false priority.driver=0\""
        );
        let source_module = match load_module("module-remap-source", &source_args) {
            Some(i) => i,
            None => {
                warn!("could not load module-remap-source");
                unload_module(sink_module);
                return None;
            }
        };

        info!(
            "[pw] hi-res mic ready: select '{}' as your microphone",
            SOURCE_NAME
        );
        Some(VirtualMic {
            sink_module,
            source_module,
        })
    }
}

impl Drop for VirtualMic {
    fn drop(&mut self) {
        unload_module(self.source_module);
        unload_module(self.sink_module);
    }
}

// Playback stream feeding the null-sink. Opened only while an app is recording.
pub struct Output {
    simple: Simple,
    agc: Option<Agc>,
}

unsafe impl Send for Output {}

impl Output {
    pub fn open(sample_rate: u32, channels: u8) -> Option<Output> {
        let spec = Spec {
            format: Format::S16le,
            channels,
            rate: sample_rate,
        };
        if !spec.is_valid() {
            error!("invalid sample spec: {} Hz, {} ch", sample_rate, channels);
            return None;
        }

        let tlength = (sample_rate * channels as u32 * 2 / 100).max(1);
        let attr = BufferAttr {
            maxlength: u32::MAX,
            tlength,
            prebuf: u32::MAX,
            minreq: u32::MAX,
            fragsize: u32::MAX,
        };

        let simple = match Simple::new(
            None,
            "LibrePods",
            Direction::Playback,
            Some(SINK_NAME),
            "AirPodsHiRes",
            &spec,
            None,
            Some(&attr),
        ) {
            Ok(s) => s,
            Err(e) => {
                error!("could not open hi-res playback stream: {}", e);
                return None;
            }
        };

        let agc = crate::utils::AppSettings::load().hires_mic_agc.then(Agc::new);
        if agc.is_none() {
            info!("[pw] AGC disabled; passing through raw hi-res capture");
        }
        Some(Output { simple, agc })
    }

    // Write s16 PCM into the sink, returning the (post-AGC) peak
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

        self.simple
            .write(bytes)
            .map(|_| peak)
            .map_err(|e| error!("hi-res playback stream broke: {}", e))
    }
}

// Name of the application recording from the virtual source, or None if idle.
pub fn source_consumer(name: &str) -> Option<String> {
    let (mut mainloop, context) = connect()?;
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
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }

    let app = Rc::new(RefCell::new(None::<String>));
    let idx = index.get();
    if idx != u32::MAX {
        let op = introspect.get_source_output_info_list({
            let app = app.clone();
            move |result| {
                if let ListResult::Item(item) = result {
                    if item.source == idx && app.borrow().is_none() {
                        let label = item
                            .proplist
                            .get_str("application.name")
                            .or_else(|| item.name.as_ref().map(|n| n.to_string()));
                        app.replace(label);
                    }
                }
            }
        });
        while op.get_state() == OperationState::Running {
            mainloop.iterate(false);
        }
    }
    mainloop.quit(Retval(0));

    let result = app.borrow().clone();
    result
}

// A2DP transport reset:
// We found that in some cases A2DP has to be suspended and resumed after a 0x58 mic start/stop
// to avoid a corrupted transport state of the airpods.
pub fn reset_a2dp(bdaddr: &str) {
    if !crate::utils::AppSettings::load().a2dp_reset {
        return;
    }
    let sink = format!("bluez_output.{}.1", bdaddr.replace(':', "_"));
    let Some((mut mainloop, mut context)) = connect() else {
        return;
    };
    info!("[pw] suspend/resume {} to reset A2DP transport", sink);
    let mut introspect = context.introspect();

    let op = introspect.suspend_sink_by_name(&sink, true, None);
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    std::thread::sleep(std::time::Duration::from_millis(200));
    let op = introspect.suspend_sink_by_name(&sink, false, None);
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    mainloop.quit(Retval(0));
}

fn connect() -> Option<(Mainloop, Context)> {
    let mut mainloop = Mainloop::new()?;
    let mut context = Context::new(&mainloop, "LibrePods-HiResMic")?;
    context
        .connect(None, ContextFlagSet::NOAUTOSPAWN, None)
        .ok()?;
    loop {
        match mainloop.iterate(false) {
            IterateResult::Quit(_) | IterateResult::Err(_) => return None,
            IterateResult::Success(_) => {}
        }
        match context.get_state() {
            libpulse_binding::context::State::Ready => break,
            libpulse_binding::context::State::Failed
            | libpulse_binding::context::State::Terminated => return None,
            _ => {}
        }
    }
    Some((mainloop, context))
}

fn unload_stale_modules() {
    let Some((mut mainloop, context)) = connect() else {
        return;
    };
    let stale: Rc<RefCell<Vec<u32>>> = Rc::new(RefCell::new(Vec::new()));
    let introspect = context.introspect();
    let op = introspect.get_module_info_list({
        let stale = stale.clone();
        move |result| {
            if let ListResult::Item(item) = result {
                if let Some(arg) = &item.argument {
                    if arg.contains(SINK_NAME) || arg.contains(SOURCE_NAME) {
                        stale.borrow_mut().push(item.index);
                    }
                }
            }
        }
    });
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
    mainloop.quit(Retval(0));

    for index in stale.borrow().iter() {
        warn!("[pw] unloading stale hi-res module {}", index);
        unload_module(*index);
    }
}

fn load_module(name: &str, args: &str) -> Option<u32> {
    let (mut mainloop, mut context) = connect()?;
    let idx: Rc<Cell<u32>> = Rc::new(Cell::new(u32::MAX));
    let mut introspect = context.introspect();
    let op = introspect.load_module(name, args, {
        let idx = idx.clone();
        move |index| idx.set(index)
    });
    while op.get_state() == OperationState::Running {
        mainloop.iterate(false);
    }
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
        while op.get_state() == OperationState::Running {
            mainloop.iterate(false);
        }
        mainloop.quit(Retval(0));
    }
}
