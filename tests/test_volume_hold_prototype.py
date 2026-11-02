"""Exercise the actual prototype parser and repeat scheduler with a controlled clock."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('volume_hold', Path('tools/test-volume-hold.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
assert m.parse_event('[ 610.490219] /dev/input/event2: 0001 0073 00000001') == ('/dev/input/event2', 115, 1)
assert m.parse_event('/dev/input/event10: 0001 0072 00000000\r\n') == ('/dev/input/event10', 114, 0)
for line in ['name: Xiaomi RC', '/dev/input/event2: 0004 0004 000c006c',
             '/dev/input/event2: 0001 0190 00000001', '/dev/input/event2: 0001 0073 00000003']:
    assert m.parse_event(line) is None
h = m.Hold()
h.event('a', 115, 1, 0)
assert h.take(.499) is None
assert h.take(.5) == 24
assert h.take(.5) is None
h.event('b', 115, 0, .51)  # A foreign release cannot end the active hold.
assert h.take(.66) == 24
h.event('a', 115, 0, .7)
assert h.take(1) is None
h.event('a', 114, 1, 1)
assert h.take(1.5) == 25
h.event('a', 114, 2, 1.6)
assert h.take(2) is None  # Real raw repeats surrender the entire hold.
h.event('a', 114, 0, 2)
h.event('a', 115, 1, 3)
h.event('b', 114, 1, 3.1)
assert h.take(3.6) is None
h.event('b', 114, 0, 3.7)
assert h.take(3.8) is None  # Ambiguity does not restart while the other key is down.
h.event('a', 115, 0, 3.9)
h.event('a', 115, 1, 4)
assert h.take(8) is None  # Lost-release watchdog.
h.event('a', 115, 0, 9)
h.event('a', 114, 1, 10)
for i in range(12):
    assert h.take(10.5 + i * .16) == 25
assert h.take(12.5) is None
h.event('a', 114, 0, 13)
h.event('a', 115, 1, 14)
assert h.take(16) == 24
assert h.take(16) is None  # Slow commands never cause catch-up bursts.
h.event('a', 115, 1, 16.1)
assert h.take(16.5) is None  # Duplicate DOWN is conservative, never restarts.
print('PASS: raw volume-only parsing, initial-step delay, release/device identity, native repeats, ambiguity, watchdog, step cap and no catch-up')

# Execute the command/reader lifecycle against an isolated adb substitute. No TV required.
import os
import subprocess
import sys
import tempfile
with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    adb = root / 'adb'
    adb.write_text('#!' + sys.executable + '''
import os,sys,time
from pathlib import Path
args=sys.argv[1:]
if args==['get-state']:
 print('device')
elif args==['shell','settings','get','secure','enabled_accessibility_services']:
 print('other/Service:io.tvos/io.tvos.RemoteKeyService')
elif args==['shell','getevent','-t']:
 print('[ 1.0] /dev/input/event2: 0001 0073 00000001',flush=True)
 if os.environ.get('VOLUME_TEST_OBSERVE')!='1':
  deadline=time.monotonic()+5
  while not Path(os.environ['VOLUME_TEST_COMMANDS']).exists():
   if time.monotonic()>deadline:raise SystemExit('No extra command received')
   time.sleep(.01)
 print('[ 1.8] /dev/input/event2: 0001 0073 00000000',flush=True)
 time.sleep(.6)
elif args==['shell','input','keyevent','24']:
 with Path(os.environ['VOLUME_TEST_COMMANDS']).open('a') as out:out.write('24\\n')
 time.sleep(.4)
else:
 raise SystemExit('Unexpected adb arguments: '+repr(args))
''')
    adb.chmod(0o755)
    env = dict(os.environ, PATH=str(root)+os.pathsep+os.environ['PATH'], VOLUME_TEST_COMMANDS=str(root/'commands'))
    for observe in [True, False]:
        env['VOLUME_TEST_OBSERVE'] = '1' if observe else '0'
        command = [sys.executable, 'tools/test-volume-hold.py'] + (['--observe-only'] if observe else [])
        result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=8)
        assert result.returncode == 1 and 'Input stream ended' in result.stderr, result
        assert 'RAW /dev/input/event2 + DOWN' in result.stdout and 'RAW /dev/input/event2 + UP' in result.stdout, result
        if observe:
            assert not (root/'commands').exists() and 'EXTRA' not in result.stdout, result
        else:
            assert (root/'commands').read_text().splitlines() == ['24'], result
            assert result.stdout.count('EXTRA + command=') == 1, result
print('PASS: real CLI with fake adb, colon-separated service detection, observe-only, discrete injection, stream-loss shutdown and output')
