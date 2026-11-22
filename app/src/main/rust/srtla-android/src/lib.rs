use std::collections::HashMap;
use std::ffi::{CStr, c_char, c_int};
use std::fs;
use std::io;
use std::net::{IpAddr, Ipv4Addr};
use std::path::PathBuf;
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

use srtla_core::priority::CriticalWindow;
use srtla_send::config::DynamicConfig;
use srtla_send::net::{CallbackBinder, UplinkBinder};
use srtla_send::stats::SharedStats;
use srtla_send::subscriptions::SubscriptionHub;
use srtla_send::sender::run_sender_with_config;
use tokio::sync::oneshot;

unsafe extern "C" {
    fn android_setsocknetwork(network: u64, socket: c_int) -> c_int;
}

struct SrtlaSession {
    stats: SharedStats,
    finished: Arc<AtomicBool>,
    error: Arc<Mutex<Option<String>>>,
    stop: Mutex<Option<oneshot::Sender<()>>>,
    thread: Mutex<Option<JoinHandle<()>>>,
    uplinks_file: PathBuf,
}

fn read_string(value: *const c_char) -> Result<String, String> {
    if value.is_null() {
        return Err("Missing required SRTLA configuration".to_string());
    }
    let value = unsafe { CStr::from_ptr(value) };
    value
        .to_str()
        .map(str::to_owned)
        .map_err(|error| format!("Invalid UTF-8 in SRTLA configuration: {error}"))
}

fn error_text(session: &SrtlaSession) -> Option<String> {
    session.error.lock().ok().and_then(|error| error.clone())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn bigirl_srtla_start(
    receiver_host: *const c_char,
    receiver_port: u16,
    local_srt_port: u16,
    wifi_ip: *const c_char,
    wifi_network_handle: u64,
    cellular_ip: *const c_char,
    cellular_network_handle: u64,
    cache_directory: *const c_char,
) -> *mut SrtlaSession {
    let (receiver_host, wifi_ip, cellular_ip, cache_directory) = match (
        read_string(receiver_host),
        read_string(wifi_ip),
        read_string(cellular_ip),
        read_string(cache_directory),
    ) {
        (Ok(host), Ok(wifi), Ok(cellular), Ok(cache)) => (host, wifi, cellular, cache),
        (Err(error), _, _, _)
        | (_, Err(error), _, _)
        | (_, _, Err(error), _)
        | (_, _, _, Err(error)) => {
            eprintln!("SRTLA configuration error: {error}");
            return ptr::null_mut();
        }
    };

    if receiver_port == 0 || local_srt_port == 0 {
        eprintln!("SRTLA receiver and local ports must be non-zero");
        return ptr::null_mut();
    }

    let mut uplinks = HashMap::<IpAddr, u64>::new();
    for (address, handle) in [
        (wifi_ip, wifi_network_handle),
        (cellular_ip, cellular_network_handle),
    ] {
        if address.is_empty() && handle == 0 {
            continue;
        }
        let address = match address.parse::<IpAddr>() {
            Ok(address) if handle != 0 => address,
            _ => {
                eprintln!("Invalid SRTLA uplink address or Android network handle");
                return ptr::null_mut();
            }
        };
        if uplinks.insert(address, handle).is_some() {
            eprintln!("SRTLA uplink addresses must be unique");
            return ptr::null_mut();
        }
    }
    if uplinks.is_empty() {
        eprintln!("SRTLA requires at least one Android network path");
        return ptr::null_mut();
    }

    let cache_directory = PathBuf::from(cache_directory);
    let uplinks_file = cache_directory.join(format!(
        "srtla-uplinks-{}-{local_srt_port}.txt",
        std::process::id()
    ));
    let uplink_text = uplinks
        .keys()
        .map(ToString::to_string)
        .collect::<Vec<_>>()
        .join("\n");
    if let Err(error) = fs::write(&uplinks_file, format!("{uplink_text}\n")) {
        eprintln!("Could not write SRTLA uplink configuration: {error}");
        return ptr::null_mut();
    }

    let binder = Arc::new(CallbackBinder(move |socket, address| {
        let network = uplinks.get(&address).copied().ok_or_else(|| {
            io::Error::new(io::ErrorKind::NotFound, "No Android network for SRTLA uplink")
        })?;
        let result = unsafe { android_setsocknetwork(network, socket) };
        if result == 0 {
            Ok(())
        } else {
            Err(io::Error::last_os_error())
        }
    }));
    let binder: Arc<dyn UplinkBinder> = binder;
    let stats = SharedStats::new();
    let task_stats = stats.clone();
    let finished = Arc::new(AtomicBool::new(false));
    let task_finished = finished.clone();
    let error = Arc::new(Mutex::new(None));
    let task_error = error.clone();
    let (stop_tx, stop_rx) = oneshot::channel();
    let host = receiver_host.clone();
    let uplinks_path = uplinks_file.to_string_lossy().into_owned();

    let thread = thread::Builder::new()
        .name("bigirl-srtla".to_string())
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(error) => {
                    if let Ok(mut stored_error) = task_error.lock() {
                        *stored_error = Some(format!("Could not create SRTLA runtime: {error}"));
                    }
                    task_finished.store(true, Ordering::Release);
                    return;
                }
            };

            let result = runtime.block_on(async move {
                tokio::select! {
                    result = run_sender_with_config(
                        local_srt_port,
                        IpAddr::V4(Ipv4Addr::LOCALHOST),
                        &host,
                        receiver_port,
                        &uplinks_path,
                        DynamicConfig::new(),
                        task_stats,
                        CriticalWindow::new(),
                        SubscriptionHub::new(),
                        binder,
                    ) => result,
                    _ = stop_rx => Ok(()),
                }
            });
            if let Err(error) = result {
                if let Ok(mut stored_error) = task_error.lock() {
                    *stored_error = Some(error.to_string());
                }
            }
            task_finished.store(true, Ordering::Release);
        });

    let thread = match thread {
        Ok(thread) => thread,
        Err(error) => {
            let _ = fs::remove_file(&uplinks_file);
            eprintln!("Could not start SRTLA runtime thread: {error}");
            return ptr::null_mut();
        }
    };

    Box::into_raw(Box::new(SrtlaSession {
        stats,
        finished,
        error,
        stop: Mutex::new(Some(stop_tx)),
        thread: Mutex::new(Some(thread)),
        uplinks_file,
    }))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn bigirl_srtla_active_links(session: *const SrtlaSession) -> c_int {
    let Some(session) = (unsafe { session.as_ref() }) else {
        return -1;
    };
    let active_links = session.stats.get().active_links as c_int;
    if active_links == 0 && session.finished.load(Ordering::Acquire) {
        -1
    } else {
        active_links
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn bigirl_srtla_error(
    session: *const SrtlaSession,
    output: *mut c_char,
    capacity: usize,
) -> usize {
    let Some(session) = (unsafe { session.as_ref() }) else {
        return 0;
    };
    let Some(error) = error_text(session) else {
        return 0;
    };
    let bytes = error.as_bytes();
    if output.is_null() || capacity == 0 {
        return bytes.len();
    }
    let copied = bytes.len().min(capacity - 1);
    unsafe {
        ptr::copy_nonoverlapping(bytes.as_ptr(), output.cast::<u8>(), copied);
        *output.add(copied) = 0;
    }
    bytes.len()
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn bigirl_srtla_stop(session: *mut SrtlaSession) {
    if session.is_null() {
        return;
    }
    let session = unsafe { Box::from_raw(session) };
    if let Ok(mut stop) = session.stop.lock()
        && let Some(stop) = stop.take()
    {
        let _ = stop.send(());
    }
    if let Ok(mut thread) = session.thread.lock()
        && let Some(thread) = thread.take()
    {
        let _ = thread.join();
    }
    let _ = fs::remove_file(&session.uplinks_file);
}
