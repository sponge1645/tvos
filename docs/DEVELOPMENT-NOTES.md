# Deferred work

## Volume-key hold with accessibility enabled — 2026-11-02

The user reports that holding volume +/− changes volume only once while tvOS is
enabled in Accessibility; repeat works after disabling the service. The user
explicitly deferred this issue until structural separation is finished.

`RemoteKeyService` requests global key filtering for app-button assignments.
`RemoteKeys` normally returns false for volume keys. The exact failure point is
unconfirmed; do not attribute it to a proven firmware bug yet. First compare key
down/up/repeat events with the service enabled and disabled. If a repeat fallback
is needed, preserve native repeats, stop on release/service shutdown and avoid
applying volume twice. No volume-handling change is part of this refactoring pass.

## Investigation resumed — 1B156

On the same TV, both raw `getevent` traces contain only DOWN/UP; the user confirms
hold works with tvOS Accessibility disabled and changes only one step when enabled.
This establishes a service/filter-path interaction, not the exact Android failure.
RemoteKeyService now has opt-in Android-boundary diagnostics:

```sh
adb shell setprop log.tag.tvOS-Volume DEBUG
adb logcat -v time -s tvOS-Volume:D
# Hold each volume key, release, then stop logcat with Ctrl+C.
adb shell setprop log.tag.tvOS-Volume INFO
```

Log fields include key/action/repeat, down/event times, device/scan/flags and the
actual handled result. Unrelated keys are not logged. Logging is off by default;
no volume adjustment, event injection or repeat scheduling has been introduced.

## Expanded boundary trace — 1B157

The installed APK contains the trace tag. A DEBUG shell probe is visible, but no
volume lines appear; dumpsys lists tvOS as bound/enabled without a crashed service.
This does not establish whether volume has nonstandard Android codes or bypasses
the callback. Extend the same opt-in tag to every key and service lifecycle.
Set DEBUG before disabling/re-enabling the tvOS accessibility service to capture
`CONNECTED build=157 flags=...`, then press arrows and hold volume +/−. Include
source/action/repeat metadata and preserve the filter return value. Disable the
trace afterward with INFO. No repeat fallback is implemented before this evidence.

## Android 13 source investigation — 2026-11-02

Observed with 1B157: `CONNECTED build=157 flags=32`; DPAD_DOWN (20), DPAD_UP
(19), BACK (4) reach RemoteKeyService and return handled=false. No volume events
appear. The user confirms reproduction on both a physical TV and an Android TV
13 emulator (SDK 33, google/sdk_google_atv_x86/generic_x86_arm). dumpsys input:
InputFilterEnabled=true, KeyRepeatTimeout=400ms, KeyRepeatDelay=50ms. Raw device
traces have only volume DOWN/UP in both accessibility states. HandlesKeyRepeat=false
means the mapper/device does not supply repeats; it is not proof of a disabled
system repeat timer.

Android 13 AOSP mechanism consistent with those observations:

1. InputDispatcher.notifyKey sends events through the enabled input filter before
   enqueueing them for normal dispatch/repeat synthesis.
2. KeyboardInterceptor.getEventDelay calls the window policy specifically for
   VOLUME_UP/DOWN. A negative result consumes the event without notifying services.
3. PhoneWindowManager.interceptKeyBeforeDispatching handles TV-routed volume via
   dispatchDirectAudioEvent and returns key_consumed (-1). Thus the audio step can
   occur before a service sees the key; the consumed key is not returned to the
   normal dispatcher path that starts repeat synthesis.

Sources inspected (exact Android 13 release for framework Java):
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/services/accessibility/java/com/android/server/accessibility/KeyboardInterceptor.java
- https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/services/core/java/com/android/server/policy/PhoneWindowManager.java
- https://github.com/LineageOS/android_frameworks_native/blob/lineage-20.0/services/inputflinger/dispatcher/InputDispatcher.cpp
  (Android 13-derived native source, not a verified dump of the user's TV firmware.)

No product workaround has been applied. A service-side timer cannot start without
a DOWN event. Clearing FLAG_REQUEST_FILTER_KEY_EVENTS globally removes our global
app-key assignments, including MI; HomeActivity's local handling remains but does
not replace global handling. Do not silently introduce this functional tradeoff.
The public accessibility flag does not configure individual key exclusions.

A possible alternative is a separate privileged/shell input backend through the
existing on-device ADB connection, leaving accessibility key filtering disabled.
This requires a prototype validating input-device identity/hotplug, shell permissions,
long-lived connection/reconnection and process lifetime on the actual TV. A raw
observer alone cannot consume the factory MI action, so preserving assigned-key
behavior requires an additional verified mechanism; do not claim this alternative
already preserves MI. Avoid adding volume broadcasts/toggle timers as a claimed
fix: volume changes do not identify key release and can originate in other apps,
and changing the filter resets dispatcher input state. Compatibility remains open.

## Volume-only ADB experiment (no APK change)

Keep the existing accessibility app-key assignments enabled. Instead of replacing
that backend, observe only raw EV_KEY volume DOWN/UP through ADB and add missing
volume steps after a hold delay. `tools/test-volume-hold.py` is a temporary host-side
experiment, not a background TV service or a universal Android 9+ fix.

Run with Python 3 and the ordinary `adb` on the Mac/PC, one connected device. It
requires tvOS accessibility to be enabled. No installation/settings changes or
root are performed. `--observe-only` reads without injecting. The active mode
sends complete `adb shell input keyevent 24/25` pairs after 500 ms, at most 12 extra
steps / 4 seconds per hold. The initial physical step remains native. Release,
raw repeats/duplicate downs, ambiguous simultaneous keys and input stream loss
stop new steps. A command already in flight can finish after release; this host
prototype logs per-command time and deliberately does not catch up missed ticks.
Ctrl+C closes the reader; no persistent repeat helper is installed. Retain this
latency limitation when interpreting the trial.

Test only on a device where the enabled key filter demonstrably breaks native
hold. Raw repeat detection cannot detect repeats synthesized later by Android;
therefore do not automatically enable this on devices with working native repeat.
If volume accelerates unexpectedly, stop the experiment. Verify +/−, short taps,
release, the selected app buttons, sleep/disconnection and external audio routing
before implementing an opt-in on-device version. Reading input devices and sending
injected volume keys may vary by firmware even when the Android APIs support 9+.
The scheduler/parser and actual CLI transport are covered with deterministic
clock checks and a fake adb. A real-TV run is still required.

## On-device persistent helper prototype

The user confirmed the host experiment changes volume on hold and retains app-key
assignments. Its per-step shell command cost was 128–410 ms, visibly slower than
native repeat. `tools/test-volume-hold-device.py` now builds a standalone Java-8
DEX jar (Android min API 28), pushes it to a unique /data/local/tmp directory and
runs one shell-owned app_process. It leaves the APK, assignments, accessibility
flags and persistent settings unchanged. The Mac needs the same JDK/Android SDK
as build.sh; run the script from a complete source snapshot because it compiles
`tools/volume-hold/VolumeHoldDevice.java`.

The device process reads getevent and injects completed volume DOWN/UP pairs via
InputManager (Android 9-shaped API) or InputManagerGlobal (Android 14-shaped API).
The Java reflection entry points were checked against Android 9 and 14 source;
this is not proof of compatibility with every OEM hidden-API/SELinux policy.
It starts extras after 400 ms, targets 50 ms intervals, and serializes injections
with WAIT_FOR_FINISH rather than queuing asynchronous catch-up events. Actual
injection time is printed; no latency improvement is claimed before device tests.
The native initial step is unchanged. A pair already submitted can finish after
physical release. Raw repeats/duplicate downs and simultaneous volume keys suspend
extras until release; the per-hold bounds are 4 seconds / 40 extra steps.

The host sends a heartbeat every 500 ms. EOF, STOP or a 3-second heartbeat gap
ends the device session and destroys its getevent child. Input-stream loss and
injection rejection also end the experiment. The runner sends STOP on Ctrl+C,
waits for shutdown and removes only its own jar/directory. There is no boot task,
service installation or permanent helper. If the ADB connection is lost, remote
file cleanup may need a later connection; the heartbeat still limits the session.
As with the first trial, use only on a device with confirmed broken native hold;
raw input cannot detect repeats generated later in Android. HDMI-CEC/external
routing, sleep/reconnect, OEM injection permissions and real timing remain device
validation requirements before integrating an opt-in app feature.

Tests compile and execute the real scheduler/reflection adapter with 9/14-shaped
fixtures, verify timing boundaries/device identity/release/repeats/caps/heartbeat
and release after rejected DOWN, and run the actual host runner with fake ADB to
check Ctrl+C STOP delivery and scoped cleanup. CI separately compiles the actual
standalone jar with D8/min-api 28. No signing identity or app display build changes.


## Prototype compiler compatibility correction

The user's device helper did not start: the host JDK rejected the intentional
Java 8 target's options warning under -Werror. Change only the options lint group
via `-Xlint:all,-options`; retain --release 8 and -Werror for source warnings.
The helper regression now invokes the actual runner compile function, and CI
runs it on JDK 25 as well as the existing JDK 17 suite/D8 build. This fixes host
compilation; it is not evidence that on-device injection or timing works yet.
