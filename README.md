# tvOS for Android TV

Custom Android TV launcher based on Free My TV. Minimum Android 9 (API 28).
Original MIT license and copyright are retained in LICENSE. Original project documentation is in docs/UPSTREAM-README.md.

## Build

Requires Python 3.8+, curl, JDK 17+, Android SDK build-tools 34+ and an Android platform 34+.
Set ANDROID_HOME if your SDK is not in a standard location.

```bash
cp config.example.properties config.properties
# Edit config.properties; use your own TMDB API key.
./build.sh
adb install -r tvOS.apk
```

For an existing installation, copy your current config.properties and freemytv.keystore
into this directory before building. Do not overwrite or publish the existing key.
The original key is retained for the 1.0.4 bridge. Run `python3 tools/signing.py init` once on the owner Mac; see docs/KEY-ROTATION.md.

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
Use `release.sh` and `tools/publish-release.py` as documented in docs/RELEASING.md. Keep v1.0.4 as legacy GitHub Latest.

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


## 1.0.4 bridge

This branch is based on stable 1.0.3 and preserves its interface. The bridge uses the original signing key (versionCode 7), accepts platform-verified forward key rotation, and reads the modern ota-v1 update channel. Font/avatar bytes are fetched from the pinned Dropbox pack during build. Before building run `python3 tools/signing.py init` once on the owner Mac. See docs/KEY-ROTATION.md and docs/RELEASING.md; the bridge release must remain GitHub Latest.
