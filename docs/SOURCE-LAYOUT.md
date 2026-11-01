# Source layout

Source directories follow Java packages. The first isolated modules are:

- `io.tvos.watching`: service aliases and source identities.
- `io.tvos.artwork`: publisher image URI policy, independent of the Android adapter.
- `io.tvos.network`: durable VPN restore journal, independent of Android scheduling.

Android adapters and existing screens still live in `io.tvos`. Move cohesive
modules as their dependencies are made explicit, rather than making all existing
package-private classes public or relocating files while retaining a flat package.
Resources keep Android's standard `res/values*` locale layout. The build discovers
Java files recursively. Tests compile the actual package paths.

- `io.tvos.settings`: stable screen IDs and navigation policy, ordered-item and Dock
  editor models, cached settings background, modal snapshot renderer and update text
  layout. Models receive storage interfaces/callbacks; they do not import Prefs,
  HomeApps or Android. SettingsEditors adapts the private launcher preferences.
  SettingsContent remains the localized-text adapter; SettingsActivity owns input,
  focus and screen lifecycle.
- `io.tvos.version`: numeric version comparison and build display identity.

The current display is `1.1.1 (1B139)`. Major version is numeric; minor version uses A=0, B=1, …, Z=25, AA=26. The three-digit sequence is maintained explicitly in `version/SoftwareBuild.java` and is not incremented by a local rebuild. Keep it increasing across patch versions within a major/minor series. APK versionName and OTA tags remain numeric; Android installation ordering still uses versionCode, which must increase for releases.

Brand presentation mappings and rating badge caches now live in `artwork/` alongside image source policy.

- `io.tvos.updates`: validated release metadata and pinned release download URLs. The Android updater remains an adapter in the original package.

Settings app queries run through `SettingsAppLoader` with lifecycle cancellation.
The settings background is static; only active focus, scroll, title, page and modal
animations request additional frames. General no longer opens system date/time
settings; date visibility and the 12/24-hour preference remain independent.

Home Screen mode in General chooses service recommendation banners or KinoPub/Seena/Lampa resume cards only when every poster category is disabled. It never changes category visibility/order or the Watching source.

- `io.tvos.home`: shared layout constants/math, top-control focus policy and
  the one-shot dream-return timeline. These helpers have no Android, preferences
  or renderer dependencies. HomeView caches policy callbacks once, shares menu
  host behavior and delegates nearest-card lookup to one implementation. The
  obsolete ready-only label Paint, write-only timestamps and zero-delay reveal
  scheduling were removed. Source application removes backed-up root copies of
  relocated Home classes.

- `io.tvos.home.dock`: generic Dock edit transaction, clock-driven caption
  transitions and resume-row rendering. The editor receives a save callback;
  HomeApps maps app items to persisted identities. The renderer receives fonts,
  labels, colors, bitmaps and a shadow callback, without reading preferences or
  loading provider data. DockContinue owns history/artwork/key handling and
  DockBanner adapts artwork into the independent caption timeline. The unused
  resume-row noWidgets argument was removed. Relocated editor sources are removed
  after backup when applying snapshots.

The artwork package now owns PosterCache, RoundedImages and the ImagePainter
boundary. Cache warnings are injected; rounded drawing receives the owning
platform's fixed legacy policy. Shared image code does not depend on Ui,
RouletteRenderer, Recs or AppLog. HTTPS and content/resource streams use one
bounded/cancellable copy routine, retaining existing cache paths, limits,
atomic promotion, retry timing and stale-window checks. Regression coverage
includes provider oversize/null streams and cancellation during copying.

Settings presentation: `settings/SettingsTextRenderer` owns tracked glyph drawing and the single wrapped-description cache; `settings/SettingsLogoRenderer` owns cached tile/cutout geometry. The Activity supplies text, theme, scale and typeface and clears description state on pause/resize. Both renderers are independent of preferences, localization and screen IDs. Cache keys include exact scale and typeface, not only rounded width. Removed obsolete title/subtitle/subrow/warning/spinner Paint allocations from SettingsActivity.

Settings rows: `settings/SettingsRowRenderer` owns capsule/focus geometry, reusable Paint/blur state and the cached chevron path; a shadow callback retains the existing legacy cache adapter. Drawing never mutates logical label/focus bounds. `SettingsTextRenderer` shares tracked-width measurement and one fitted-value cache including width, text size, typeface and tracking; pause/resize clears it. SettingsActivity retains screen policy and application data, without duplicate row graphics state or the unreachable About-row background branch.

`settings/SettingsListViewport` owns the isolated clipped list layer and its
resize-dependent edge mask. Its content callback receives only Canvas; screen
selection and transition state remain in SettingsActivity. The callback is retained
once by the view, with no per-frame callback or mask allocation.

VPN recovery records each started attempt in the existing durable marker under the
shared process/file lock. Five attempts are allowed across restarts; only attempts
one through four schedule a silent retry. Exhaustion removes the intent and emits
one localized manual-recovery notice. Legacy one-byte markers start at zero.

`settings/SettingsTransitions` owns menu/title transition state and focus delay.
The caller supplies screen-parent policy, a screen painter, localized title,
geometry, time and accessibility options. It retains navigation, focus springs
and frame scheduling. There is no duplicate transition state in SettingsActivity;
callbacks are allocated once. The existing parent-dependent direction, 280ms
smoothstep, title-only crossfade and fixed background/logo geometry are preserved.
Four obsolete list geometry constants and the viewport forwarding method were
removed after checking source, resources, reflection and tooling references.

`settings/SettingsListWindow` owns the logical offset, directional window movement,
picker positioning, editor focus-following and buffered draw range. It takes row
counts and indices without Android, preferences, screen IDs or app data. Focus
and the existing Spring animations remain in SettingsActivity; the old offset
field is removed. Shared Up/Down handling preserves key-repeat sounds, key-up
accessibility callbacks and About's read-only behavior. Row bounds share one
calculation, retaining About's detail offset and animated accessibility geometry.
