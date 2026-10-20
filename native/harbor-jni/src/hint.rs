//! An Android performance hint session (ADPF) for the engine thread. The engine works in short
//! bursts between sleeps, which the CPU governor reads as light load: it can leave the thread on a
//! small core at a low clock until the output starves. Reporting how long each slice of audio
//! took against a target lets the system raise the clock or move the thread before that.
//!
//! `APerformanceHint_*` is API 33+, and Harbor's minimum is 31, so the functions are looked up at
//! run time; without them (or without a session) the engine runs as before.

use std::time::Duration;

#[cfg(target_os = "android")]
use harbor_engine::engine::REPORT_EVERY as WINDOW;

/// The share of real time the engine may spend emulating before the system is asked for more.
#[cfg(target_os = "android")]
const TARGET_LOAD: f64 = 0.6;

#[cfg(target_os = "android")]
mod sys {
    use std::ffi::{c_void, CStr};

    pub type GetManager = unsafe extern "C" fn() -> *mut c_void;
    pub type CreateSession = unsafe extern "C" fn(*mut c_void, *const i32, usize, i64) -> *mut c_void;
    pub type Report = unsafe extern "C" fn(*mut c_void, i64) -> i32;
    pub type Close = unsafe extern "C" fn(*mut c_void);

    pub struct Api {
        pub get_manager: GetManager,
        pub create: CreateSession,
        pub report: Report,
        pub close: Close,
    }

    unsafe fn sym<T>(lib: *mut c_void, name: &CStr) -> Option<T> {
        let p = libc::dlsym(lib, name.as_ptr());
        // SAFETY: the caller names the C signature T that `name` has in the NDK headers.
        (!p.is_null()).then(|| std::mem::transmute_copy(&p))
    }

    pub fn load() -> Option<Api> {
        // SAFETY: dlopen/dlsym on the system's libandroid; the pointers are typed per the NDK.
        unsafe {
            let lib = libc::dlopen(c"libandroid.so".as_ptr(), libc::RTLD_NOW);
            if lib.is_null() {
                return None;
            }
            Some(Api {
                get_manager: sym(lib, c"APerformanceHint_getManager")?,
                create: sym(lib, c"APerformanceHint_createSession")?,
                report: sym(lib, c"APerformanceHint_reportActualWorkDuration")?,
                close: sym(lib, c"APerformanceHint_closeSession")?,
            })
        }
    }
}

/// A session for the calling thread: create it on the engine thread.
pub struct Hint {
    #[cfg(target_os = "android")]
    api: sys::Api,
    #[cfg(target_os = "android")]
    session: *mut std::ffi::c_void,
}

impl Hint {
    pub fn for_this_thread() -> Option<Hint> {
        #[cfg(target_os = "android")]
        {
            let api = sys::load()?;
            let target = (WINDOW.as_nanos() as f64 * TARGET_LOAD) as i64;
            // SAFETY: NDK calls with a valid tid array; a null session means no ADPF here.
            unsafe {
                let manager = (api.get_manager)();
                if manager.is_null() {
                    return None;
                }
                let tid = libc::gettid();
                let session = (api.create)(manager, &tid, 1, target);
                if session.is_null() {
                    log::warn!("engine thread: no performance hint session");
                    return None;
                }
                log::info!("engine thread: performance hint session, target {} us per {} ms", target / 1000, WINDOW.as_millis());
                Some(Hint { api, session })
            }
        }
        #[cfg(not(target_os = "android"))]
        None
    }

    /// The engine spent `busy` making `audio` worth of output.
    pub fn report(&mut self, busy: Duration, audio: Duration) {
        #[cfg(target_os = "android")]
        {
            if audio.is_zero() {
                return;
            }
            // scaled to exactly one window, so the target stays fixed
            let actual = busy.as_nanos() as f64 * WINDOW.as_nanos() as f64 / audio.as_nanos() as f64;
            // SAFETY: the session is live until drop.
            unsafe { (self.api.report)(self.session, actual.max(1.0) as i64) };
        }
        #[cfg(not(target_os = "android"))]
        let _ = (busy, audio);
    }
}

impl Drop for Hint {
    fn drop(&mut self) {
        #[cfg(target_os = "android")]
        // SAFETY: closed once, here.
        unsafe {
            (self.api.close)(self.session)
        };
    }
}
