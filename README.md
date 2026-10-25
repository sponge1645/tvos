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

Branch `dev-1.1`, preview `1.1.0-dev.1` (versionCode 7), based on stable 1.0.3.

The first development step uses 34 monochrome SVG symbols from the approved export. A small parser loads the bundled absolute M/L/C/Z paths once, including even-odd holes and layered opacity. Canvas draws the cached paths directly at the current icon size. Source PNGs remain an asset fallback. Existing layout reference sizes and tint alpha are preserved; only multi-layer icons with translucent tint use a bounded composition layer. The cached screensaver weather badge rasterizes its SVG mask at the actual glyph output size so its existing shadow remains intact. No external SVG library or per-frame XML parsing is added. Colored app logos remain unchanged.

Run `python3 tests/test_svg_symbols.py` and `python3 tests/test_screensaver_weather.py` for SVG rendering / sizing and weather badge checks. Real-device performance checks are still required, especially on Android 9 TVs. The rest of 1.1 remains in `docs/ROADMAP.md`.
