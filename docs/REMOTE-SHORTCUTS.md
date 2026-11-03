# Remote assignments — branch 1.1.2

## Current input path

Home handles assignments locally. Across-app assignments use `RemoteInputMethod`,
a standard Android input method, instead of an accessibility key filter. Enable
**tvOS button input** in Android keyboard settings, then select it as the current
keyboard. Enable Gboard (or another text keyboard) as well, but leave tvOS selected.
When an app requests the keyboard, tvOS starts a temporary foreground controller.
The controller preflights local ADB and the IME visibility dump, then selects the
text keyboard. Once the keyboard has been hidden for at least 700 ms it restores
tvOS, provided the user has not selected a different keyboard in the meantime.
A one-second binding grace period ignores the old IME's visibility during startup.

Choose the text keyboard under Button shortcuts → Text keyboard. Enabled Gboard
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

The old tvOS accessibility service may be disabled; it only contains the optional
CHiQ cold-boot guard and never requests keys. Setup and Home-mode jobs do not
enable it automatically.

Remotes and Devices → Button shortcuts → Add a button. Press the physical key,
then select Home-only or Across apps and independent 1×/2×/3× actions. Existing
Netflix/YouTube/Prime/TCL/MI choices and saved custom assignments are preserved.
Bindings match Android code, Linux scan code and remote name, with up to 32 keys.
Tap interval is 300 ms. Single-only assignments execute on release. Double/triple
assignments delay the single action. Original OK is supported on Home only.
Arrows, Back, Home, power and volume are reserved and pass unchanged to Android,
including repeats/releases. There is no volume repeat generator or audio listener.

The IME ignores tvOS windows so Home never handles a key twice and the capture
dialog receives originals. It cancels pending sequences when input targets change.
Applications and firmware can consume keys before IME dispatch; this is not a
universal system input filter. In particular, firmware app-launch keys and
windows that bypass the IME require physical-device validation.

## Validation

148 JVM test scripts pass, including actual controller-worker tests with simulated
ADB failure/rollback, manual selection and foreground cleanup; policy tests cover
binding/close debounce, malformed firmware output, show timeout, screen-off and
atomic target storage. The existing tap and remote-adapter tests still pass.
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
