# Remote assignments — branch 1.1.2

## Current input path

Home handles assignments locally. Across-app assignments use `RemoteInputMethod`,
a standard Android input method, instead of an accessibility key filter. Enable
**tvOS button input** in Android keyboard settings, then select it as the current
keyboard. The input method includes a small Russian/English keyboard for text
entry and a keyboard-picker button to return to another keyboard. Selecting a
different keyboard disables across-app assignments; Home-only assignments remain.
No accessibility permission, ADB helper, privileged audio listener or root is
required for this input path. The old tvOS accessibility service may be disabled;
it now only contains the optional CHiQ cold-boot guard and never requests keys.
Setup and Home-mode jobs no longer enable it automatically.

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

JVM tests cover actual tap/storage logic, Home/global adapters and the IME routing
method: native volume/navigation passthrough, Home/capture exclusions, custom
assignment priority and legacy mapping fallback. Java source compilation against
Android API 35 passes. Full suite: 146 scripts pass. No APK assembly or real-TV
verification was available in the implementation environment.

On TV, disable the old tvOS accessibility service, select the new input method,
then verify native volume hold/release both on Home and during video playback.
Test one/two/three taps, text search, keyboard switching, standby and cold boot.
If an app-launch key never reaches the capture dialog or still starts its vendor
app, record the TV firmware and key; do not add synthetic volume repeats.
