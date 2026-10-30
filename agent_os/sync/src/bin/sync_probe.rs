//! Two-process loopback probe for the sync transport.
//!
//! Usage: `sync-probe server <port>` and `sync-probe client <port>`.
//! Synthetic identities only; no user data is read or written.

use std::time::Duration;
use traceform_sync::identity::DeviceIdentity;
use traceform_sync::transport::{dial, loopback, serve, Ed25519BindingVerifier};

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let role = args.get(1).map(String::as_str).unwrap_or("");
    let port: u16 = args.get(2).and_then(|value| value.parse().ok()).unwrap_or(0);
    if port == 0 {
        eprintln!("usage: sync-probe <server|client> <port>");
        std::process::exit(2);
    }
    if role == "server" {
        let identity = DeviceIdentity::from_seed(1);
        let expected = DeviceIdentity::from_seed(2).peer_id();
        let public_key = DeviceIdentity::from_seed(2).public_key_bytes();
        match serve(identity, expected, loopback(port), &Ed25519BindingVerifier, public_key, Duration::from_secs(20)).await {
            Ok(summary) if summary.binding_verified && summary.frames >= 1 => {
                println!("PASS server binding_verified=true frames={} bytes={}", summary.frames, summary.bytes);
            }
            Ok(summary) => {
                println!("FAIL server unexpected summary {summary:?}");
                std::process::exit(1);
            }
            Err(error) => {
                println!("FAIL server {error}");
                std::process::exit(1);
            }
        }
    } else if role == "client" {
        let identity = DeviceIdentity::from_seed(2);
        let expected = DeviceIdentity::from_seed(1).peer_id();
        match dial(identity, loopback(port), expected, Duration::from_secs(20)).await {
            Ok(summary) if summary.binding_verified => {
                println!("PASS client binding_verified=true frames={} bytes={}", summary.frames, summary.bytes);
            }
            Ok(summary) => {
                println!("FAIL client unexpected summary {summary:?}");
                std::process::exit(1);
            }
            Err(error) => {
                println!("FAIL client {error}");
                std::process::exit(1);
            }
        }
    } else if role == "client-unpaired" {
        // Not in the server allow-list: the connection must be refused.
        let identity = DeviceIdentity::from_seed(3);
        let expected = DeviceIdentity::from_seed(1).peer_id();
        match dial(identity, loopback(port), expected, Duration::from_secs(8)).await {
            Err(_) => println!("PASS client-unpaired connection refused"),
            Ok(_) => {
                println!("FAIL client-unpaired unexpectedly connected");
                std::process::exit(1);
            }
        }
    } else {
        eprintln!("unknown role");
        std::process::exit(2);
    }
}
