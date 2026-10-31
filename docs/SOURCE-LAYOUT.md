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

The current display is `1.1.1 (1B132)`. Major version is numeric; minor version uses A=0, B=1, …, Z=25, AA=26. The three-digit sequence is maintained explicitly in `version/SoftwareBuild.java` and is not incremented by a local rebuild. Keep it increasing across patch versions within a major/minor series. APK versionName and OTA tags remain numeric; Android installation ordering still uses versionCode, which must increase for releases.

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
