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
