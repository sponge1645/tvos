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
