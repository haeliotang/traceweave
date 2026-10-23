use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

static SHUTDOWN_REQUESTED: AtomicBool = AtomicBool::new(false);

extern "C" fn handle_posix_signal(_sig: std::os::raw::c_int) {
    SHUTDOWN_REQUESTED.store(true, Ordering::SeqCst);
}

fn print_usage(prog: &str) {
    println!("Personal Agent Substrate Native Daemon (agentosd) v0.2.0");
    println!("Usage: {} [OPTIONS]", prog);
    println!();
    println!("Options:");
    println!("  -p, --port <PORT>          TCP loopback listen port (default: 19898)");
    println!("  -u, --socket <PATH>        Unix domain socket path (default: /data/local/tmp/agentosd.sock)");
    println!("  -s, --store-path <PATH>    Path to persistent SQLite store (default: platform standard)");
    println!("  -h, --help                 Show this help message");
    println!("  -v, --version              Show daemon version");
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = std::env::args().collect();
    let prog = if !args.is_empty() { &args[0] } else { "agentosd" };

    let mut port = std::env::var("AGENTOSD_PORT")
        .ok()
        .and_then(|p| p.parse::<u16>().ok())
        .unwrap_or(agent_os_core::daemon::DEFAULT_TCP_PORT);

    let mut socket_path = std::env::var("AGENTOSD_SOCKET")
        .unwrap_or_else(|_| agent_os_core::daemon::DEFAULT_SOCKET_PATH.to_string());

    let mut store_path: Option<String> = std::env::var("AGENTOSD_STORE_PATH").ok();

    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "-p" | "--port" => {
                if i + 1 < args.len() {
                    if let Ok(parsed) = args[i + 1].parse::<u16>() {
                        port = parsed;
                    }
                    i += 1;
                }
            }
            "-u" | "--socket" => {
                if i + 1 < args.len() {
                    socket_path = args[i + 1].clone();
                    i += 1;
                }
            }
            "-s" | "--store-path" => {
                if i + 1 < args.len() {
                    store_path = Some(args[i + 1].clone());
                    i += 1;
                }
            }
            "-h" | "--help" => {
                print_usage(prog);
                return Ok(());
            }
            "-v" | "--version" => {
                println!("agentosd 0.2.0-substrate");
                return Ok(());
            }
            _ => {
                eprintln!("[agentosd] Unknown argument: {}", args[i]);
            }
        }
        i += 1;
    }

    if let Some(ref sp) = store_path {
        if let Some(parent) = std::path::Path::new(sp).parent() {
            if !parent.as_os_str().is_empty() {
                let _ = std::fs::create_dir_all(parent);
            }
        }
        if let Err(e) = agent_os_core::store::init_global_store(sp) {
            eprintln!("[agentosd] Failed to initialize global store at {}: {:?}", sp, e);
        } else {
            println!("[agentosd] Initialized global store at: {}", sp);
        }
    }

    println!("[agentosd] ========================================");
    println!("[agentosd] Starting Agent OS System Daemon (agentosd)");
    println!("[agentosd] Personal Agent Substrate Core");
    println!("[agentosd] TCP Loopback Port: 127.0.0.1:{}", port);
    println!("[agentosd] UDS Socket: {}", socket_path);
    if let Some(ref sp) = store_path {
        println!("[agentosd] Store Path: {}", sp);
    }
    println!("[agentosd] PID: {}", std::process::id());
    println!("[agentosd] Architecture: {}", std::env::consts::ARCH);
    println!("[agentosd] ========================================");

    let running = Arc::new(AtomicBool::new(true));

    #[cfg(unix)]
    {
        extern "C" {
            fn signal(sig: std::os::raw::c_int, handler: extern "C" fn(std::os::raw::c_int)) -> usize;
        }
        unsafe {
            signal(2, handle_posix_signal);  // SIGINT
            signal(15, handle_posix_signal); // SIGTERM
            signal(1, handle_posix_signal);  // SIGHUP
        }

        let r_clone = running.clone();
        std::thread::spawn(move || {
            while !SHUTDOWN_REQUESTED.load(Ordering::SeqCst) {
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
            println!("[agentosd] Termination signal caught, commencing graceful shutdown...");
            r_clone.store(false, Ordering::SeqCst);
        });
    }

    // Run dual daemon (TCP loopback + UDS)
    let res = agent_os_core::daemon::run_daemon_dual(port, Some(&socket_path), running);
    if let Err(e) = res {
        eprintln!("[agentosd] Daemon loop exited with error: {:?}", e);
        return Err(Box::new(e));
    }

    println!("[agentosd] Shutdown complete.");
    Ok(())
}
