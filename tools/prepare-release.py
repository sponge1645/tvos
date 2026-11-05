#!/usr/bin/env python3
"""Package the APK that build.sh has just signed; no credentials are published."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def source_identity(root):
    manifest = ET.parse(root / 'app/AndroidManifest.xml').getroot()
    namespace = '{http://schemas.android.com/apk/res/android}'
    version = manifest.attrib[namespace + 'versionName']
    code = int(manifest.attrib[namespace + 'versionCode'])
    build_source = (root / 'app/src/io/tvos/version/SoftwareBuild.java').read_text()
    build_match = re.search(r'public static final int SEQUENCE = ([0-9]+);', build_source)
    if not build_match:
        raise SystemExit('SoftwareBuild.SEQUENCE is missing.')
    build = int(build_match.group(1))
    if not 1 <= build <= 999999:
        raise SystemExit('Invalid software build sequence.')
    return {'packageName': manifest.attrib['package'], 'versionName': version,
            'versionCode': code, 'buildSequence': build}


def write_build_info(root):
    path = root / 'out/assets/build-info.json'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(source_identity(root), sort_keys=True) + '\n')


def prepare(root, aapt2=None):
    apk = root / 'tvOS.apk'
    if not apk.is_file():
        raise SystemExit('Build tvOS.apk first.')
    identity = source_identity(root)
    try:
        with zipfile.ZipFile(apk) as archive:
            entry = archive.getinfo('assets/build-info.json')
            if entry.file_size > 4096:
                raise ValueError('oversized build identity')
            built = json.loads(archive.read(entry))
    except (OSError, KeyError, ValueError, zipfile.BadZipFile) as error:
        raise SystemExit('APK build identity is missing or invalid; rebuild tvOS.apk.') from error
    if built != identity:
        raise SystemExit('APK build identity differs from sources; rebuild tvOS.apk.')
    tool = str(aapt2) if aapt2 else shutil.which('aapt2')
    if not tool:
        raise SystemExit('Pass --aapt2 from Android SDK build-tools to verify the APK manifest.')
    try:
        badging = subprocess.check_output([tool, 'dump', 'badging', str(apk)], text=True)
    except (OSError, subprocess.CalledProcessError) as error:
        raise SystemExit('Cannot inspect the APK manifest with aapt2.') from error
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not package or (package[1], int(package[2]), package[3]) != (
            identity['packageName'], identity['versionCode'], identity['versionName']):
        raise SystemExit('Compiled APK manifest differs from sources; rebuild tvOS.apk.')
    digest = hashlib.sha256()
    with apk.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    metadata = {
        **identity,
        'tag': 'v' + identity['versionName'],
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aapt2')
    parser.add_argument('--write-build-info', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    if args.write_build_info:
        write_build_info(root)
    else:
        prepare(root, args.aapt2)
