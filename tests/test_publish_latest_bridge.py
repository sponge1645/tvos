"""Offline regression tests for the dual GitHub release manifest contract."""
import importlib.util
import json
from pathlib import Path
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('publisher', 'tools/publish-release.py')
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)

def m(version, code):
    return dict(packageName='io.tvos',versionName=version,versionCode=code,
                tag='v'+version,asset='tvOS.apk',size=20000,sha256='a'*64)

with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp)
    (root/'release').mkdir()
    (root/'release/tvOS.apk').write_bytes(b'x'*20000)
    (root/'release/update.json').write_text(json.dumps(m('1.1',104)))
    bridge=m('1.0.4',7)
    calls=[]
    def fake_run(*args, capture=False):
        calls.append(args)
        class Result:
            stdout='{"isDraft":false}' if 'isDraft' in args else ''
        if args[:3] == ('release','download','v1.0.4'):
            location=Path(args[args.index('--dir')+1])
            (location/'update.json').write_text(json.dumps(bridge))
        if args[:3] == ('release','download','v1.1'):
            location=Path(args[args.index('--dir')+1])
            (location/'update.json').write_text(json.dumps(bridge))
        return Result()
    with patch.object(publisher,'run',fake_run):
        publisher.execute(root)
        assert calls[0][:3] == ('release','download','v1.0.4')
        create=[x for x in calls if x[:2] == ('release','create')][0]
        assert create[2]=='v1.1' and '--latest=true' in create
        assert Path(create[4]).name=='update.json'
        assert Path(create[4]) != root/'release/update.json'
        calls.clear()
        with patch.object(publisher,'ensure_channel',lambda:None):
            publisher.execute(root,publish=True)
        assert ('release','edit','v1.1','--draft=false','--latest=true') in calls
        assert any(x[:3] == ('release','upload','ota-v1')
                   and x[3] == str(root/'release/update.json') for x in calls)
    assert publisher.manifest(root/'release/update.json')['versionName']=='1.1'
    (root/'release/update.json').write_text(json.dumps(m('1.1.0-dev.97',103)))
    try:
        publisher.manifest(root/'release/update.json')
    except ValueError:
        pass
    else:
        raise AssertionError('Development version was accepted as stable')
print('PASS: legacy Latest pinned, current release Latest, modern OTA current')
