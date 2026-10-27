#!/usr/bin/env bash
# ./release.sh 1.0.1 -> increase versionCode, build and prepare both GitHub Release assets.
set -euo pipefail
cd "$(dirname "$0")"
[ -f signing.local.json ] || { echo 'Run python3 tools/signing.py init before releasing.'; exit 1; }
[ -f config.properties ] || { echo 'Missing config.properties. Restore your local settings before releasing.'; exit 1; }
python3 - "${1:-}" <<'PY'
from pathlib import Path
import re
import sys

version = sys.argv[1]
if not re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,2}', version):
    raise SystemExit('Usage: ./release.sh 1.0.1')
path = Path('app/AndroidManifest.xml')
source = path.read_text()
old = re.search(r'android:versionName="([^"]+)"', source).group(1)
if version == old:
    print('Preparing the current version:', version)
else:
    def parts(value):
        return tuple((list(map(int, value.split('-')[0].split('.'))) + [0, 0, 0])[:3])
    if parts(version) < parts(old) or (parts(version) == parts(old) and '-' not in old):
        raise SystemExit('The release version must increase.')
    code = int(re.search(r'android:versionCode="([0-9]+)"', source).group(1)) + 1
    if code > 2100000000:
        raise SystemExit('Android versionCode limit reached.')
    source = re.sub(r'android:versionCode="[0-9]+"', f'android:versionCode="{code}"', source, count=1)
    source = re.sub(r'android:versionName="[^"]+"', f'android:versionName="{version}"', source, count=1)
    path.write_text(source)
    print(f'Release {version}, Android versionCode {code}')
PY
./build.sh
