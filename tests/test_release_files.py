"""The release manifest must describe the exact staged APK bytes and Android version."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import zipfile

spec = importlib.util.spec_from_file_location('prepare_release', 'tools/prepare-release.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    (root / 'app').mkdir()
    (root / 'app/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.tvos" android:versionCode="3" android:versionName="1.2"/>')
    build_path = root / 'app/src/io/tvos/version/SoftwareBuild.java'
    build_path.parent.mkdir(parents=True)
    build_path.write_text('public static final int SEQUENCE = 171;')
    module.write_build_info(root)
    stamp = (root / 'out/assets/build-info.json').read_bytes()
    with zipfile.ZipFile(root / 'tvOS.apk', 'w') as apk:
        apk.writestr('assets/build-info.json', stamp)
        apk.writestr('classes.dex', b'test-release-payload' * 2000)
    payload = (root / 'tvOS.apk').read_bytes()
    aapt2 = root / 'aapt2'
    aapt2.write_text("#!/usr/bin/env python3\nimport sys\nassert sys.argv[1:3] == ['dump','badging']\nprint(\"package: name='io.tvos' versionCode='3' versionName='1.2'\")\n")
    aapt2.chmod(0o755)
    metadata = module.prepare(root, aapt2)
    assert (root / 'release/tvOS.apk').read_bytes() == payload
    assert json.loads((root / 'release/update.json').read_text()) == metadata
    assert metadata['sha256'] == hashlib.sha256(payload).hexdigest()
    assert metadata['size'] == len(payload)
    assert metadata['buildSequence'] == 171
    assert metadata['versionCode'] == 3 and metadata['versionName'] == '1.2' and metadata['tag'] == 'v1.2'
    def rejected():
        try:
            module.prepare(root, aapt2)
        except SystemExit:
            return
        raise AssertionError('stale or mismatched APK must be rejected')
    build_path.write_text('public static final int SEQUENCE = 172;')
    rejected()
    build_path.write_text('public static final int SEQUENCE = 171;')
    aapt2.write_text(aapt2.read_text().replace("versionCode='3'", "versionCode='2'"))
    rejected()
    with zipfile.ZipFile(root / 'tvOS.apk', 'w') as apk:
        apk.writestr('classes.dex', b'no build identity')
    rejected()
    assert json.loads((root / 'release/update.json').read_text()) == metadata
    assert (root / 'release/tvOS.apk').read_bytes() == payload
print('PASS: release APK bytes/hash, compiled manifest and build identity; stale APK rejected before staging')
