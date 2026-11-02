# Final audit — 1.1.1 (1B159)

## Changes

- Fix volume listener recovery after AccessibilityService.onInterrupt: restart
  only when enabled and interactive; preserve serialized cancellation/retry bounds.
- Replace TMDB null-as-close notification with ConnectionObserver. Release on
  setup failure and even if disconnect throws. A stale Dock close cannot clear
  the current connection; stale opens cannot replace it.
- Cache title/detail marquee fade shaders independently. Regression tests verify
  reuse over repeated frames and invalidation on geometry/edge changes.
- Remove unused DockArtwork.find wrapper and superseded host volume prototype
  tools/test-volume-hold.py plus its test. Source installer backs up retired files.
  Keep tools/test-volume-hold-device.py: it builds the bundled production helper.
- Clarify in RU/EN help that automatic region temporarily pauses Incy for the
  entire TV. Default-off setting, persistent recovery and five-attempt cap remain.

## Feature ledger

| Requested feature | State / implementation |
| --- | --- |
| Dock recommendations, service banners, original-language title logos | Implemented: DockBackdrop, DockArtwork, DockBanner; external artwork availability still matters. |
| Home Recommendations / Continue watching | Implemented for KinoPub, Seena and Lampa when content categories are disabled: DockContinue and HomeView. Resume links and focus geometry retained. |
| Seena/Lampa history | Implemented: WatchingCatalog, publisher ownership and deduplication. Empty provider channels cannot supply history. |
| Shared custom tiles in Dock and Applications, KinoPub artwork | Implemented: AppArtwork and app resources. Visual approval on target TV remains manual. |
| UI sounds and weather-style update popup | Implemented: Click and SettingsUpdateDialog. Perceived sound level depends on TV/audio output. |
| Wi-Fi strength | Implemented: four RSSI levels and connection fallbacks. |
| System Settings in Dock picker and MI/app-button remaps | Implemented; device-specific intents and key delivery remain platform dependent. |
| Volume hold with accessibility enabled | Opt-in bundled ADB helper, default off. Direct-audio standalone prototype tested by user; integrated APK still needs physical-device lifecycle/CEC validation. |
| Weather/city localization and forecast | Extracted models/parser/geocoder; cache, epoch and refresh behavior covered by regressions. |
| Automatic region / VPN recovery | Implemented, default off; system-wide Incy pause is explicit. Persistent bounded recovery is best effort under Android scheduling. |
| Seena refresh and dynamic APK | Implemented; 90-second coalescing plus Seena's own delay documented in WATCHING.md. Signer trust in APK-SOURCES.md. |
| Settings separation, localization/accessibility, build display | Implemented; coordinators retained where appropriate. General Date and Time action intentionally removed. Display 1B159 is separate from Android versionCode. |
| Safe source update and signing | Backups and personal config/key preservation tested. No release/merge or production signing keys created. |

## Scope and limitations

Reviewed production source references, manifest/build integration and private
member usage. No additional unreferenced private members were found apart from
intentional serialization hooks. Static reference checks are conservative, not
a proof that every reachable branch/resource is necessary. Dynamic resources and
reflection entry points were retained. Existing provider observation throttling,
image ownership validation and bounded VPN recovery were checked.

All 134 remaining tests/test_*.py scripts passed locally, with each exit code
checked. The retired prototype explains the reduction from 135 scripts. Strict
Android compilation, DEX, resources, CI signing and bundled-helper integration
are checked by the pull-request workflow. No physical TV tests or CPU/RAM/GPU/FPS
measurements were performed for this audit. Shader allocation reuse is tested;
performance improvement is not quantified. Further aggressive optimization needs
measurements on a weak TV. This audit cannot establish absence of all bugs.
