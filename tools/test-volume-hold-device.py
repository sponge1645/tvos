#!/usr/bin/env python3
"""Build and run a temporary shell-owned volume experiment on the connected TV."""
import argparse
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import threading
import zipfile


def version(path):
    return tuple(int(n) for n in re.findall(r'\d+', path.name))


def android_tools():
    roots = [os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
             str(Path.home()/'Library/Android/sdk'), str(Path.home()/'Android/Sdk'),
             '/opt/homebrew/share/android-commandlinetools', '/usr/local/share/android-commandlinetools',
             '/usr/lib/android-sdk']
    for root in roots:
        if not root:
            continue
        sdk = Path(root)
        tools = sorted((p for p in (sdk/'build-tools').glob('*') if (p/'d8').is_file()), key=version)
        platforms = sorted((sdk/'platforms').glob('android-*'), key=version)
        jars = [p/'android.jar' for p in platforms if (p/'android.jar').is_file()]
        if tools and jars:
            return tools[-1]/'d8', jars[-1]
    raise RuntimeError('Android SDK not found. Use the same ANDROID_HOME as for build.sh.')


def compile_sources(java, output, sources):
    # New JDKs warn about targeting Java 8. Keep code warnings fatal, but the
    # intentional Android-compatible target must not make compilation fail.
    subprocess.run([java, 'com.sun.tools.javac.Main', '--release', '8',
                    '-Xlint:all,-options', '-Werror', '-d', str(output),
                    *map(str, sources)], check=True)


def build(output):
    d8, android_jar = android_tools()
    java = str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    if not java:
        raise RuntimeError('JDK 17+ is required, as for build.sh.')
    source = Path(__file__).resolve().parent/'volume-hold/VolumeHoldDevice.java'
    with tempfile.TemporaryDirectory(prefix='tvos-volume-build-') as directory:
        root = Path(directory)
        classes = root/'classes'
        classes.mkdir()
        compile_sources(java, classes, [source])
        jar = root/'input.jar'
        with zipfile.ZipFile(jar, 'w') as archive:
            for path in classes.rglob('*.class'):
                archive.write(path, path.relative_to(classes))
        subprocess.run([str(d8), '--release', '--min-api', '28', '--lib', str(android_jar),
                        '--output', str(output), str(jar)], check=True)
        with zipfile.ZipFile(output) as archive:
            if not archive.read('classes.dex').startswith(b'dex\n'):
                raise RuntimeError('Invalid helper DEX')
    print('Built ' + str(output), flush=True)


def adb(*args):
    return subprocess.run(['adb', *args], check=True, capture_output=True, text=True, timeout=10).stdout.strip()


def run(jar, audio=False):
    enabled = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
    if 'io.tvos/io.tvos.RemoteKeyService' not in enabled.split(':'):
        raise RuntimeError('Keep the tvOS accessibility service enabled for this test.')
    remote = adb('shell', 'mktemp', '-d', '/data/local/tmp/tvos-volume.XXXXXX')
    if not re.fullmatch(r'/data/local/tmp/tvos-volume\.[A-Za-z0-9]+', remote):
        raise RuntimeError('Unexpected remote temporary path: ' + repr(remote))
    process = None
    stopped = threading.Event()
    controls = None
    try:
        adb('push', str(jar), remote+'/helper.jar')
        command = 'CLASSPATH=' + shlex.quote(remote+'/helper.jar') + ' app_process /data/local/tmp io.tvos.tools.VolumeHoldDevice'
        if audio:
            command += ' audio'
        print('Service stays enabled. Hold + / - briefly. Ctrl+C stops; no APK or settings changes.', flush=True)
        process = subprocess.Popen(['adb', 'shell', '-T', command], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)

        def ping():
            try:
                while not stopped.is_set():
                    process.stdin.write('PING\n')
                    process.stdin.flush()
                    if stopped.wait(.5):
                        break
                if process.poll() is None:
                    process.stdin.write('STOP\n')
                    process.stdin.flush()
            except (OSError, ValueError):
                pass
            finally:
                process.stdin.close()

        controls = threading.Thread(target=ping, daemon=True)
        controls.start()
        for line in process.stdout:
            print(line, end='', flush=True)
        if process.wait() != 0:
            raise RuntimeError('Device helper failed; see output above. Do not disable accessibility.')
    except KeyboardInterrupt:
        print('\nStopping device helper...', flush=True)
    finally:
        stopped.set()
        if controls is not None:
            controls.join(timeout=2)
        if process is not None:
            try:
                process.wait(timeout=6)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            remaining = process.stdout.read()
            if remaining:
                print(remaining, end='', flush=True)
            process.stdout.close()
        try:
            adb('shell', 'rm', '-f', remote+'/helper.jar')
            adb('shell', 'rmdir', remote)
        except (OSError, subprocess.SubprocessError) as error:
            print('Temporary helper cleanup could not finish: ' + str(error), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build-only', action='store_true')
    parser.add_argument('--audio', action='store_true', help='experimental direct system audio path instead of key injection')
    parser.add_argument('--output', type=Path, help='helper .jar path for --build-only')
    args = parser.parse_args()
    if args.audio and args.build_only:
        parser.error('--audio is a runtime mode')
    if args.output and not args.build_only:
        parser.error('--output requires --build-only')
    with tempfile.TemporaryDirectory(prefix='tvos-volume-device-') as directory:
        output = args.output.resolve() if args.output else Path(directory)/'helper.jar'
        build(output)
        if not args.build_only:
            run(output, audio=args.audio)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error))
