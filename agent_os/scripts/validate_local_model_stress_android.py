import argparse,subprocess,time,json,shlex,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'agent_os/scripts'))
from verify_release_android import apk_identity
parser=argparse.ArgumentParser(description="Measure synthetic on-device model latency with a thermal guard")
for name in ('adb','serial','apk','model','build-tools','output'):parser.add_argument('--'+name,required=True)
args=parser.parse_args()
adb=[args.adb,'-s',args.serial];pkg='org.traceform.agentos.actionsvalidation';apk=args.apk;model=Path(args.model);installed=False
identity=apk_identity(Path(apk),Path(args.build_tools))
if identity['package']!=pkg or not identity['debuggable']:raise RuntimeError('Only the isolated debug package is allowed')
packages=subprocess.run(adb+['shell','pm','list','packages',pkg],capture_output=True,text=True,check=True).stdout.splitlines()
if 'package:'+pkg in packages:raise RuntimeError('Existing validation app must be preserved')
try:
 subprocess.run(adb+['install',apk],check=True,stdout=subprocess.DEVNULL);installed=True
 with model.open('rb') as f:subprocess.run(adb+['shell',shlex.join(['run-as',pkg,'sh','-c','mkdir -p files && cat > files/synthetic-evaluation-model.gguf'])],stdin=f,check=True)
 subprocess.run(adb+['shell','am','start','-W','-a','STRESS','--es','model','qwen15','-n',pkg+'/org.traceform.agentos.LocalModelQualityProbe'],check=True,stdout=subprocess.DEVNULL)
 pid=subprocess.run(adb+['shell','pidof',pkg],capture_output=True,text=True,check=True).stdout.strip().split()[0]
 for _ in range(480):
  logs=subprocess.run(adb+['logcat','-d','--pid='+pid,'-s','AgentOsModelQuality:I','*:S'],capture_output=True,text=True,check=True).stdout
  lines=[line for line in logs.splitlines() if 'STRESS_RESULT_READY' in line or 'FAIL ' in line]
  if lines:
   if 'STRESS_RESULT_READY' not in lines[-1]:raise RuntimeError(lines[-1])
   d=json.loads(subprocess.run(adb+['exec-out','run-as',pkg,'cat','files/synthetic-stress-result.json'],capture_output=True,check=True).stdout);Path(args.output).write_text(json.dumps(d,indent=2)+'\n')
   print('Stress completed:',d['stop_reason'],'cases:',len(d['results']));break
  time.sleep(.5)
 else:raise RuntimeError('Stress test timed out')
finally:
 if installed:subprocess.run(adb+['uninstall',pkg],check=True,stdout=subprocess.DEVNULL)
 print('Isolated application and model copy removed')
