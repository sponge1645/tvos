# Remote assignments — branch 1.2

## Current input path

Home handles assignments locally. An optional accessibility key filter receives
allowed app buttons before normal window/IME delivery. Enable **tvOS** in
Android Accessibility (Enable button interception). It returns
false for navigation, volume, power, OK, ordinary controller keys and unassigned
buttons. Global bindings use the existing tap-sequence engine; Home-only
bindings are intercepted only on Home.

During learning, Settings requests a signature-protected capture lease, renewed
every 2 seconds with a maximum 5-second expiry. Forwarded KeyEvents carry a
session UUID and are accepted only by the active/resumed capture UI. Leaving
the screen or losing focus ends capture; late release events are swallowed once
without executing a binding. This also survives a settings-process failure via
the lease timeout. No screen-content retrieval permission is requested.

On DEXP/MTK, Netflix's 265/scan161 and Prime's scan500 reach vendor global-key
handlers instead of the normal window. Accessibility interception was confirmed
on the tested DEXP. The TV initially rejected enabling the service with a
misleading device-admin-policy log despite having no active administrators;
allowing ACCESS_RESTRICTED_SETTINGS for io.tvos resolved it. Other firmware
still requires device validation. Capture diagnostics log numeric
code/scan/source/action only.

The old tvOS input method, keyboard handoff service and keyboard setup rows are
removed. Text input uses the keyboard selected in Android. tvOS never changes
the system input method. Existing button assignments are preserved.

The optional tvOS accessibility service must be enabled manually for early
interception. Setup and Home-mode jobs do not enable it automatically.

Remotes and Devices opens a native tvOS-style editor: the shared settings gradient,
logo tile, focus capsules, short row labels and checkmark pickers. The editor uses
the same SettingsListRenderer, approved row geometry, 36 px typography, colors,
edge fades and description renderer as the main Settings screens. Saved buttons
appear first, followed by Add a Button and Enable button interception. Adding a button learns
one matching physical DOWN/UP pair; capture never runs existing assignments.
The editor shows Works (Everywhere/Home), Single Press, Double Press, Triple Press
and a direct Delete Button Configuration action. The default for new buttons is Everywhere; OK is
always Home-only and initially keeps its original action. Existing scope choices
are preserved. Global bindings now also work inside the
main Settings activity and the editor outside capture mode. Both local adapters
cancel on pause/focus loss and unregister on destruction. Accessibility consumes assigned events before the local adapters to avoid duplicates.

The old App Buttons section, manufacturer preset dispatcher and stored preset
choices are retired. Only learned configurations dispatch actions. Button names
may still recognize printed Netflix/YouTube/Prime/TCL/MI labels. The action list
has one Not Selected choice; legacy empty/none values display the same label.

Bindings match Android code, Linux scan code and remote name, with up to 32 keys.
Tap interval is 300 ms. Single-only assignments execute on release. Double/triple
assignments delay the single action. Original OK is supported on Home only.
Arrows, Back, Home, power and volume are reserved for native Android handling.
The accessibility callback no longer generates audio-volume steps and has no
manufacturer-specific volume mappings.

## Volume hold through local ADB

While the accessibility service is connected, AdbVolumeKeys owns one local ADB
shell running VolumeRelay from the installed APK via app_process. The relay
reads getevent directly and passes synthetic standard Volume Up/Down events to
Android's input service; it never guesses an audio stream or sets absolute volume.
The original physical press remains native. After 600 ms without native hardware
repeats it emits one paired repeat every 80 ms until the physical release.
Hardware repeats, another physical key or a 30-second lost-release deadline
cancel the synthetic repeat. Idle timer polling is 200 ms, active polling 20 ms.

Linux volume scans 115/114 work automatically. Other remotes use **Set Up Volume
Buttons** in the button editor: press/release Up, then Down after each prompt.
Prompts wait for acknowledgement from the relay. Mapping uses input-device name
and Linux scan rather than /dev/input/eventN, so event numbering can change.
Only the active signature-protected capture lease can save a mapping. Leaving
Settings stops learning. Stored mappings survive app updates; no vendor names
or letter-key mappings are baked into the app.

The relay reads owner heartbeats and exits on stdin EOF or a 2-second heartbeat
timeout. Service destruction, interruption or screen-off closes its ADB socket;
screen-on reconnects. Connection failures retry after 5 seconds. Early key filtering is enabled only
after relay READY; loss of the relay disables it so native volume autorepeat
remains available while ADB is down. The relay uses
shell input-injection privileges, no root and no keyboard/IME replacement. Local
ADB and the firmware's getevent/input-injection permissions remain prerequisites;
real-TV behavior still requires validation. No raw key stream is logged or saved.
Only READY/errors and the two explicitly learned button identities leave the relay.

## Validation

Key-filter tests execute capture/release matching, reserved-key passthrough,
Home/global scope and cleanup. Raw-volume tests cover standard/custom devices,
paired calibration, hold/release, native-repeat suppression and deadlines.
Process tests execute the actual relay with simulated getevent/injection APIs,
including owner EOF and heartbeat loss.
Editor and Settings tests cover learning, gestures, focus and lifecycle.
The APK builds against Android API 34. Netflix and Prime interception was
confirmed on the tested DEXP; volume fallback still requires a TV check.

On TV, enable tvOS in Accessibility and check assigned buttons both on Home
and in another app. Verify native text input, volume hold/release during
playback and after standby. tvOS must not appear in Android's keyboard list.

## Unified Home header

Date/weather and recommendation sections share the corner buttons' top edge and
61.2 px resting height in the 1920 px reference layout. Date/weather and sections
have no resting glass or outline; the same white focus capsule, shadow and 5%
focus growth are used for both. Corner controls retain their existing treatment.
Section widths fit between the optional date/weather group and enabled corner
controls; long labels shrink within their cells instead of overlapping controls.
Left/right navigation joins weather to the first visible section; Down from weather
reaches the posters or Dock. Search headings remain below the common control row.
Geometry and render tests cover 720p/1080p/4K, all 64 corner-button masks, weather
presence, constrained labels, plain resting surfaces and focused capsules.
