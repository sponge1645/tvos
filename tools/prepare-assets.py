#!/usr/bin/env python3
"""Stage APK assets; fetch the pinned external font/avatar pack only when needed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.parse
import zipfile


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            value.update(chunk)
    return value.hexdigest()


def dropbox_url(url):
    parsed = urllib.parse.urlsplit(url)
    host = (parsed.hostname or '').lower()
    if parsed.scheme != 'https' or parsed.username or parsed.password or not (
        host in ('dropbox.com', 'www.dropbox.com', 'dl.dropboxusercontent.com')
        or host.endswith('.dropboxusercontent.com')
    ):
        raise ValueError('Assets URL must be an HTTPS Dropbox shared link.')
    query = [(key, value) for key, value in urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
             if key not in ('dl', 'raw')]
    query.append(('dl', '1'))
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path,
                                   urllib.parse.urlencode(query), ''))


def download(url, destination):
    # Dropbox redirects are supported, but redirects may not downgrade to HTTP.
    try:
        subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error',
                        '--proto', '=https', '--proto-redir', '=https', '--retry', '2',
                        '--connect-timeout', '20', '--max-time', '180',
                        '--max-filesize', str(32 * 1024 * 1024),
                        '--output', str(destination), dropbox_url(url)], check=True)
    except subprocess.CalledProcessError as error:
        raise ValueError('Dropbox asset download failed; the existing cache was not changed.') from error


def config_url(root):
    config = root / 'config.properties'
    found = ''
    if config.is_file():
        for line in config.read_text().splitlines():
            key, sep, value = line.partition('=')
            if sep and key.strip() == 'assets_url':
                found = value.strip()
    return found


def valid_file(path, spec):
    return path.is_file() and path.stat().st_size == spec['size'] and digest(path) == spec['sha256']


def validate_archive(path, manifest):
    if path.stat().st_size > 32 * 1024 * 1024 or digest(path) != manifest['sha256']:
        raise ValueError('Asset archive SHA-256 does not match the pinned pack.')
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) != set(manifest['files']):
            raise ValueError('Asset archive has missing, duplicate or unexpected entries.')
        for name, spec in manifest['files'].items():
            info = archive.getinfo(name)
            if info.file_size != spec['size'] or hashlib.sha256(archive.read(name)).hexdigest() != spec['sha256']:
                raise ValueError('Asset file hash mismatch: ' + name)


def prepare(root, fetch=download):
    manifest = json.loads((root / 'tools/assets-pack.json').read_text())
    for name in manifest['files']:
        path = Path(name)
        if path.is_absolute() or '..' in path.parts or path.parts[0] not in ('fonts', 'avatars'):
            raise ValueError('Invalid asset manifest path.')
    source = root / 'app/assets'
    local = all(valid_file(source / name, spec) for name, spec in manifest['files'].items())
    cached = None
    if not local:
        cache = root / '.tvos-assets'
        cache.mkdir(exist_ok=True)
        cached = cache / (manifest['sha256'] + '.zip')
        if cached.exists():
            try:
                validate_archive(cached, manifest)
            except (ValueError, zipfile.BadZipFile):
                cached.unlink()
        if not cached.exists():
            local_archive = os.environ.get('TVOS_ASSETS_ARCHIVE', '')
            url = os.environ.get('TVOS_ASSETS_URL', '') or config_url(root) or manifest.get('url', '')
            if not local_archive and not url:
                raise ValueError('Assets are missing. Set TVOS_ASSETS_URL or assets_url in config.properties to the Dropbox ZIP link.')
            fd, name = tempfile.mkstemp(prefix='.download-', dir=cache)
            os.close(fd)
            temporary = Path(name)
            try:
                if local_archive:
                    shutil.copyfile(local_archive, temporary)
                else:
                    fetch(url, temporary)
                validate_archive(temporary, manifest)
                temporary.replace(cached)
            finally:
                temporary.unlink(missing_ok=True)
    output = root / 'out'
    output.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.assets-', dir=output) as temporary:
        stage = Path(temporary) / 'assets'
        shutil.copytree(source, stage, ignore=shutil.ignore_patterns('fonts', 'avatars'))
        if local:
            for name in manifest['files']:
                destination = stage / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source / name, destination)
        else:
            with zipfile.ZipFile(cached) as archive:
                for name in manifest['files']:
                    destination = stage / name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(archive.read(name))
        target = output / 'assets'
        if target.exists():
            shutil.rmtree(target)
        stage.replace(target)
    print('APK assets prepared (local verified files).' if local else 'APK assets prepared (verified pack cache).')


def verify_apk(root, path):
    manifest = json.loads((root / 'tools/assets-pack.json').read_text())
    with zipfile.ZipFile(path) as apk:
        for name, spec in manifest['files'].items():
            entry = 'assets/' + name
            if entry not in apk.namelist() or apk.getinfo(entry).file_size != spec['size']:
                raise ValueError('APK asset missing or wrong size: ' + name)
            if hashlib.sha256(apk.read(entry)).hexdigest() != spec['sha256']:
                raise ValueError('APK asset hash mismatch: ' + name)
    print('APK external asset hashes verified.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-apk', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    try:
        if args.verify_apk:
            verify_apk(root, args.verify_apk)
        else:
            prepare(root)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        raise SystemExit('Asset preparation failed: ' + str(error)) from error
