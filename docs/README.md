name: Build and test
on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:
permissions:
  contents: read
jobs:
  android:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '17'
      - name: JVM tests
        run: |
          set -euo pipefail
          for test in tests/test_*.py; do python3 "$test"; done
      - name: Android SDK
        shell: bash
        run: |
          mkdir -p "$RUNNER_TEMP/tvos-android-sdk/licenses"
          cp -R "$ANDROID_HOME/licenses/." "$RUNNER_TEMP/tvos-android-sdk/licenses/"
          "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$RUNNER_TEMP/tvos-android-sdk" "platforms;android-34" "build-tools;34.0.0"
      - name: Strict javac checks and compile APK with disposable CI key
        env:
          ANDROID_HOME: ${{ runner.temp }}/tvos-android-sdk
          ANDROID_SDK_ROOT: ${{ runner.temp }}/tvos-android-sdk
          TVOS_STRICT_JAVA: '1'
          TVOS_SIGNING_MODE: ci
        run: |
          bash build.sh
          python3 tests/integration_signing_rotation.py
      - name: Verify compiled UI strings
        env:
          ANDROID_HOME: ${{ runner.temp }}/tvos-android-sdk
        run: python3 tools/check-resource-strings.py --aapt2 "$ANDROID_HOME/build-tools/34.0.0/aapt2" tvOS.apk
      # No production signing key or TMDB key is used; this APK is deliberately not published.

  jdk21-settings:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '21'
      - name: Strict settings regressions on JDK 21
        run: |
          python3 tests/test_settings_list_window.py
          python3 tests/test_settings_preference_editor.py
          python3 tests/test_setup_welcome.py
          python3 tests/test_setup_language.py
          python3 tests/test_setup_permissions.py
          python3 tests/test_setup_finish.py
          python3 tests/test_ota_refresh.py
          python3 tests/test_empty_search_focus.py

  jdk25-remote:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v7
        with:
          persist-credentials: false
      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: '25'
      - name: Remote input regressions on JDK 25
        run: |
          python3 tests/test_keyboard_handoff.py
          python3 tests/test_remote_input_method.py
          python3 tests/test_remote_taps.py

## Cleanup stages (1.1.2)

Build 1B178 removes the unreachable inline Home search (`HomeSearchInput`,
`HomeSearchGlass`, `HomeSearchState`), its extra layer, heading and lifecycle
branches. Home continues to launch `SearchActivity`; its shared `SearchController`,
recent queries, grid navigation and detail transitions remain in use.
`apply-source.sh` removes the retired sources/tests from existing installations
and keeps them in the normal backup. Tests cover the standalone search launch,
empty categories, remote navigation and source migration.

Navigation extraction and settings extraction are separate subsequent builds.
This stage does not change the design or navigation policy.

Build 1B179 extracts directional routing between weather, header actions, poster
pages and the Dock into `HomeNavigation`. A reused state snapshot bridges the
router to `HomeView`; content refreshes copy back clamped selection and updated
geometry before routing continues. Key press/release, long holds, app launches,
accessibility, drawer and grid controls retain their existing owners and behavior.
The remote navigation tests compile both the production router and HomeView bridge.

Build 1B180 extracts picker data and saved selection from `SettingsActivity` into
`SettingsPicker`: country codes/labels, TV providers, city results, and application
choices for remote buttons/VPN. All entry points share `showPicker()` so selected
rows and initial scroll offsets follow the same policy. Loading, lifecycle guards,
row rendering, navigation and transitions keep their existing owners. Regression
coverage includes the actual view entry points and sequential picker reuse.

Build 1B181 moves TV-provider lookup out of `SettingsActivity` into
`SettingsTvProviderLoader`. Requests use one bounded shared worker and cooperative
cancellation; duplicate confirms share the pending request. Back and pause cancel
the visit, including results already queued for UI delivery. Delivery also checks
that the Media page is active. Platform lookup failures retain the automatic-only
picker fallback. Tests exercise delayed callbacks across reentry, queue removal,
inactive pages, duplicate input, failures and executor rejection.

Build 1B182 consolidates category/header-button editor drawing in `drawOrderEditor`.
Ordering, toggles and persistence already share `SettingsOrderEditor`; no additional
controller is introduced. Redundant rebuild wrappers are removed. The header-button
VPN footer retains its disabled/chevron/value layout, categories retain their plain
rows, and focused rows still paint last. Row-binding tests cover switching editors
and footer styles at all supported test scales; exhaustive persistence/order tests
remain unchanged in scope.

Build 1B183 extracts resume-row artwork requests, generation guards, image paths
and the four-card poster/logo cache window into `DockContinueArtwork`.
`DockContinue` retains row navigation, animation and rendering. Provider metadata,
year/type matching, TMDB w780 URLs, fallback priority and shared `PosterCache`
budgets are unchanged. Tests cover old UI/worker callbacks after hide/replacement,
viewport changes during loading, optional logos and published/history fallbacks.

Build 1B184 removes temporary artwork search/result/decoded-image tracing while
retaining lookup failure messages. Resume artwork now checks cancellation between
provider metadata, TMDB metadata and optional logo download; late cache completion
cannot invalidate a cleared row. Tests cover cancellation during metadata and logo
lookup as well as queued delivery. Cache budgets, persisted files, matching policy
and UI animations remain unchanged. Two obsolete orphaned Javadocs are removed.
A targeted scan of the refactored Home/settings/resume classes found no additional
unreferenced private methods to remove safely.
