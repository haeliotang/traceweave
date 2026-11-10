# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

"""Reject synthetic wrong TLS identities before MPC output; explicit local probe."""
import argparse,subprocess,ssl,hashlib,time,json
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
for name in ('identities','binary','circuit','output'): parser.add_argument('--'+name,required=True)
parser.add_argument('--session', help='Public transport session for the bound TLS runner')
args=parser.parse_args()
root=Path(args.identities);binary=args.binary;circuit=args.circuit
receipts=[]
for mode in ('wrong_pin','wrong_trust','wrong_key'):
 children=[]
 try:
  for role in (1,2):
   peer=3-role;pin=hashlib.sha256(ssl.PEM_cert_to_DER_cert((root/f'{peer}.pem').read_text())).hexdigest()
   trust=root/f'{peer}.pem';key=root/f'{role}.key'
   if mode=='wrong_pin' and role==1: pin='0'*64
   if mode=='wrong_trust' and role==1: trust=root/'1.pem'
   if mode=='wrong_key' and role==2: key=root/'1.key'
   command=[binary,str(role),'23791',circuit,str(root/f'{role}.pem'),str(key),str(trust),pin]
   if args.session is not None: command.append(args.session)
   child=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
   children.append(child);child.stdin.write(b'0000\n');child.stdin.close();child.stdin=None
   if role==1: time.sleep(.1)
  parties=[]
  for child in children:
   out,err=child.communicate(timeout=15)
   parties.append({'exit_code':child.returncode,'no_public_output':b'PUBLIC_OUTPUT=' not in out,'stderr':err.decode()})
  assert all(p['exit_code']!=0 and p['no_public_output'] for p in parties),mode
  reason={'wrong_pin':'TLS peer certificate pin rejected','wrong_key':'TLS identity configuration rejected','wrong_trust':'TLS'}[mode]
  assert any(reason in p['stderr'] for p in parties), (mode,parties)
  receipts.append({'case':mode,'passed':True,'parties':parties})
 finally:
  for child in children:
   if child.poll() is None: child.kill();child.communicate()
Path(args.output).write_text(json.dumps({'passed':True,'cases':receipts,'scope':'synthetic_loopback_tls_identity_rejection'},indent=2)+'\n')
print(json.dumps({'passed':True,'cases':len(receipts)}))
