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
const SOURCE_NAME: &str = "AirPodsHiRes";

pub struct Output {
    simple: Option<Simple>,
    agc: Agc,
    sink_module: u32,
    source_module: u32,
}

unsafe impl Send for Output {}

impl Output {
    // Create the virtual mic (null-sink + remap-source) and open the playback
    // stream into it. Returns None on failure.
    pub fn open(sample_rate: u32, channels: u8) -> Option<Output> {
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

        let spec = Spec {
            format: Format::S16le,
            channels,
            rate: sample_rate,
        };
        if !spec.is_valid() {
            error!("invalid sample spec: {} Hz, {} ch", sample_rate, channels);
            unload_module(source_module);
            unload_module(sink_module);
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
                unload_module(source_module);
                unload_module(sink_module);
                return None;
            }
        };

        info!(
            "[pw] hi-res mic ready: select '{}' as your microphone",
            SOURCE_NAME
        );
        Some(Output {
            simple: Some(simple),
            agc: Agc::new(),
            sink_module,
            source_module,
        })
    }

    // Write interleaved s16 PCM into the sink
    pub fn write(&mut self, pcm: &[i16]) -> Result<(), ()> {
        let mut pcm = pcm.to_vec();

        self.agc.process(&mut pcm);

        let bytes = unsafe {
            std::slice::from_raw_parts(
                pcm.as_ptr() as *const u8,
                std::mem::size_of_val(pcm.as_slice()),
            )
        };

        match &self.simple {
            Some(s) => s.write(bytes).map_err(|e| {
                error!("hi-res playback stream broke: {}", e);
            }),
            None => Err(()),
        }
    }
}

impl Drop for Output {
    fn drop(&mut self) {
        self.simple.take();
        unload_module(self.source_module);
        unload_module(self.sink_module);
    }
}

// A2DP transport reset:
// We found that in some cases A2DP has to be suspended and resumed after a 0x58 mic start/stop
// to avoid a corrupted transport state of the airpods.
pub fn reset_a2dp(bdaddr: &str) {
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
