"""The release manifest must describe the exact staged APK bytes and Android version."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile

spec = importlib.util.spec_from_file_location('prepare_release', 'tools/prepare-release.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    (root / 'app').mkdir()
    (root / 'app/AndroidManifest.xml').write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.tvos" android:versionCode="3" android:versionName="1.2"/>')
    payload = b'test-release-payload' * 2000
    (root / 'tvOS.apk').write_bytes(payload)
    metadata = module.prepare(root)
    assert (root / 'release/tvOS.apk').read_bytes() == payload
    assert json.loads((root / 'release/update.json').read_text()) == metadata
    assert metadata['sha256'] == hashlib.sha256(payload).hexdigest()
    assert metadata['size'] == len(payload)
    assert metadata['versionCode'] == 3 and metadata['versionName'] == '1.2' and metadata['tag'] == 'v1.2'
print('PASS: release APK bytes, SHA-256, size and Android version metadata')
