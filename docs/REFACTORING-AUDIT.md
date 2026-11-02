# Structural baseline: 1.1.1 (1B154)

Historical baseline. See [FINAL-AUDIT.md](FINAL-AUDIT.md) for 1B159 changes and
the integrated opt-in volume workaround added after this baseline.

The broad structural-separation phase is complete for the current feature set.
This is an ownership and cleanup baseline, not evidence of higher FPS or lower PSS.

## Ownership retained after the audit

| Area | Responsibility |
| --- | --- |
| `home/` and Home state/rendering components | Geometry, focus policy, layers, poster windows, Dock/drawer/grid/search state and drawing |
| `settings/` and Settings adapters | Screen/action policy, editors, lists, transitions, remote input, backgrounds and text |
| `catalog/` and Recs adapters | Selection, interactive query policy, diagnostic output; Android queue/cache/metadata integration |
| `weather/` and HomeData | Forecast parsing/model and geocoding; Android storage, refresh and stale-result delivery |
| `watching/` and Watching adapters | Source identity, channel discovery, resume policy and card decoding; provider/lifecycle coordination |
| `artwork/` and artwork adapters | URI policy, poster/image caching, decode and shared drawing |
| `network/` and VPN adapters | Durable restore journal and signal policy; platform service/job integration |
| `version/` | Numeric versions and displayed build identity |

`HomeView` retains Android layer composition, input routing, transitions between
features and lifecycle. Its geometry, drawing and independently owned state have
already been extracted. A further generic screen Host would mostly mirror its
state and callbacks. `SettingsActivity` similarly retains platform integration,
navigation and lifecycle. Root-package adapters that use package-private cards,
preferences or Android services are intentional; they are not made public merely
to move their files into a folder.

## Verified cleanup in this pass

A JDK compiler-tree scan identified private fields without references or with only
assignment references, and private methods without identifier/member references.
Each candidate was checked against source, manifest/resources, tools and tests;
constructor/serialization hooks were excluded. This conservative scan can miss
same-name references across nested scopes and is not a proof that all dead code
is absent.

Removed:

- `DreamView`: unused margin/drift constants, fallback-clock Paint, obsolete sky
  timestamp, write-only dirty flag and unused weather Paint setup. Actual
  `chromeDirty`, invalidation, clock/photo rendering and animation scheduling remain.
- `HomeWidgets`: unused temporary RectF.
- `HomeAppsRenderer`: unused faint-color constant.
- `SettingsActivity`: unused return-focus field, plus stale test-fixture state/helper.
- The uncalled `HomeApps.special` → `AppArtwork.symbolTile` path. Active complete
  tiles, logos, gradients and settings fallback paths remain.
- Unused imports in BackdropCache, HomeApps and MovieEnrichment.

No resource files, persisted keys, signature scheme, source identities or product
features are removed. No root Java source is relocated in this pass, so the source
application retirement list requires no new entries.

## Verification and next work

Run all `tests/test_*.py`, accounting for each exit code, plus the repository's
strict APK/D8/resource/signing CI workflow. Existing regressions cover geometry,
focus/navigation, search/roulette, async cancellation, provider ownership, history,
weather, lifecycle, updater and migration behavior. They do not replace device
validation of native drawing, video/dream behavior, firmware input or performance.

Next, establish reproducible device baselines before changing CPU/RAM/GPU behavior:
use the same build, device, category/widget settings and warmed/cold scenarios;
record frame timing/jank, process memory and image/cache/request behavior. Choose
changes from the measured bottleneck and compare the same scenario afterward.
Do not resume broad file splitting without a concrete ownership problem.

The deferred volume-key hold issue remains in `DEVELOPMENT-NOTES.md`. Its exact
failure point still needs on-device event evidence; it is not changed here.
