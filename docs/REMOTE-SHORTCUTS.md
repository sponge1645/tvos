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
Arrows, Back, Home, power and volume are reserved and pass unchanged to Android,
including repeats/releases. While the accessibility filter is active, VolumeKeyRepeat
adds missing volume steps after the normal hold timeout plus 100 ms, using
AudioManager on the suggested stream. Short presses add no steps. Any real
repeat disables the fallback for that press, so normal remotes keep their
firmware repeat rate. Release, another key, screen-off, interruption or service
teardown cancels it; a 30-second deadline bounds a lost release. Volume events
are never consumed or entered into the assignment engine. No audio listener
is installed.

## Validation

Key-filter tests execute capture/release matching, reserved-key passthrough,
Home/global scope, volume fallback, native-repeat suppression and cleanup.
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
