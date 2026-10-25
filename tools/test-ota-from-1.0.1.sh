#!/usr/bin/env bash
# Install a test updater over an existing 1.0 or 1.0.1, then test OTA through the app.
set -euo pipefail
SOURCE="$(cd "$(dirname "$0")/.." && pwd)"
ADB_CMD=(adb -e)
if [ -n "${TVOS_ADB_SERIAL:-}" ]; then ADB_CMD=(adb -s "$TVOS_ADB_SERIAL"); fi
PACKAGE_STATE="$("${ADB_CMD[@]}" shell dumpsys package io.tvos | tr -d '\r')"
INSTALLED_CODE="$(printf '%s\n' "$PACKAGE_STATE" | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1)"
INSTALLED_NAME="$(printf '%s\n' "$PACKAGE_STATE" | sed -n 's/.*versionName=\([^[:space:]]*\).*/\1/p' | head -1)"
case "$INSTALLED_NAME:$INSTALLED_CODE" in
  1.0:2|1.0.1:3) ;;
  *)
    echo "OTA test requires an existing tvOS 1.0 (code 2) or 1.0.1 (code 3). Found: ${INSTALLED_NAME:-missing} (code ${INSTALLED_CODE:-missing})." >&2
    echo 'No installation, downgrade or data removal was performed.' >&2
    exit 1 ;;
esac
printf 'Android release: '; "${ADB_CMD[@]}" shell getprop ro.build.version.release
printf 'Android SDK: '; "${ADB_CMD[@]}" shell getprop ro.build.version.sdk
[ -f "$SOURCE/config.properties" ] && [ -f "$SOURCE/freemytv.keystore" ] || {
  echo 'Existing config.properties and signing key are required; no new key will be created.' >&2; exit 1;
}
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/tvos-ota-test.XXXXXX")"
trap 'rm -rf -- "$TEST_DIR"' EXIT
cp -R "$SOURCE/app" "$TEST_DIR/app"
cp -R "$SOURCE/tools" "$TEST_DIR/tools"
cp "$SOURCE/build.sh" "$SOURCE/config.properties" "$SOURCE/freemytv.keystore" "$TEST_DIR/"
chmod 600 "$TEST_DIR/freemytv.keystore"
python3 - "$TEST_DIR/app/AndroidManifest.xml" "$INSTALLED_CODE" "$INSTALLED_NAME" <<'PY'
from pathlib import Path
import re,sys
path=Path(sys.argv[1]);source=path.read_text()
source=re.sub(r'android:versionCode="[0-9]+"',f'android:versionCode="{sys.argv[2]}"',source,count=1)
source=re.sub(r'android:versionName="[^"]+"',f'android:versionName="{sys.argv[3]}"',source,count=1)
path.write_text(source)
PY
bash "$TEST_DIR/build.sh"
"${ADB_CMD[@]}" install -r "$TEST_DIR/tvOS.apk"
"${ADB_CMD[@]}" shell am start -n io.tvos/.SettingsActivity
printf '%s\n' "Test updater installed as $INSTALLED_NAME without removing app data." \
  'Open System → Software Updates → Update Software and install the offered public release through the app.' \
  'If it fails, send the exact new error message. The public release files were not changed.'
