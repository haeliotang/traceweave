use std::sync::atomic::AtomicBool;
use std::sync::Arc;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let port = std::env::var("AGENTOSD_PORT")
        .ok()
        .and_then(|p| p.parse::<u16>().ok())
        .unwrap_or(agent_os_core::daemon::DEFAULT_TCP_PORT);

    let socket_path = std::env::var("AGENTOSD_SOCKET")
        .unwrap_or_else(|_| agent_os_core::daemon::DEFAULT_SOCKET_PATH.to_string());

    println!("[agentosd] ========================================");
    println!("[agentosd] Starting Agent OS System Daemon (agentosd)");
    println!("[agentosd] AOSP Native Daemon Layer for Pixel 6a");
    println!("[agentosd] TCP Loopback Port: 127.0.0.1:{}", port);
    println!("[agentosd] UDS Socket: {}", socket_path);
    println!("[agentosd] PID: {}", std::process::id());
    println!("[agentosd] Architecture: {}", std::env::consts::ARCH);
    println!("[agentosd] ========================================");

    let running = Arc::new(AtomicBool::new(true));

    // Run dual daemon (TCP loopback + UDS)
    let res = agent_os_core::daemon::run_daemon_dual(port, Some(&socket_path), running);
    if let Err(e) = res {
        eprintln!("[agentosd] Daemon loop exited with error: {:?}", e);
        return Err(Box::new(e));
    }

    println!("[agentosd] Shutdown complete.");
    Ok(())
}
