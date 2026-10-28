"""CI-only real apksigner rotation using disposable keys; no production keystore is read."""
import importlib.util
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

spec=importlib.util.spec_from_file_location('signing','tools/signing.py')
signing=importlib.util.module_from_spec(spec);spec.loader.exec_module(signing)
if os.environ.get('CI')!='true':raise SystemExit('This integration test requires CI=true.')
bt=signing.build_tools();payload=Path('out/aligned.apk').resolve()
with tempfile.TemporaryDirectory(prefix='tvos-rotation-') as tmp:
    root=Path(tmp);(root/'app').mkdir()
    original=root/'freemytv.keystore';password=root/'old-password.txt'
    signing.private_file(password,secrets.token_urlsafe(32)+'\n')
    signing.run([signing.java_tool('keytool'),'-genkeypair','-keystore',original,'-alias','freemytv',
                 '-keyalg','RSA','-keysize','2048','-validity','2','-storepass:file',password,'-dname','CN=Disposable Rotation Test'])
    old_hash=signing.key_certificate(original,'freemytv',password)
    signing.initialize(root,root/'owner-keys',original,password.read_text().strip(),expected=old_hash)
    assert not original.exists() and (root/'owner-keys/legacy-original.keystore').is_file()
    signing.LEGACY_SHA256=old_hash
    manifest=root/'app/AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionName="1.0.4"/>')
    signing.sign(root,bt,payload,root/'old.apk',mode='legacy')
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionName="1.1"/>')
    signing.sign(root,bt,payload,root/'new.apk',mode='release')
    new_hash=signing.key_certificate(root/'owner-keys/tvos-release.p12','tvos-release',root/'owner-keys/release-password.txt')
    assert new_hash!=old_hash
    for sdk in (28,30,33,34):
        result=signing.run([bt/'apksigner','verify','--print-certs','--min-sdk-version',str(sdk),'--max-sdk-version',str(sdk),root/'new.apk'],capture=True)
        assert new_hash in result.stdout.decode().lower(), 'New signer not used for SDK '+str(sdk)
print('PASS: real disposable RSA keys, legacy-to-new lineage, new-only release signing and API28/30/33/34 signature validation')
