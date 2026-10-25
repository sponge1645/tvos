# tvOS for Android TV

Custom Android TV launcher based on Free My TV. Minimum Android 9 (API 28).
Original MIT license and copyright are retained in LICENSE. Original project documentation is in docs/UPSTREAM-README.md.

## Build

Requires JDK 17+, Android SDK build-tools 34+ and an Android platform 34+.
Set ANDROID_HOME if your SDK is not in a standard location.

```bash
cp config.example.properties config.properties
# Edit config.properties; use your own TMDB API key.
./build.sh
adb install -r tvOS.apk
```

For an existing installation, copy your current config.properties and freemytv.keystore
into this directory before building. Do not overwrite or publish the existing key.
The signing key must stay the same for APK updates to work. Keep a separate backup of it.

## Current snapshot

Includes Audit 3, Render 4, Posters 5, Background 6, Resources 7 and the HomeView
this.running forward-reference fix. APK builds were tested by the project owner;
full performance verification on physical TV hardware remains pending.

## Updates

GitHub Releases for [sponge1645/tvos](https://github.com/sponge1645/tvos/releases) are the update source.
Version 1.0 includes real manual checks/downloads and optional daily automatic check/download.
Installation uses Android's installer and requires confirmation. Update APKs must have the same
package and current signing certificate set, matching version metadata and SHA-256.

`./build.sh` also creates `release/tvOS.apk` and `release/update.json` from the same build.
For the next version, run `./release.sh 1.0.1`, commit the version change and upload both assets to
release tag `v1.0.1`. See [release instructions in Russian](docs/RELEASING.md).

GitHub Actions compiles with a disposable CI key and never publishes its test APK.

## Tests

Optional JVM tests use Python 3 and JDK 17+ with deterministic Android/network stubs.
Run from the repository root:

```bash
python3 tests/test_poster_cache.py
python3 tests/test_background_requests.py
python3 tests/test_weather_refresh.py
python3 tests/test_city_requests.py
python3 tests/test_player_lifecycle.py
```

These do not replace Android compilation or device UI/media tests.

## Keep private

Never commit config.properties, signing keys, generated out/ files, personal tokens,
or backup directories. APKs are distributed as release assets rather than source files.

## tvOS 1.1 development

Branch `dev-1.1`, preview `1.1.0-dev.7` (versionCode 13), based on stable 1.0.3.

The first development step uses 34 monochrome SVG symbols from the approved export. A small parser loads the bundled absolute M/L/C/Z paths once, including even-odd holes and layered opacity. Canvas draws the cached paths directly at the current icon size. Source PNGs remain an asset fallback. Existing layout reference sizes and tint alpha are preserved; only multi-layer icons with translucent tint use a bounded composition layer. The cached screensaver weather badge rasterizes its SVG mask at the actual glyph output size so its existing shadow remains intact. No external SVG library or per-frame XML parsing is added. Colored app logos remain unchanged.

Run `python3 tests/test_svg_symbols.py` and `python3 tests/test_screensaver_weather.py` for SVG rendering / sizing and weather badge checks. Real-device performance checks are still required, especially on Android 9 TVs. The rest of 1.1 remains in `docs/ROADMAP.md`.

The next step centralizes app tile styles in `AppBranding` and moves prepared Dock logos into `DockLogoRenderer`. `watch.kino.mod` shares the standard KinoPub logo, colors and visual scales while retaining its own launch package and stored identity. The renderer keeps one normal and one dark prepared logo per app, reuses them through focus motion and invalidates on source/layout/optical changes without building a source-ID string every frame. See `docs/REFACTORING.md`.

Search scheduling now lives in `SearchController`: the 400 ms debounce, request cancellation, generation checks, pending-query submission on Done/back and interrupted-query retry on resume. `HomeView` retains results, layout, heading animation and remote focus. The deterministic lifecycle test exercises the production controller and view callbacks, including late responses and Unicode query length.

`RouletteController` owns random-movie catalog loading/cancellation, winner selection, spin state, the five-second completion and bounded poster prefetch. `HomeView` keeps the existing strip drawing and remote focus callbacks. Cancelling a spin removes its completion; completed winners survive pause, while changing categories resets their presentation. `test_roulette_focus.py` runs the production controller and remote handlers with virtual time.

KinoPub card opening supports both `watch.kino` and `watch.kino.mod`, retaining the selected package through cached and online ID lookup. All fallback intents stay scoped to that package. Screensaver weather shadows are baked from explicitly blurred symbol alpha, with padding derived from the returned blur bounds; bitmap rectangle shadow layers are no longer used for icons.

`AppArtwork` owns bundled/extracted logo processing, image caches and card preparation; `HomeApps` retains discovery, ordering, labels and launching. The artwork implementation, cache keys/version, dimensions and loader call sites are preserved. `test_app_artwork.py` exercises production pixel and cache methods with deterministic graphics and real temporary PNG files.

`TmdbClient` isolates TMDB JSON transport from recommendation scheduling: API-key/Bearer authorization, eight-second timeouts and HTTP errors retain their behavior, and response streams are explicitly closed. `Recs` keeps its executors, cancellation, catalogs, localization and image/cache ownership. The transport test intercepts URL connections and exercises production code without external network requests.
