# Remote assignments — branch 1.2

## Current input path

Home handles assignments locally. An optional accessibility key filter receives
allowed app buttons before normal window/IME delivery. Enable **tvOS** in
Android Accessibility (Input Settings > Enable button interception). It returns
false for navigation, volume, power, OK, ordinary controller keys and unassigned
buttons. Global bindings use the existing tap-sequence engine; Home-only
bindings are intercepted only on Home. The IME remains available as an
alternative on firmware without early filtering.

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

Across-app assignments can alternatively use `RemoteInputMethod`. Enable
**tvOS button input** in Android keyboard settings, then select it as the current
keyboard. Its Android label is **tvOS Keyboard**. The IME metadata declares a
standard keyboard subtype and no settingsActivity link: tapping its Android
keyboard entry must not redirect into the button editor. Enable Gboard (or another
text keyboard) as well, but leave tvOS selected.
When an app requests the keyboard, tvOS starts a temporary foreground controller.
The controller preflights local ADB and the IME visibility dump, then selects the
text keyboard. Once the keyboard has been hidden for at least 700 ms it restores
tvOS, provided the user has not selected a different keyboard in the meantime.
A one-second binding grace period ignores the old IME's visibility during startup.

Choose the text keyboard under Remotes and Devices → Input Settings → Text keyboard. Enabled Gboard
is preferred automatically when there is no saved choice. The setting is an atomic
file, read afresh across Home and input-method processes. The original handmade
text keyboard is removed; a small connecting/picker panel is the fallback when
ADB or the target keyboard is unavailable. Hardware-key assignments themselves
do not need ADB; automatic text-keyboard delegation/return does.

The controller is foreground only during a text session and polls a filtered
`mInputShown` state every 400 ms. It never stores or logs typed text. It stops on
manual keyboard changes, screen-off, failed show, unsupported firmware or after
30 minutes. The time limit leaves visible text input alone. A notification, where
available, allows an explicit return to tvOS. ADB failure after switching triggers
one rollback connection attempt. Unexpected process death can leave the text
keyboard selected; select tvOS again manually in that case. No automatic service
restart takes ownership of a manually selected keyboard. This remains a TV trial.

The optional tvOS accessibility service must be enabled manually for early
interception. Setup and Home-mode jobs do not enable it automatically.

Remotes and Devices opens a native tvOS-style editor: the shared settings gradient,
logo tile, focus capsules, short row labels and checkmark pickers. The editor uses
the same SettingsListRenderer, approved row geometry, 36 px typography, colors,
edge fades and description renderer as the main Settings screens. Saved buttons
appear first, followed by Add a Button and Input Settings. Adding a button learns
one matching physical DOWN/UP pair; capture never runs existing assignments.
The editor shows Works (Everywhere/Home), Single Press, Double Press, Triple Press
and a direct Delete Button Configuration action. The default for new buttons is Everywhere; OK is
always Home-only and initially keeps its original action. Existing scope choices
are preserved. Global bindings now also work inside the
main Settings activity and the editor outside capture mode. Both local adapters
cancel on pause/focus loss and unregister on destruction. The IME continues to
skip tvOS windows to avoid duplicate execution.

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

The IME ignores tvOS windows so Home never handles a key twice and the capture
dialog receives originals. It cancels pending sequences when input targets change.
Applications and firmware can consume keys before IME dispatch; this is not a
universal system input filter. In particular, firmware app-launch keys and
windows that bypass the IME require physical-device validation.

## Validation

150 JVM test scripts pass, including actual controller-worker tests with simulated
ADB failure/rollback, manual selection and foreground cleanup; policy tests cover
binding/close debounce, malformed firmware output, show timeout, screen-off and
atomic target storage. Editor tests execute capture/release matching, reserved keys,
Home-only OK, default global scope, duplicate preservation, gesture assignment and
direct deletion. Settings lifecycle tests execute global dispatch,
navigation fallback and adapter cleanup. Existing tap/remote tests still pass.
Java source compilation against Android API 35 passes. Full APK assembly and
real-TV testing were not available in the implementation environment.

On TV, keep local ADB enabled and authorized. Enable Gboard, leave **tvOS button
input** selected and open a search field. Verify that Gboard appears, Back/Done
closes it and assignments resume about a second later. Also test changing fields,
manual keyboard selection, volume hold/release during playback and standby. If
handoff fails, the app shows a message; use the keyboard picker to recover.

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
