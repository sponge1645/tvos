"""Check OTA test setup refuses wrong versions, preserves source/key and removes temporary copies."""
from pathlib import Path
import os,subprocess,tempfile
source=Path('tools/test-ota-from-1.0.1.sh').read_text()
with tempfile.TemporaryDirectory(prefix='tvos-ota-script-') as directory:
    root=Path(directory);project=root/'project';(project/'tools').mkdir(parents=True);(project/'app').mkdir()
    script=project/'tools/test-ota-from-1.0.1.sh';script.write_text(source)
    manifest=project/'app/AndroidManifest.xml';original='<manifest android:versionCode="4" android:versionName="1.0.2"/>'
    manifest.write_text(original);(project/'config.properties').write_text('test=true\n');key=project/'freemytv.keystore';key.write_bytes(b'test-key')
    (project/'build.sh').write_text('''#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")"
python3 - <<'CHECK'
from pathlib import Path
import os
s=Path('app/AndroidManifest.xml').read_text()
assert ('versionCode="'+os.environ.get('TEST_CODE','3')+'"') in s
assert ('versionName="'+os.environ.get('TEST_NAME','1.0.1')+'"') in s
assert Path('freemytv.keystore').read_bytes()==b'test-key'
Path('tvOS.apk').write_bytes(b'test-apk')
CHECK
''')
    bin_dir=root/'bin';bin_dir.mkdir();adb=bin_dir/'adb'
    adb.write_text('''#!/usr/bin/env python3
import os,sys
from pathlib import Path
a=sys.argv[1:]
if a[:1]==['-e']:a=a[1:]
if a[:1]==['-s']:a=a[2:]
if a[:3]==['shell','dumpsys','package']:
 print('versionCode='+os.environ.get('TEST_CODE','3')+' minSdk=28')
 print('versionName='+os.environ.get('TEST_NAME','1.0.1'))
elif a[:2]==['shell','getprop']:print('test-platform')
elif a[:1]==['install']:
 apk=Path(a[-1]);assert apk.read_bytes()==b'test-apk'
 Path(os.environ['TRACE']).write_text(str(apk.parent))
 if os.environ.get('FAIL_INSTALL')=='1':sys.exit(1)
 print('Success')
elif a[:3]!=['shell','am','start']:raise AssertionError(a)
''');adb.chmod(0o755)
    trace=root/'trace';tmp=root/'tmp';tmp.mkdir()
    env={**os.environ,'PATH':str(bin_dir)+os.pathsep+os.environ['PATH'],'TMPDIR':str(tmp),'TRACE':str(trace)}
    wrong=subprocess.run(['bash',str(script)],env={**env,'TEST_CODE':'4','TEST_NAME':'1.0.2'},capture_output=True,text=True)
    assert wrong.returncode!=0 and not trace.exists() and not list(tmp.iterdir()),'wrong version must not build, install or copy key'
    for name,code in [('1.0','2'),('1.0.1','3')]:
      for fail in [False,True]:
        result=subprocess.run(['bash',str(script)],env={**env,'TEST_NAME':name,'TEST_CODE':code,'FAIL_INSTALL':str(int(fail))},capture_output=True,text=True)
        assert (result.returncode!=0)==fail,result.stderr
        assert not Path(trace.read_text()).exists(),'temporary source and copied key must be removed even after failure'
        assert manifest.read_text()==original and key.read_bytes()==b'test-key','source version and original signing key preserved'
        assert not (project/'tvOS.apk').exists(),'test output must not replace release APK'
    print('PASS: OTA test setup version guard, same-version install, isolated output, original key/source preserved, cleanup on failure')
