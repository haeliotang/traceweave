# SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
# SPDX-License-Identifier: AGPL-3.0-or-later

import argparse,subprocess,sys,shlex,tempfile
from pathlib import Path
sys.path.insert(0,'agent_os/scripts')
from acceptance_android import distinct_validation_uids
from verify_release_android import apk_identity
parser=argparse.ArgumentParser(description="Test synthetic offline archive conflicts between isolated apps")
for name in ('adb','serial','source-apk','target-apk','build-tools'):parser.add_argument('--'+name,required=True)
args=parser.parse_args()
adb=[args.adb,'-s',args.serial];source='com.inceptless.traceweave.actionsvalidation';target='com.inceptless.traceweave.documentsvalidation';bt=Path(args.build_tools)
apks={'source':Path(args.source_apk),'target':Path(args.target_apk)}
results=tempfile.TemporaryDirectory(prefix='traceform-memory-branches-');root=Path(results.name)
for name,pkg in [('source',source),('target',target)]:
 identity=apk_identity(apks[name],bt)
 if identity['package']!=pkg or not identity['debuggable']:raise RuntimeError('unexpected APK')
packages=subprocess.run(adb+['shell','pm','list','packages'],capture_output=True,text=True,check=True).stdout.splitlines()
if any('package:'+p in packages for p in [source,target]):raise RuntimeError('Existing package must be preserved')
installed=[]
def case(pkg,name):
 subprocess.run(['python3','agent_os/scripts/validate_actions_android.py','--adb',adb[0],'--serial',adb[2],'--package',pkg,'--case',name,'--output',str(root/(name+'.json'))],check=True)
def transfer(src,dst,name):
 data=subprocess.run(adb+['exec-out','run-as',src,'cat','files/'+name],check=True,capture_output=True).stdout
 command=shlex.join(['run-as',dst,'sh','-c','mkdir -p files && cat > files/'+name])
 subprocess.run(adb+['shell',command],input=data,check=True)
try:
 for name,p in [('source',source),('target',target)]:
  subprocess.run(adb+['install',str(apks[name])],check=True,stdout=subprocess.DEVNULL);installed.append(p)
 distinct_validation_uids(adb)
 case(source,'MEMORY_BRANCH_SEED')
 for name in ('branch-initial.taos','branch-ids.json'):transfer(source,target,name)
 case(target,'MEMORY_BRANCH_TARGET_EDIT')
 case(source,'MEMORY_BRANCH_SOURCE_EDIT')
 transfer(source,target,'branch-source.taos')
 case(target,'MEMORY_BRANCH_TARGET_RESOLVE')
 transfer(target,source,'branch-target.taos')
 case(source,'MEMORY_BRANCH_SOURCE_RESOLVE')
 print('PASS cross-install offline branch conflict and deletion')
finally:
 for p in reversed(installed):subprocess.run(adb+['uninstall',p],check=True,stdout=subprocess.DEVNULL)
 results.cleanup()
 print('Synthetic applications removed')
