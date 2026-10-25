#!/usr/bin/env bash
# Install a test updater over an existing 1.0.1, then test OTA to the unchanged public 1.0.2.
set -euo pipefail
SOURCE="$(cd "$(dirname "$0")/.." && pwd)"
ADB_CMD=(adb -e)
if [ -n "${TVOS_ADB_SERIAL:-}" ]; then ADB_CMD=(adb -s "$TVOS_ADB_SERIAL"); fi
PACKAGE_STATE="$("${ADB_CMD[@]}" shell dumpsys package io.tvos | tr -d '\r')"
INSTALLED_CODE="$(printf '%s\n' "$PACKAGE_STATE" | sed -n 's/.*versionCode=\([0-9][0-9]*\).*/\1/p' | head -1)"
INSTALLED_NAME="$(printf '%s\n' "$PACKAGE_STATE" | sed -n 's/.*versionName=\([^[:space:]]*\).*/\1/p' | head -1)"
if [ "$INSTALLED_CODE" != 3 ] || [ "$INSTALLED_NAME" != 1.0.1 ]; then
  echo "OTA test requires an existing tvOS 1.0.1 (code 3). Found: ${INSTALLED_NAME:-missing} (code ${INSTALLED_CODE:-missing})." >&2
  echo 'No installation, downgrade or data removal was performed.' >&2
  exit 1
fi
[ -f "$SOURCE/config.properties" ] && [ -f "$SOURCE/freemytv.keystore" ] || {
  echo 'Existing config.properties and signing key are required; no new key will be created.' >&2; exit 1;
}
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/tvos-ota-test.XXXXXX")"
trap 'rm -rf -- "$TEST_DIR"' EXIT
cp -R "$SOURCE/app" "$TEST_DIR/app"
cp -R "$SOURCE/tools" "$TEST_DIR/tools"
cp "$SOURCE/build.sh" "$SOURCE/config.properties" "$SOURCE/freemytv.keystore" "$TEST_DIR/"
chmod 600 "$TEST_DIR/freemytv.keystore"
python3 - "$TEST_DIR/app/AndroidManifest.xml" <<'PY'
from pathlib import Path
import re,sys
path=Path(sys.argv[1]);source=path.read_text()
source=re.sub(r'android:versionCode="[0-9]+"','android:versionCode="3"',source,count=1)
source=re.sub(r'android:versionName="[^"]+"','android:versionName="1.0.1"',source,count=1)
path.write_text(source)
PY
bash "$TEST_DIR/build.sh"
"${ADB_CMD[@]}" install -r "$TEST_DIR/tvOS.apk"
"${ADB_CMD[@]}" shell am start -n io.tvos/.SettingsActivity
printf '%s\n' 'Test updater installed as 1.0.1 without removing app data.' \
  'Open System → Software Updates → Update Software and install the public 1.0.2 through the app.' \
  'If it fails, send the exact new error message. The public release files were not changed.'
