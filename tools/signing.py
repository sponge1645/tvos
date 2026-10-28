#!/usr/bin/env python3
"""Local release signing and a verified old-to-new key lineage; no production secrets in Git."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
LEGACY_SHA256 = '50aa9806f8db2f8371861d12aeb85f03dad86782feb67ef12201d88e75b72c88'


def run(args, env=None, capture=False):
    return subprocess.run([str(arg) for arg in args], env=env, check=True,
                          stdout=subprocess.PIPE if capture else None)


def java_tool(name):
    return str(Path(os.environ['JAVA_HOME']) / 'bin' / name) if os.environ.get('JAVA_HOME') else name


def build_tools():
    candidates = [os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
                  str(Path.home() / 'Library/Android/sdk'), str(Path.home() / 'Android/Sdk')]
    for candidate in candidates:
        if candidate:
            versions = list((Path(candidate) / 'build-tools').glob('*'))
            versions = [path for path in versions if (path / 'apksigner').is_file()]
            if versions:
                return max(versions, key=lambda p: tuple(int(x) for x in re.findall(r'\d+', p.name)))
    raise ValueError('Android build-tools not found; set ANDROID_HOME.')


def private_file(path, value):
    with path.open('x', encoding='utf-8') as stream:
        os.chmod(path, 0o600)
        stream.write(value)


def key_certificate(keystore, alias, password_file):
    result = run([java_tool('keytool'), '-exportcert', '-keystore', keystore, '-alias', alias,
                  '-storepass:file', password_file], capture=True)
    return hashlib.sha256(result.stdout).hexdigest()


def initialize(root, directory, legacy, legacy_password='freemytv', expected=LEGACY_SHA256):
    root, directory, legacy = root.resolve(), directory.resolve(), legacy.resolve()
    config = root / 'signing.local.json'
    if config.exists() or directory.exists():
        raise ValueError('Signing configuration/directory already exists. It will not be overwritten.')
    if not legacy.is_file():
        raise ValueError('Original signing keystore is missing; rotation requires the old private key.')
    directory.parent.mkdir(parents=True, exist_ok=True)
    apksigner = build_tools() / 'apksigner'
    with tempfile.TemporaryDirectory(prefix='.tvos-signing-', dir=directory.parent) as tmp:
        stage = Path(tmp)
        os.chmod(stage, 0o700)
        old_key = stage / 'legacy.keystore'
        shutil.copyfile(legacy, old_key)
        os.chmod(old_key, 0o600)
        private_file(stage / 'legacy-password.txt', legacy_password + '\n')
        old_hash = key_certificate(old_key, 'freemytv', stage / 'legacy-password.txt')
        if old_hash != expected:
            raise ValueError('Old certificate does not match the reviewed tvOS 1.0.x certificate. No key was replaced.')
        private_file(stage / 'release-password.txt', secrets.token_urlsafe(32) + '\n')
        run([java_tool('keytool'), '-genkeypair', '-storetype', 'PKCS12', '-keystore', stage / 'tvos-release.p12',
             '-alias', 'tvos-release', '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000',
             '-storepass:file', stage / 'release-password.txt', '-keypass:file', stage / 'release-password.txt',
             '-dname', 'CN=tvOS Release,OU=tvOS'])
        os.chmod(stage / 'tvos-release.p12', 0o600)
        run([apksigner, 'rotate', '--out', stage / 'tvos.lineage',
             '--old-signer', '--ks', old_key, '--ks-key-alias', 'freemytv', '--ks-pass', 'file:' + str(stage / 'legacy-password.txt'),
             '--new-signer', '--ks', stage / 'tvos-release.p12', '--ks-key-alias', 'tvos-release',
             '--ks-pass', 'file:' + str(stage / 'release-password.txt')])
        new_hash = key_certificate(stage / 'tvos-release.p12', 'tvos-release', stage / 'release-password.txt')
        private_file(stage / 'certificates.json', json.dumps({'legacy_sha256': old_hash, 'release_sha256': new_hash,
                                                             'rotation_min_sdk': 28}, indent=2) + '\n')
        stage.rename(directory)
    private_file(config, json.dumps({'directory': str(directory)}, indent=2) + '\n')
    print('Signing configuration created. Back up the signing directory securely:', directory)
    print('New public certificate SHA-256:', new_hash)
    if legacy == root / 'freemytv.keystore':
        legacy.rename(directory / 'legacy-original.keystore')
        os.chmod(directory / 'legacy-original.keystore', 0o600)
        print('Original project key moved into the protected signing directory.')
    else:
        print('The supplied external legacy keystore has not been moved.')
    return directory


def sign(root, bt, source, output, mode=None):
    root, bt = root.resolve(), bt.resolve()
    version = ET.parse(root / 'app/AndroidManifest.xml').getroot().attrib['{http://schemas.android.com/apk/res/android}versionName']
    mode = mode or os.environ.get('TVOS_SIGNING_MODE') or ('legacy' if version == '1.0.4' else 'release')
    args = [bt / 'apksigner', 'sign', '--min-sdk-version', '28', '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'false' if mode == 'release' else 'true', '--v3-signing-enabled', 'true']
    if mode == 'ci':
        if os.environ.get('CI') != 'true':
            raise ValueError('CI signing is restricted to CI=true; it is not a release key.')
        directory = root / 'out/ci-signing'
        directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        private_file(directory / 'password.txt', secrets.token_urlsafe(32) + '\n')
        run([java_tool('keytool'), '-genkeypair', '-keystore', directory / 'disposable.p12', '-storetype', 'PKCS12',
             '-alias', 'ci-only', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '2',
             '-storepass:file', directory / 'password.txt', '-dname', 'CN=Disposable CI'])
        args += ['--ks', directory / 'disposable.p12', '--ks-key-alias', 'ci-only', '--ks-pass', 'file:' + str(directory / 'password.txt')]
    elif mode in ('release', 'legacy'):
        config = root / 'signing.local.json'
        if not config.is_file():
            raise ValueError('Run python3 tools/signing.py init on your Mac before building. No signing key is auto-generated.')
        directory = Path(json.loads(config.read_text())['directory']).expanduser().resolve()
        if mode == 'legacy':
            if version != '1.0.4':
                raise ValueError('Legacy signing is reserved for the 1.0.4 bridge.')
            if key_certificate(directory / 'legacy.keystore', 'freemytv', directory / 'legacy-password.txt') != LEGACY_SHA256:
                raise ValueError('Legacy certificate does not match released tvOS.')
            args += ['--ks', directory / 'legacy.keystore', '--ks-key-alias', 'freemytv',
                     '--ks-pass', 'file:' + str(directory / 'legacy-password.txt')]
        else:
            if version == '1.0.4':
                raise ValueError('The 1.0.4 bridge must use legacy signing.')
            args += ['--ks', directory / 'tvos-release.p12', '--ks-key-alias', 'tvos-release',
                     '--ks-pass', 'file:' + str(directory / 'release-password.txt'),
                     '--lineage', directory / 'tvos.lineage', '--rotation-min-sdk-version', '28']
    else:
        raise ValueError('Unknown TVOS_SIGNING_MODE.')
    args += ['--out', output, source]
    run(args)
    for sdk in (28, 30, 33, 34):
        run([bt / 'apksigner', 'verify', '--min-sdk-version', str(sdk), '--max-sdk-version', str(sdk), output])
    print('APK signatures verified for Android API 28, 30, 33 and 34; signing mode:', mode)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    init = sub.add_parser('init')
    init.add_argument('--directory', type=Path, default=Path.home() / '.local/share/tvos-signing')
    init.add_argument('--legacy-keystore', type=Path, default=ROOT / 'freemytv.keystore')
    init.add_argument('--legacy-password-file', type=Path)
    signing = sub.add_parser('sign')
    signing.add_argument('build_tools', type=Path)
    signing.add_argument('input', type=Path)
    signing.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'init':
            password = args.legacy_password_file.read_text().splitlines()[0] if args.legacy_password_file else 'freemytv'
            initialize(ROOT, args.directory, args.legacy_keystore, password)
        else:
            sign(ROOT, args.build_tools, args.input, args.output)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit('Signing failed: ' + str(error)) from error
