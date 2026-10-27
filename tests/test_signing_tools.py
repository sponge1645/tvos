"""Signing fails closed without owner config; release drafts never expose modern APKs to legacy latest."""
import importlib.util,json,os,tempfile
from pathlib import Path
import xml.etree.ElementTree as ET


def load(name, path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m


signing=load('signing','tools/signing.py');publishing=load('publishing','tools/publish-release.py')
with tempfile.TemporaryDirectory(prefix='tvos-signing-policy-') as tmp:
    root=Path(tmp);(root/'app').mkdir();(root/'release').mkdir()
    (root/'app/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionName="1.1"/>')
    try:signing.sign(root,root/'bt',root/'in.apk',root/'out.apk',mode='release')
    except ValueError as error:assert 'init' in str(error)
    else:raise AssertionError('release silently generated a key')
    assert not (root/'freemytv.keystore').exists()
    for version,expected in [('1.0.4','true'),('1.1','false'),('1.1.1','false'),('1.2','false'),('2.0','false')]:
        (root/'release/update.json').write_text(json.dumps({'versionName':version,'tag':'v'+version}))
        command=publishing.plan(root)[0];assert '--draft' in command and '--latest='+expected in command
        assert publishing.plan(root,True)[1][3]=='ota-v1'
        publish=publishing.plan(root,publish=True)[0];assert '--draft=false' in publish and '--latest='+expected in publish
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
        assert all(not arg.startswith('pass:') for arg in command)
        assert len(commands)==5 and all('verify' in c for c in commands[1:])
    finally:signing.run=original
print('PASS: release key initialization guard, no secret argv, v3 rotation at SDK28, multi-SDK verification and legacy/modern publication separation')
