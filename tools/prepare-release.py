#!/usr/bin/env python3
"""Package the APK that build.sh has just signed; no credentials are published."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET


def prepare(root):
    manifest = ET.parse(root / 'app/AndroidManifest.xml').getroot()
    namespace = '{http://schemas.android.com/apk/res/android}'
    apk = root / 'tvOS.apk'
    if not apk.is_file():
        raise SystemExit('Build tvOS.apk first.')
    version = manifest.attrib[namespace + 'versionName']
    code = int(manifest.attrib[namespace + 'versionCode'])
    build_source = (root / 'app/src/io/tvos/version/SoftwareBuild.java').read_text()
    build_match = re.search(r'public static final int SEQUENCE = ([0-9]+);', build_source)
    if not build_match:
        raise SystemExit('SoftwareBuild.SEQUENCE is missing.')
    build = int(build_match.group(1))
    if not 1 <= build <= 999999:
        raise SystemExit('Invalid software build sequence.')
    digest = hashlib.sha256()
    with apk.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    metadata = {
        'packageName': manifest.attrib['package'],
        'versionName': version,
        'versionCode': code,
        'buildSequence': build,
        'tag': 'v' + version,
        'asset': 'tvOS.apk',
        'size': apk.stat().st_size,
        'sha256': digest.hexdigest(),
    }
    release = root / 'release'
    release.mkdir(exist_ok=True)
    shutil.copy2(apk, release / 'tvOS.apk')
    (release / 'update.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print('Release files: release/tvOS.apk and release/update.json')
    print('GitHub Release tag:', metadata['tag'])
    return metadata


if __name__ == '__main__':
    prepare(Path(__file__).resolve().parent.parent)
