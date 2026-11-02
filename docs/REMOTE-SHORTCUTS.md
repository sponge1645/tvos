# Remote testing — 1.1.2 (1B161)

Not a published release. The 1.1.1 branch/tag and production OTA are untouched.

## Settings

Remotes and Devices → Button shortcuts → Add a button. Press the physical key,
then select Home-only or Across apps and independent 1×/2×/3× actions. Bindings
use Android code, Linux scan code and remote name; per-sequence device IDs keep
identically named input devices from sharing a sequence. Up to 32 bindings.
Default is no new mappings. Legacy Netflix/YouTube/Prime/TCL/MI assignments stay.

Example: capture OK, keep Home-only and Original OK action for 1×; assign Toggle
INCY VPN for 3×. A tap sequence waits up to 300 ms between taps; single-only
bindings execute on release without that wait. Incomplete unassigned sequences
fall back to the single action once. Explicit Do nothing differs from unassigned.
VPN controls INCY only, through the existing guarded broadcast/ADB fallback.
The global controller never injects original keys. Consequently global OK is
rejected; arrows, Back, Home, power and volume are reserved. Across-app mappings
fully consume the selected key and require the enabled tvOS accessibility service.
Keys intercepted earlier by firmware cannot be learned or remapped by this layer.

Only Home can replay an original OK locally. Configured OK holding starts native
Home handling after 500 ms, so its long-press menu appears later. Unconfigured
keys retain old behavior. Pausing, screen-off, window/settings changes and another
key cancel pending actions; native held-key cancellation uses a cancelled release.
No press or repeat reads storage; reload is on resume or protected notification.

## Volume experiment

Existing ADB helper remains the default. Select System — experimental, use the
one-time grant action, enable tvOS accessibility and the volume fix toggle. The
grant requires authorized local ADB and declared development permission
SET_VOLUME_KEY_LONG_PRESS_LISTENER. It does not grant DUMP, change hidden_api_policy
or disable ADB automatically (ADB may be needed for other tvOS features).

The hidden MediaSessionManager listener is registered through reflection. A grant
or successful registration does not prove the firmware will deliver events.
Failure is surfaced through the existing volume status; the user explicitly
selects the ADB helper again if necessary. System mode never starts the helper
in the background. Native short presses stay with Android; long-press callbacks
start direct suggested-stream steps at 85 ms, bounded to 6 seconds / 100 steps.
Release, cancellation, screen-off, interruption and destruction stop pending work;
stale callbacks remain invalid after re-registration. No Audio/Input dumps or
polling are added. Audio routing/CEC and Android 9/OEM support need real TV tests.

## Provenance and validation

The feature review of techgenius1/tvOS-Launcher inspired the configurable taps
and privileged-listener experiment. Implemented in our components; no source files
from that project were imported. See that project's Apache-2.0 LICENSE/NOTICE
before future code reuse.

Tests execute the actual sequence/store, Android shortcut adapter on SDK28/33,
privileged listener and existing volume lifecycle adapter. They cover identity,
1×/2×/3×, cancellation, native hold, original suppression, malformed storage,
permission/registration failure, callback generations and explicit ADB fallback.
Strict APK/D8/resources/signing checks run in CI. Physical TV and HDMI-CEC testing
are still required; no universal compatibility or performance gain is claimed.
