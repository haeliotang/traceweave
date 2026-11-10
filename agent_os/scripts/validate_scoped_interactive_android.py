# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import argparse,json,zipfile,sqlite3,tempfile,subprocess,time,shlex,re,secrets,xml.etree.ElementTree as ET,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'agent_os/scripts'))
from verify_release_android import apk_identity
parser=argparse.ArgumentParser(description="Validate a synthetic Edge summary through the task approval dialog")
for name in ('adb','serial','edge-apk','probe-apk','build-tools'):parser.add_argument('--'+name,required=True)
args=parser.parse_args()
adb=[args.adb,'-s',args.serial];edge='com.inceptless.traceweave.edge.validation';probe='com.inceptless.traceweave.actionsvalidation';edge_apk=args.edge_apk;probe_apk=args.probe_apk;bt=Path(args.build_tools);installed=[]
for path,pkg in ((edge_apk,edge),(probe_apk,probe)):
 identity=apk_identity(Path(path),bt)
 if identity['package']!=pkg or not identity['debuggable']:raise RuntimeError('Unexpected validation APK')
def run(*args,**kw):return subprocess.run(adb+list(args),check=True,**kw)
packages=run('shell','pm','list','packages',capture_output=True,text=True).stdout.splitlines()
if any('package:'+p in packages for p in (edge,probe)):raise RuntimeError('Existing validation package must be preserved')
try:
 for pkg,apk in ((edge,edge_apk),(probe,probe_apk)):
  run('install',apk,stdout=subprocess.DEVNULL);installed.append(pkg)
 now=int(run('shell','date','+%s',capture_output=True,text=True).stdout.strip())*1000
 with zipfile.ZipFile(edge_apk) as z:
  binding=json.loads(z.read('assets/model_binding.json'));tokens=[n['state_token'] for n in json.loads(z.read('assets/manifold.json'))['nodes']]
 with tempfile.TemporaryDirectory(prefix='traceform-interactive-') as d:
  path=Path(d)/'synthetic.sqlite'
  with sqlite3.connect(path) as db:
   db.execute('CREATE TABLE warm_trajectory (bucket INTEGER PRIMARY KEY, ts INTEGER, payload TEXT, n INTEGER)')
   for i in range(6):
    ts=(now//30000-8+i)*30000;frame={'timestamp_epoch_ms':ts,'state_token':tokens[i%len(tokens)],**binding,'source_mode':'synthetic_fixture','session_id':'synthetic_session','continuous_from_previous':i>0}
    db.execute('INSERT INTO warm_trajectory VALUES (?,?,?,1)',(ts//30000,ts,json.dumps(frame)))
  cmd=shlex.join(['run-as',edge,'sh','-c','mkdir -p databases && cat > databases/edge.sqlite'])
  with path.open('rb') as payload:run('shell',cmd,stdin=payload)
 run('shell','am','start','-W','-a','SEED','-n',probe+'/com.inceptless.traceweave.ScopedInteractiveProbe',stdout=subprocess.DEVNULL)
 pid=run('shell','pidof',probe,capture_output=True,text=True).stdout.strip().split()[0]
 def log_for(pattern):
  for _ in range(60):
   log=run('logcat','-d','--pid='+pid,'-s','ScopedInteractive:I','*:S',capture_output=True,text=True).stdout
   if 'FAIL' in log:raise RuntimeError('Isolated probe failed: '+log[-500:])
   if pattern in log:return
   time.sleep(.5)
  raise RuntimeError('Probe timed out')
 log_for('PASS seed ungranted read rejected')
 ui='/data/local/tmp/traceform-interactive-'+secrets.token_hex(12)+'.xml'
 if subprocess.run(adb+['shell','test','-e',ui]).returncode == 0:raise RuntimeError('Temporary UI path already exists')
 try:
  for _ in range(10):
   run('shell','uiautomator','dump',ui,stdout=subprocess.DEVNULL)
   tree=ET.fromstring(run('shell','cat',ui,capture_output=True).stdout)
   found=[n for n in tree.iter('node') if n.get('package')==probe and n.get('text','').upper()=='ALLOW FOR 5 MINUTES']
   if found:break
   time.sleep(.5)
  if len(found)!=1:raise RuntimeError('Expected isolated approval dialog unavailable')
  bounds=re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]',found[0].get('bounds',''))
  if not bounds:raise RuntimeError('Approval button bounds unavailable')
  x1,y1,x2,y2=map(int,bounds.groups());run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
 finally:run('shell','rm','-f',ui,stdout=subprocess.DEVNULL)
 run('shell','am','start','-W','-a','VERIFY','-n',probe+'/com.inceptless.traceweave.ScopedInteractiveProbe',stdout=subprocess.DEVNULL)
 log_for('PASS interactive grant synthetic summary revoked denied')
 print('PASS actual UI grant, synthetic Edge summary, revoke and denial')
finally:
 for pkg in reversed(installed):run('uninstall',pkg,stdout=subprocess.DEVNULL)
 print('Isolated applications and synthetic data removed')
