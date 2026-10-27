#!/usr/bin/env python3
"""Create a reviewed release draft and update the separate modern OTA channel explicitly."""
import argparse
import json
import re
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
REPO = 'sponge1645/tvos'


def plan(root, channel=False, publish=False):
    release = root / 'release'
    metadata = json.loads((release / 'update.json').read_text())
    version, tag = metadata['versionName'], metadata['tag']
    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+){1,2}', version) or tag != 'v' + version:
        raise ValueError('Prepare a stable versioned release first.')
    if version == '1.0.4':
        latest = 'true'
    elif version.startswith('1.1') or tuple(int(p) for p in version.split('.')) >= (1, 1):
        latest = 'false'
    else:
        raise ValueError('Only the 1.0.4 bridge or stable 1.1+ releases are supported.')
    if publish:
        return [['gh', 'release', 'edit', tag, '--repo', REPO, '--draft=false', '--latest=' + latest]]
    if channel:
        return [['gh', 'release', 'view', tag, '--repo', REPO, '--json', 'isDraft'],
                ['gh', 'release', 'upload', 'ota-v1', str(release / 'update.json'), '--clobber', '--repo', REPO]]
    return [['gh', 'release', 'create', tag, str(release / 'tvOS.apk'), str(release / 'update.json'),
             '--repo', REPO, '--title', 'tvOS ' + version, '--draft', '--latest=' + latest]]


def execute(root, channel=False, publish=False):
    commands = plan(root, channel, publish)
    version = json.loads((root / 'release/update.json').read_text())['versionName']
    if channel or (publish and version != '1.0.4'):
        latest = subprocess.run(['gh', 'release', 'view', '--repo', REPO, '--json', 'tagName'], check=True, capture_output=True, text=True)
        if json.loads(latest.stdout)['tagName'] != 'v1.0.4':
            raise ValueError('Legacy latest must be v1.0.4 before publishing modern OTA.')
    if channel:
        result = subprocess.run(commands[0], check=True, capture_output=True, text=True)
        if json.loads(result.stdout)['isDraft']:
            raise ValueError('Publish the versioned release first; the channel must not point to a draft.')
        # This tag is never latest. A prerelease cannot replace the legacy 1.0.4 endpoint.
        exists = subprocess.run(['gh', 'release', 'view', 'ota-v1', '--repo', REPO], stdout=subprocess.DEVNULL,
                                stderr=subprocess.DEVNULL)
        if exists.returncode:
            subprocess.run(['gh', 'release', 'create', 'ota-v1', '--repo', REPO, '--title', 'Modern OTA channel',
                            '--prerelease', '--latest=false', '--notes', 'Update manifest for tvOS 1.0.4 and later.'], check=True)
        subprocess.run(commands[1], check=True)
    else:
        for command in commands:
            subprocess.run(command, check=True)
        if publish:
            execute(root, channel=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--update-channel', action='store_true')
    modes.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    try:
        execute(ROOT, args.update_channel, args.publish)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit('Release publishing failed: ' + str(error)) from error
