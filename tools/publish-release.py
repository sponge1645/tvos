#!/usr/bin/env python3
"""Publish a current GitHub Latest while keeping legacy OTA pinned to 1.0.4."""
import argparse
import json
import re
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
REPO = 'sponge1645/tvos'
BRIDGE = 'v1.0.4'
CHANNEL = 'ota-v1'


def run(*args, capture=False):
    return subprocess.run(['gh', *args, '--repo', REPO], check=True,
                          capture_output=capture, text=True)


def manifest(path):
    data = json.loads(Path(path).read_text())
    version = data['versionName']
    if not re.fullmatch(r'[0-9]+(?:\\.[0-9]+){1,2}', version) or data['tag'] != 'v' + version:
        raise ValueError('Expected a stable version/tag.')
    if version != '1.0.4' and tuple(map(int, version.split('.'))) < (1, 1):
        raise ValueError('Only 1.0.4 and 1.1+ are supported.')
    if data['packageName'] != 'io.tvos' or data['asset'] != 'tvOS.apk':
        raise ValueError('Unexpected package or asset.')
    if not re.fullmatch(r'[0-9a-fA-F]{64}', data['sha256']):
        raise ValueError('Invalid SHA-256.')
    if not isinstance(data['versionCode'], int) or data['versionCode'] < 1:
        raise ValueError('Invalid version code.')
    if not isinstance(data['size'], int) or data['size'] < 10000:
        raise ValueError('Invalid APK size.')
    return data


def bridge_manifest(directory):
    """Get immutable bridge metadata from its tag, never from Latest."""
    result = run('release', 'download', BRIDGE, '--pattern', 'update.json',
                 '--dir', str(directory), '--clobber')
    data = manifest(directory / 'update.json')
    if data['versionName'] != '1.0.4' or data['tag'] != BRIDGE:
        raise ValueError('Bridge release does not contain 1.0.4 metadata.')
    return data


def current_latest_tag():
    data = json.loads(run('release', 'view', '--json', 'tagName', capture=True).stdout)
    return data['tagName']


def ensure_channel():
    present = subprocess.run(['gh', 'release', 'view', CHANNEL, '--repo', REPO],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if present.returncode:
        run('release', 'create', CHANNEL, '--title', 'Modern OTA channel',
            '--prerelease', '--latest=false',
            '--notes', 'OTA manifest for tvOS 1.0.4 and later.')


def execute(root, publish=False, channel=False):
    release = root / 'release'
    current = manifest(release / 'update.json')
    tag = current['tag']
    if channel:
        if json.loads(run('release', 'view', tag, '--json', 'isDraft', capture=True).stdout)['isDraft']:
            raise ValueError('Publish the versioned release before updating OTA.')
        ensure_channel()
        run('release', 'upload', CHANNEL, str(release / 'update.json'), '--clobber')
        return

    if publish:
        # Every Latest must retain the legacy bridge manifest: old clients read it.
        if current['versionName'] != '1.0.4':
            with tempfile.TemporaryDirectory(prefix='tvos-bridge-') as directory:
                old = bridge_manifest(Path(directory))
                if old['versionCode'] != 7:
                    raise ValueError('Unexpected bridge versionCode.')
                # Ensure the release really carries the old manifest, not the current one.
                with tempfile.TemporaryDirectory(prefix='tvos-latest-check-') as checkdir:
                    run('release', 'download', tag, '--pattern', 'update.json',
                        '--dir', checkdir, '--clobber')
                    on_release = manifest(Path(checkdir) / 'update.json')
                    if on_release != old:
                        raise ValueError('Release update.json must point to the 1.0.4 bridge.')
        run('release', 'edit', tag, '--draft=false', '--latest=true')
        execute(root, channel=True)
        return

    with tempfile.TemporaryDirectory(prefix='tvos-bridge-') as directory:
        if current['versionName'] == '1.0.4':
            legacy_path = release / 'update.json'
        else:
            bridge_manifest(Path(directory))
            legacy_path = Path(directory) / 'update.json'
        # The new APK is always in the version's release. update.json on Latest
        # intentionally points at the immutable v1.0.4 APK for old binaries.
        run('release', 'create', tag, str(release / 'tvOS.apk'), str(legacy_path),
            '--title', 'tvOS ' + current['versionName'],
            '--draft', '--latest=true')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    options = parser.add_mutually_exclusive_group()
    options.add_argument('--publish', action='store_true')
    options.add_argument('--update-channel', action='store_true')
    args = parser.parse_args()
    try:
        execute(ROOT, publish=args.publish, channel=args.update_channel)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit('Release publishing failed: ' + str(error)) from error
