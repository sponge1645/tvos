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

- `io.tvos.settings`: independent settings text layout.
- `io.tvos.version`: numeric version comparison and build display identity.

The current display is `1.1.1 (1B129)`. Major version is numeric; minor version uses A=0, B=1, …, Z=25, AA=26. The three-digit sequence is maintained explicitly in `version/SoftwareBuild.java` and is not incremented by a local rebuild. Keep it increasing across patch versions within a major/minor series. APK versionName and OTA tags remain numeric; Android installation ordering still uses versionCode, which must increase for releases.

Brand presentation mappings and rating badge caches now live in `artwork/` alongside image source policy.

- `io.tvos.updates`: validated release metadata and pinned release download URLs. The Android updater remains an adapter in the original package.

Settings app queries run through `SettingsAppLoader` with lifecycle cancellation.
The settings background is static; only active focus, scroll, title, page and modal
animations request additional frames. General no longer opens system date/time
settings; date visibility and the 12/24-hour preference remain independent.

Home Screen mode in General chooses service recommendation banners or KinoPub/Seena/Lampa resume cards only when every poster category is disabled. It never changes category visibility/order or the Watching source.
