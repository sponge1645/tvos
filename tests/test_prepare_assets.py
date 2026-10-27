"""Exercise real asset staging, pin verification, cache reuse and failed-download isolation."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import zipfile

spec = importlib.util.spec_from_file_location('prepare_assets', 'tools/prepare-assets.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def check(value, why):
    if not value:
        raise AssertionError(why)


def rejects(call, why):
    try:
        call()
    except (ValueError, zipfile.BadZipFile):
        return
    raise AssertionError(why)


url = module.dropbox_url('https://www.dropbox.com/scl/fi/id/pack.zip?rlkey=key&dl=0')
check('rlkey=key' in url and 'dl=1' in url and 'dl=0' not in url, 'shared-link download conversion')
for invalid in ['http://www.dropbox.com/a', 'https://dropbox.com.evil/a', 'file:///tmp/a', 'https://user:pass@dropbox.com/a']:
    rejects(lambda: module.dropbox_url(invalid), 'reject non-Dropbox/insecure URL')

with tempfile.TemporaryDirectory(prefix='tvos-assets-') as tmp:
    root = Path(tmp)
    (root / 'tools').mkdir()
    (root / 'app/assets/logos').mkdir(parents=True)
    (root / 'app/assets/logos/logo.png').write_bytes(b'logo')
    original = root / 'pack.zip'
    contents = {'fonts/font.otf': b'font bytes', 'avatars/robot.webp': b'avatar bytes'}
    with zipfile.ZipFile(original, 'w') as archive:
        for name, data in contents.items():
            archive.writestr(name, data)
    manifest = {'sha256': module.digest(original), 'url': 'https://www.dropbox.com/s/pack.zip?dl=0',
                'files': {name: {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                          for name, data in contents.items()}}
    (root / 'tools/assets-pack.json').write_text(json.dumps(manifest))
    calls = []

    def fetch(url, destination):
        calls.append(url)
        destination.write_bytes(original.read_bytes())

    module.prepare(root, fetch)
    check(len(calls) == 1, 'first build downloads once')
    for name, data in contents.items():
        check((root / 'out/assets' / name).read_bytes() == data, 'original bytes staged')
        check(not (root / 'app/assets' / name).exists(), 'private files stay out of source assets')
    check((root / 'out/assets/logos/logo.png').read_bytes() == b'logo', 'bundled logos preserved')
    module.prepare(root, lambda *_: (_ for _ in ()).throw(AssertionError('offline build downloaded')))
    cache = root / '.tvos-assets' / (manifest['sha256'] + '.zip')
    cache.write_bytes(b'corrupt cache')
    module.prepare(root, fetch)
    check(len(calls) == 2, 'corrupt cache replaced by valid archive')
    cache.unlink()
    rejects(lambda: module.prepare(root, lambda url, p: p.write_bytes(b'wrong archive')), 'reject changed download')
    check((root / 'out/assets/fonts/font.otf').read_bytes() == b'font bytes', 'failed download preserves prior staging')
    check(not list((root / '.tvos-assets').glob('.download-*')), 'partial transfer cleaned')
    for name, data in contents.items():
        path = root / 'app/assets' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    module.prepare(root, lambda *_: (_ for _ in ()).throw(AssertionError('local verified build downloaded')))
    with zipfile.ZipFile(root / 'app.apk', 'w') as apk:
        for name, data in contents.items():
            apk.writestr('assets/' + name, data)
    module.verify_apk(root, root / 'app.apk')
    with zipfile.ZipFile(root / 'wrong.apk', 'w') as apk:
        apk.writestr('assets/fonts/font.otf', b'wrong')
    rejects(lambda: module.verify_apk(root, root / 'wrong.apk'), 'final APK must contain pinned resources')
    with zipfile.ZipFile(root / 'unexpected.zip', 'w') as archive:
        archive.writestr('../escaped', b'bad')
    unexpected = dict(manifest, sha256=module.digest(root / 'unexpected.zip'))
    rejects(lambda: module.validate_archive(root / 'unexpected.zip', unexpected), 'reject unexpected traversal entries')
print('PASS: actual asset downloader/staging, Dropbox links, pins, original bytes, offline cache, corruption and failed-transfer cleanup')
