#!/usr/bin/env python3
"""Temporary Mac/PC ADB experiment; does not install or modify the TV app."""
import argparse
import re
import subprocess
import threading
import time

EVENT = re.compile(r'(/dev/input/event\d+):\s+0001\s+(0072|0073)\s+(0000000[012])\s*$')


def parse_event(line):
    match = EVENT.search(line)
    if not match:
        return None
    return match[1], int(match[2], 16), int(match[3], 16)


class Hold:
    """Only schedule extra discrete presses; the initial native step is untouched."""
    def __init__(self):
        self.pressed = set()
        self.active = None
        self.started = self.due = 0.0
        self.count = 0
        self.blocked = False

    def event(self, device, code, value, now):
        key = device, code
        if value == 0:
            self.pressed.discard(key)
            if self.active == key:
                self.active = None
            if not self.pressed:
                self.blocked = False
            return
        if value == 2 or key in self.pressed:
            # Hardware repeats or duplicate DOWN: do not compete with native handling.
            self.active = None
            self.blocked = True
            return
        self.pressed.add(key)
        if self.blocked or len(self.pressed) != 1:
            self.active = None
            self.blocked = True
            return
        self.active = key
        self.started = now
        self.due = now + .5
        self.count = 0

    def take(self, now):
        if self.active is None:
            return None
        if now - self.started >= 4 or self.count >= 12:
            self.active = None
            self.blocked = True
            return None
        if now < self.due:
            return None
        self.count += 1
        self.due = now + .15  # No catch-up/backlog after a slow shell invocation.
        return 24 if self.active[1] == 115 else 25


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--observe-only', action='store_true', help='show raw volume events without adding steps')
    args = parser.parse_args()
    adb = ['adb']
    subprocess.run(adb + ['get-state'], check=True, capture_output=True, text=True, timeout=5)
    enabled = subprocess.run(adb + ['shell', 'settings', 'get', 'secure', 'enabled_accessibility_services'],
                             check=True, capture_output=True, text=True, timeout=5).stdout
    if 'io.tvos/io.tvos.RemoteKeyService' not in enabled.strip().split(':'):
        raise RuntimeError('Enable the tvOS accessibility service before this experiment.')
    print('tvOS service is enabled. Test only on a device where native volume hold is broken.', flush=True)
    print('Hold +, release, then hold -. Ctrl+C stops. No extra first step; max 4 s / 12 extra steps per hold.', flush=True)
    print('This host prototype measures shell overhead; one already submitted key pair can finish after release.', flush=True)
    if args.observe_only:
        print('OBSERVE ONLY: no key injection.', flush=True)
    process = subprocess.Popen(adb + ['shell', 'getevent', '-t'], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)
    state = Hold()
    lock = threading.Lock()
    stopped = threading.Event()

    def read_events():
        try:
            for line in process.stdout:
                event = parse_event(line)
                if event is None:
                    if 'Permission denied' in line or 'could not' in line.lower():
                        print('INPUT ERROR: ' + line.strip(), flush=True)
                        break
                    continue
                device, code, value = event
                with lock:
                    state.event(device, code, value, time.monotonic())
                print('RAW {} {} {}'.format(device, '+' if code == 115 else '-',
                                           {0: 'UP', 1: 'DOWN', 2: 'REPEAT'}[value]), flush=True)
        finally:
            stopped.set()

    reader = threading.Thread(target=read_events, daemon=True)
    reader.start()
    try:
        while not stopped.wait(.01):
            if args.observe_only:
                continue
            with lock:
                key = state.take(time.monotonic())
            if key is None or stopped.is_set():
                continue
            started = time.monotonic()
            # Each command is a complete DOWN/UP pair, never an indefinitely held key.
            result = subprocess.run(adb + ['shell', 'input', 'keyevent', str(key)],
                                    capture_output=True, text=True, timeout=4)
            if result.returncode:
                raise RuntimeError('Key injection failed: ' + result.stderr.strip() + result.stdout.strip())
            print('EXTRA {} command={}ms'.format('+' if key == 24 else '-',
                                               round((time.monotonic() - started) * 1000)), flush=True)
        raise RuntimeError('Input stream ended; no further steps will be sent.')
    except KeyboardInterrupt:
        print('\nStopped.', flush=True)
    finally:
        stopped.set()
        process.terminate()
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        reader.join(timeout=2)
        process.stdout.close()


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.SubprocessError, OSError) as error:
        raise SystemExit(str(error))
