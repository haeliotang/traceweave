import argparse,base64,hashlib,json,os,queue,shlex,socket,subprocess,threading,time
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.exceptions import InvalidSignature
parser=argparse.ArgumentParser(description='Run synthetic isolated Keystore and Noise binding experiment')
for arg in ('adb','serial','host','binary','apk'):parser.add_argument('--'+arg,required=True)
args=parser.parse_args()
adb=[args.adb,'-s',args.serial];package='org.traceform.agentos.actionsvalidation';target='/data/local/tmp/traceform-live-binding-probe';host=args.host;binary=args.binary
ids=subprocess.run([host,'ids'],capture_output=True,text=True,check=True).stdout.split();sender,receiver=map(bytes.fromhex,ids);nonce=os.urandom(32)
enc=lambda b:base64.b64encode(b).decode(); packages=subprocess.run(adb+['shell','pm','list','packages',package],capture_output=True,text=True,check=True).stdout.splitlines()
if 'package:'+package in packages:raise RuntimeError('Existing test package must be preserved')
if subprocess.run(adb+['shell','test','-e',target]).returncode==0:raise RuntimeError('Test executable path exists')
sock=socket.socket();sock.bind(('127.0.0.1',0));port=sock.getsockname()[1];sock.close();spec='tcp:'+str(port)
if spec in subprocess.run(adb+['reverse','--list'],capture_output=True,text=True,check=True).stdout:raise RuntimeError('Existing forwarding must be preserved')
installed=False;copied=False;forwarded=False;server=None;client=None
try:
 subprocess.run(adb+['install',args.apk],check=True,stdout=subprocess.DEVNULL);installed=True
 subprocess.run(adb+['shell','am','start','-W','-S','-a','SIGN_BINDING_FIXTURE','-n',package+'/org.traceform.agentos.DeviceIdentityValidationActivity','--es','sender',enc(sender),'--es','receiver',enc(receiver),'--es','challenge',enc(nonce)],check=True,stdout=subprocess.DEVNULL)
 proof=None
 for _ in range(40):
  result=subprocess.run(adb+['exec-out','run-as',package,'cat','files/synthetic-binding.json'],capture_output=True)
  if result.returncode==0:proof=json.loads(result.stdout);break
  time.sleep(.25)
 if proof is None:raise RuntimeError('Synthetic Keystore proof unavailable')
 pinned=base64.b64decode(proof['public_key'],validate=True);signature=base64.b64decode(proof['signature'],validate=True)
 verifier=serialization.load_der_public_key(pinned)
 def message(a,b,n):return b'traceform-device-transport-binding-v1\0'+len(a).to_bytes(2,'big')+a+len(b).to_bytes(2,'big')+b+n
 verifier.verify(signature,message(sender,receiver,nonce),ec.ECDSA(hashes.SHA256()))
 for wrong in (message(receiver,sender,nonce),message(sender,receiver,bytes([nonce[0]^1])+nonce[1:])):
  try:verifier.verify(signature,wrong,ec.ECDSA(hashes.SHA256()))
  except InvalidSignature:continue
  raise RuntimeError('Tampered binding was accepted')
 subprocess.run(adb+['push',binary,target],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);copied=True
 subprocess.run(adb+['shell','chmod','700',target],check=True)
 subprocess.run(adb+['reverse',spec,spec],check=True,stdout=subprocess.DEVNULL);forwarded=True
 server=subprocess.Popen([host,'server',str(port)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
 def line():
  q=queue.Queue();threading.Thread(target=lambda:q.put(server.stdout.readline().strip()),daemon=True).start()
  return q.get(timeout=40)
 if line()!='READY':raise RuntimeError('Server not ready')
 body=enc(json.dumps(proof,separators=(',',':')).encode())
 remote_cmd=shlex.join([target,'client',str(port),body]);client=subprocess.Popen(adb+['shell',remote_cmd],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 used=set()
 for count in range(2):
  row=line().split(' ',3)
  if len(row)!=4 or row[0]!='PROOF':raise RuntimeError('Missing verified transport event')
  observed_sender,observed_receiver=bytes.fromhex(row[1]),bytes.fromhex(row[2]);received=json.loads(base64.b64decode(row[3],validate=True))
  if observed_sender!=sender or observed_receiver!=receiver or base64.b64decode(received['public_key'],validate=True)!=pinned:raise RuntimeError('Unexpected authenticated identity')
  incoming=base64.b64decode(received['signature'],validate=True)
  verifier.verify(incoming,message(observed_sender,observed_receiver,nonce),ec.ECDSA(hashes.SHA256()))
  token=hashlib.sha256(pinned+nonce).digest(); verdict='REJECT' if token in used else 'ACCEPT';used.add(token)
  if (count==0 and verdict!='ACCEPT') or (count==1 and verdict!='REJECT'):raise RuntimeError('Unexpected replay decision')
  server.stdin.write(verdict+'\n');server.stdin.flush()
 out,err=client.communicate(timeout=40);server_out,server_err=server.communicate(timeout=15)
 if client.returncode or server.returncode or 'PASS accepted then replay rejected' not in out or 'PASS server closed' not in server_out:raise RuntimeError('Transport completion failed: '+out+err+server_out+server_err)
 print('PASS Pixel Keystore signature verified against actual Noise peers; same-session replay rejected')
finally:
 for proc in (client,server):
  if proc and proc.poll() is None:proc.kill();proc.wait()
 if forwarded:subprocess.run(adb+['reverse','--remove',spec],check=True,stdout=subprocess.DEVNULL)
 if copied:subprocess.run(adb+['shell','rm','-f',target],check=True)
 if installed:subprocess.run(adb+['uninstall',package],check=True,stdout=subprocess.DEVNULL)
 print('Isolated app, identity, program and forwarding removed')
