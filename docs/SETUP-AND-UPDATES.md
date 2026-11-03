# Test build 1.1.2 (1B162): setup and automatic update offers

## Update behavior

Keep the existing auto_update key and opt-out behavior. Automatic checks now
produce an actual foreground offer with Download and install / Later; they no
longer start an unsolicited download followed by a toast. Home and Settings
subscribe only while resumed. An hourly timer revisits the check policy while
the foreground UI is active; successful checks remain throttled to 24 hours,
failed/initial states retry after an hour. No recurring timer is kept for opt-out
or paused Activities, and no global overlay interrupts another app.

Later/Back snoozes the same version for 24 hours; a different version can appear
immediately. Positive approval downloads with progress, then opens Android's
installer only while that UI remains in front. Leaving the Activity clears its
automatic-install intent; returning offers an explicit Install action again.
Downloads can continue in the background. Source-install permission remains an
Android-controlled step. APK limits, SHA-256, package/version, signer/lineage and
installer verification remain intact. No silent-install privilege is introduced.

A validated manifest is cached privately so a known offer survives process death.
It is parsed again and compared with the installed version on restore; corrupt,
oversize or already-installed offers are discarded. Restored metadata does not
mark an APK READY or bypass download/signature verification.

## Setup

Fresh/cleared-data installs enter a resumable wizard. Configured upgrades migrate
without reopening it. An unfinished wizard resumes after update/process restart.
General → Run setup again is available for existing installs. Back from Hello
defers the gate for this process session instead of reopening it in a loop.
Completion is written only on Finish/skip of the last step.

Steps: hello → привет; language (system suggestion, Russian/English catalog with
English fallback); weather city via IP/manual search with displayed result; optional
Wi-Fi location permission; automatic-update consent; Live TV application; Android
default-Home selection with actual status. Denial, unavailable network/app/role and
Set up later do not trap the user. The wizard never disables VPN or stock apps.
IP lookup is refused if VPN is active; the existing ordinary auto-region/Incy
pause policy is unchanged and its localized explanation is shown on that step.

TV default uses the existing tuner/vendor detection. A manual choice is stored
separately and opening TV falls back to auto detection if that app disappears.
Changing it invalidates Home preferences so the shortcut list refreshes. Home
selection uses ROLE_HOME where available or system Home settings; it does not
implicitly activate the existing full ADB-based tvOS mode or debloat packages.

The welcome uses the supplied vector stroke paths, not a script font. Greeting
animation stops on pause/page changes and honors Reduced Motion.

The reviewed techgenius1/tvOS-Launcher setup suggested optional permission steps,
repeat setup and verified Home selection. No source files were imported.

## Verification limits

Regressions execute actual first-run policy/gate, wizard request cancellation and
update-offer UI through platform adapters. Existing suites retain OTA trust/cache,
settings/navigation/localization and lifecycle coverage. CI compiles the actual
Android activities/resources/DEX and verifies signing and packaged helper. Real
TV welcome layout, DPAD focus, permission/role dialogs, city detection, OEM Live
TV intents and a real newer signed OTA still need device testing. No release or
production OTA change is part of this branch.

## Welcome redesign (1B163)

The first screen uses native vector stroke drawing from the supplied `docs/design/hello.svg` and `hello-ru.svg`, with the same day/night policy and background as Settings. The greeting fits a 570 × 210 area on a 1920 × 1080 reference screen, centered at 47% of height; the Semibold prompt is centered at 65%. Ink matches the Settings tvOS tile: light #8897AC, dark #51595B–#55646B gradient.

English and Russian handwriting alternate with overlapping fades; the bilingual center-button instruction crossfades near completion of each incoming word. Only a complete, uncanceled center/Enter press advances; arrows do not advance. Back keeps the existing session-defer behavior. Reduced Motion shows a static localized greeting. Drawing stops on pause/detach and does not schedule frames without window focus. Geometry, PathMeasure and paints are cached; no video decoder or frame bitmaps are used. The obsolete Marck Script font/license bundle and font-based greeting timer were removed.

Native drawing/input/timeline boundaries are covered by `test_setup_welcome.py`. Physical TV layout, contrast and smoothness still require device review.
