use futures::StreamExt;
use libp2p::{allow_block_list::{AllowedPeers, Behaviour as Allow}, identity, noise,
    request_response::{self, cbor, Message, ProtocolSupport}, swarm::{NetworkBehaviour, SwarmEvent},
    tcp, yamux, Multiaddr, PeerId, StreamProtocol};
use std::{error::Error, time::Duration, io::{self, BufRead, Write}};
#[derive(NetworkBehaviour)]
struct Behaviour { allow: Allow<AllowedPeers>, message: cbor::Behaviour<String, String> }
fn key(n: u8) -> identity::Keypair { identity::Keypair::ed25519_from_bytes([n; 32]).unwrap() }
fn peer(n: u8) -> PeerId { key(n).public().to_peer_id() }
fn hex(bytes: &[u8]) -> String { bytes.iter().map(|b| format!("{b:02x}")).collect() }
#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
 let args: Vec<String> = std::env::args().collect();
 if args.get(1).map(String::as_str)==Some("ids") { println!("{} {}",hex(&peer(2).to_bytes()),hex(&peer(1).to_bytes())); return Ok(()); }
 let server=args.get(1).map(String::as_str)==Some("server");
 if !server && args.get(1).map(String::as_str)!=Some("client") { return Err("invalid role".into()); }
 let port: u16=args.get(2).ok_or("missing port")?.parse()?;
 let proof=if server { String::new() } else { args.get(3).ok_or("missing proof")?.clone() };
 let remote=if server {peer(2)} else {peer(1)};
 let mut swarm=libp2p::SwarmBuilder::with_existing_identity(key(if server {1} else {2}))
  .with_tokio().with_tcp(tcp::Config::default(),noise::Config::new,yamux::Config::default)?
  .with_behaviour(|_| { let mut allow=Allow::<AllowedPeers>::default();allow.allow_peer(remote);
   Behaviour {allow,message:cbor::Behaviour::new([(StreamProtocol::new("/traceform/synthetic-binding/1"),ProtocolSupport::Full)],request_response::Config::default())}
  })?.build();
 let address:Multiaddr=format!("/ip4/127.0.0.1/tcp/{port}").parse()?;
 if server {swarm.listen_on(address)?;} else {swarm.dial(address.with(libp2p::multiaddr::Protocol::P2p(remote)))?;}
 tokio::time::timeout(Duration::from_secs(45),async {
  let mut sent=false;let mut count=0;
  loop { match swarm.select_next_some().await {
   SwarmEvent::NewListenAddr {..}=>{println!("READY");io::stdout().flush().unwrap();},
   SwarmEvent::ConnectionEstablished {peer_id,..} if !server && !sent=>{assert_eq!(peer_id,remote);swarm.behaviour_mut().message.send_request(&remote,proof.clone());sent=true;},
   SwarmEvent::Behaviour(BehaviourEvent::Message(request_response::Event::Message {peer,message,..}))=>{
    assert_eq!(peer,remote);
    match message {
     Message::Request {request,channel,..} if server=>{
      println!("PROOF {} {} {}",hex(&peer.to_bytes()),hex(&swarm.local_peer_id().to_bytes()),request);io::stdout().flush().unwrap();
      let mut verdict=String::new();io::stdin().lock().read_line(&mut verdict).unwrap();
      let response=match verdict.trim() {"ACCEPT"=>"accepted","REJECT"=>"rejected",_=>panic!("invalid verdict")};
      swarm.behaviour_mut().message.send_response(channel,response.to_string()).unwrap();
     },
     Message::Response {response,..} if !server=>{
      if count==0 {assert_eq!(response,"accepted");count=1;swarm.behaviour_mut().message.send_request(&remote,proof.clone());}
      else {assert_eq!(response,"rejected");println!("PASS accepted then replay rejected");break;}
     },_=>panic!("unexpected message")
    }
   },
   SwarmEvent::ConnectionClosed {peer_id,..} if server=>{assert_eq!(peer_id,remote);println!("PASS server closed");break;},
   SwarmEvent::Behaviour(BehaviourEvent::Message(request_response::Event::OutboundFailure {error,..}))=>panic!("outbound {error}"),
   SwarmEvent::OutgoingConnectionError {error,..}=>panic!("connect {error}"),_=>{}
  }}
 }).await?;Ok(())
}
