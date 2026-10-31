//! Two-process loopback probe for the sync transport.
//!
//! Usage: `sync-probe server <port>` and `sync-probe client <port>`.
//! Synthetic identities only; no user data is read or written.

use std::time::Duration;
use traceweave_sync::identity::DeviceIdentity;
use traceweave_sync::discovery::discover;
use traceweave_sync::pairing_session::{pair_dial, pair_serve};
use traceweave_sync::transport::{dial, loopback, serve, Ed25519BindingVerifier};
use sha2::{Digest, Sha256};

fn fingerprint(identity: &DeviceIdentity) -> String {
    let digest = Sha256::digest(identity.public_key_bytes());
    digest.iter().map(|byte| format!("{:02x}", byte)).collect()
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let role = args.get(1).map(String::as_str).unwrap_or("");
    let port: u16 = args.get(2).and_then(|value| value.parse().ok()).unwrap_or(0);
    if port == 0 && !matches!(role, "discover") {
        eprintln!("usage: sync-probe <server|client|pair-server|pair-client> <port> | sync-probe discover");
        std::process::exit(2);
    }
    if role == "server" {
        let identity = DeviceIdentity::from_seed(1);
        let expected = DeviceIdentity::from_seed(2).peer_id();
        match serve(identity, expected, loopback(port), &Ed25519BindingVerifier, Duration::from_secs(20)).await {
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
    } else if role == "pair-server" {
        let identity = DeviceIdentity::from_seed(1);
        let local = fingerprint(&identity);
        match pair_serve(identity, loopback(port), &local, Duration::from_secs(20)).await {
            Ok(session) => println!("PASS pair-server sas={} peer={} offer={}", session.sas, session.peer, session.peer_offer.fingerprint),
            Err(error) => { println!("FAIL pair-server {error}"); std::process::exit(1); }
        }
    } else if role == "discover" {
        let identity = DeviceIdentity::from_seed(9);
        match discover(identity, Duration::from_secs(6)).await {
            Ok(candidates) if !candidates.is_empty() => {
                for candidate in &candidates {
                    println!("PASS discover peer={} addrs={}", candidate.peer, candidate.addresses.len());
                }
            }
            Ok(_) => { println!("FAIL discover no peers"); std::process::exit(1); }
            Err(error) => { println!("FAIL discover {error}"); std::process::exit(1); }
        }
    } else if role == "pair-client" {
        let identity = DeviceIdentity::from_seed(2);
        let local = fingerprint(&identity);
        let address: libp2p::Multiaddr = format!("/ip4/127.0.0.1/tcp/{port}").parse().unwrap();
        match pair_dial(identity, address, &local, Duration::from_secs(20)).await {
            Ok(session) => println!("PASS pair-client sas={} peer={} offer={}", session.sas, session.peer, session.peer_offer.fingerprint),
            Err(error) => { println!("FAIL pair-client {error}"); std::process::exit(1); }
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
