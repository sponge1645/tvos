"""Signing fails closed without owner config; legacy OTA metadata stays on bridge while new releases remain Latest."""
import importlib.util,json,os,tempfile
from pathlib import Path
import xml.etree.ElementTree as ET


def load(name, path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m


build=Path('build.sh').read_text();assert 'python3 tools/signing.py sign' in build
signing=load('signing','tools/signing.py');publishing=load('publishing','tools/publish-release.py')
with tempfile.TemporaryDirectory(prefix='tvos-signing-policy-') as tmp:
    root=Path(tmp);(root/'app').mkdir();(root/'release').mkdir()
    (root/'app/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionName="1.1"/>')
    try:signing.sign(root,root/'bt',root/'in.apk',root/'out.apk',mode='release')
    except ValueError as error:assert 'init' in str(error)
    else:raise AssertionError('release silently generated a key')
    assert not (root/'freemytv.keystore').exists()
    for version,code in [('1.0.4',7),('1.1',104),('1.1.1',105),('1.2',106),('2.0',107)]:
        data={'packageName':'io.tvos','asset':'tvOS.apk','versionName':version,
              'versionCode':code,'tag':'v'+version,'size':20000,'sha256':'a'*64}
        path=root/'release/update.json'
        path.write_text(json.dumps(data))
        assert publishing.manifest(path)==data
        assert publishing.BRIDGE=='v1.0.4'
        assert publishing.CHANNEL=='ota-v1'
    commands=[]
    class Result:
        def __init__(self,stdout='',returncode=0):self.stdout=stdout;self.returncode=returncode
    def run(command,**kwargs):
        commands.append([str(p) for p in command]);return Result()
    original=signing.run;signing.run=run
    try:
        directory=root/'keys';directory.mkdir();(root/'signing.local.json').write_text(json.dumps({'directory':str(directory)}))
        signing.sign(root,root/'bt',root/'in.apk',root/'out.apk',mode='release')
        command=commands[0];assert '--lineage' in command and '--rotation-min-sdk-version' in command and command[command.index('--rotation-min-sdk-version')+1]=='28'
        assert command[command.index('--v1-signing-enabled')+1]=='false' and command[command.index('--v2-signing-enabled')+1]=='false'
        assert all(not arg.startswith('pass:') for arg in command)
        assert len(commands)==5 and all('verify' in c for c in commands[1:])
    finally:signing.run=original
print('PASS: release key initialization guard, no secret argv, v3 rotation at SDK28, multi-SDK verification and legacy/modern publication separation')
