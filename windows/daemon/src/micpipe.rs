//! Writer for the LibrePodsMic virtual microphone: pushes decoded PCM into the
//! driver's ring buffer over the control device `\\.\LibrePodsMic`.

use std::ffi::c_void;
use std::ptr;
use std::sync::Mutex;

use windows_sys::Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE};
use windows_sys::Win32::Storage::FileSystem::{
    CreateFileW, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_EXISTING,
};
use windows_sys::Win32::System::IO::DeviceIoControl;

const GENERIC_READ: u32 = 0x8000_0000;
const GENERIC_WRITE: u32 = 0x4000_0000;
// CTL_CODE(FILE_DEVICE_UNKNOWN, 0x800, METHOD_BUFFERED, FILE_WRITE_DATA)
const IOCTL_LIBREPODS_MIC_WRITE_PCM: u32 = 0x0022_A000;
// CTL_CODE(FILE_DEVICE_UNKNOWN, 0x801, METHOD_BUFFERED, FILE_READ_DATA)
const IOCTL_LIBREPODS_MIC_STATUS: u32 = 0x0022_6004;

pub struct MicPipe {
    handle: HANDLE,
}

// One tray owns the single exclusive handle and uses it from the receive thread
// (writes) and the status-poll thread (reads); both DeviceIoControl paths are
// independent, so sharing the handle is safe.
unsafe impl Send for MicPipe {}
unsafe impl Sync for MicPipe {}

impl MicPipe {
    /// Open the virtual-mic control device. None if the LibrePodsMic driver
    /// isn't installed (or another writer holds the exclusive handle).
    pub fn open() -> Option<MicPipe> {
        let path: Vec<u16> = r"\\.\LibrePodsMic"
            .encode_utf16()
            .chain(std::iter::once(0))
            .collect();
        let handle = unsafe {
            CreateFileW(
                path.as_ptr(),
                GENERIC_READ | GENERIC_WRITE,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                ptr::null(),
                OPEN_EXISTING,
                0,
                ptr::null_mut(),
            )
        };
        if handle == INVALID_HANDLE_VALUE || handle.is_null() {
            None
        } else {
            Some(MicPipe { handle })
        }
    }

    /// Capture-activity counter — advances while an app is recording from the virtual
    /// mic. `None` = the DeviceIoControl failed (handle dead / device gone), so the
    /// caller can reopen.
    pub fn status(&self) -> Option<i32> {
        let mut out = [0u8; 4];
        let mut returned = 0u32;
        let ok = unsafe {
            DeviceIoControl(
                self.handle,
                IOCTL_LIBREPODS_MIC_STATUS,
                ptr::null(),
                0,
                out.as_mut_ptr() as *mut c_void,
                4,
                &mut returned,
                ptr::null_mut(),
            )
        };
        if ok != 0 {
            Some(i32::from_le_bytes(out))
        } else {
            None
        }
    }

    /// Push mono 16-bit PCM samples into the mic ring. Returns false if the write
    /// failed (handle dead) so the caller can reopen.
    pub fn write(&self, samples: &[i16]) -> bool {
        if samples.is_empty() {
            return true;
        }
        let bytes = std::mem::size_of_val(samples);
        let mut returned = 0u32;
        let ok = unsafe {
            DeviceIoControl(
                self.handle,
                IOCTL_LIBREPODS_MIC_WRITE_PCM,
                samples.as_ptr() as *const c_void,
                bytes as u32,
                ptr::null_mut(),
                0,
                &mut returned,
                ptr::null_mut(),
            )
        };
        ok != 0
    }
}

/// Self-healing holder for the virtual-mic pipe. The device may not be enumerated yet
/// when the daemon starts at boot, and the handle can later break (mic driver
/// reinstalled / device re-plugged) — either used to leave the mic dead until a daemon
/// restart. All mic access goes through this cell, which (re)opens the pipe on demand
/// so audio + mic recover on their own. Cheap Mutex; the write path is uncontended.
pub struct MicPipeCell {
    inner: Mutex<Option<MicPipe>>,
}

impl MicPipeCell {
    /// Try to open once up front; a failure here is fine — it reopens on first use.
    pub fn new() -> MicPipeCell {
        MicPipeCell {
            inner: Mutex::new(MicPipe::open()),
        }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Option<MicPipe>> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Whether a pipe is currently open (reopening if it was closed).
    pub fn is_open(&self) -> bool {
        let mut g = self.lock();
        if g.is_none() {
            *g = MicPipe::open();
        }
        g.is_some()
    }

    /// Push PCM; reopen + retry once if the handle broke.
    pub fn write(&self, samples: &[i16]) {
        let mut g = self.lock();
        if g.is_none() {
            *g = MicPipe::open();
        }
        if let Some(p) = g.as_ref() {
            if !p.write(samples) {
                *g = MicPipe::open(); // handle broke — reopen and retry once
                if let Some(p2) = g.as_ref() {
                    let _ = p2.write(samples);
                }
            }
        }
    }

    /// Capture-activity counter (0 when unavailable); a dead handle is dropped so the
    /// next call reopens it.
    pub fn status(&self) -> i32 {
        let mut g = self.lock();
        if g.is_none() {
            *g = MicPipe::open();
        }
        match g.as_ref().and_then(|p| p.status()) {
            Some(v) => v,
            None => {
                *g = None;
                0
            }
        }
    }
}

impl Drop for MicPipe {
    fn drop(&mut self) {
        unsafe { CloseHandle(self.handle) };
    }
}
