#!/usr/bin/env bash
# Apply this source snapshot to an existing local project while preserving personal config and signing key.
set -euo pipefail
SOURCE="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-$HOME/free-my-tv/free-my-tv}"
[ -f "$TARGET/build.sh" ] && [ -d "$TARGET/app/src/io/tvos" ] || { echo 'Existing tvOS project not found.'; exit 1; }
[ "$SOURCE" != "$(cd "$TARGET" && pwd)" ] || { echo 'Source and target are identical.'; exit 1; }
BACKUP="$TARGET/patch-backups/github-updater-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP"
for ITEM in app build.sh release.sh tools tests README.md LICENSE config.example.properties .gitignore docs; do
  if [ -e "$TARGET/$ITEM" ]; then cp -R "$TARGET/$ITEM" "$BACKUP/"; fi
  if [ -e "$SOURCE/$ITEM" ]; then
    if [ -d "$SOURCE/$ITEM" ]; then
      mkdir -p "$TARGET/$ITEM"
      rsync -a "$SOURCE/$ITEM/" "$TARGET/$ITEM/"
    else cp "$SOURCE/$ITEM" "$TARGET/$ITEM"; fi
  fi
done
# Retired tracked files must also disappear from an existing checkout; BACKUP already holds the old app.
for RETIRED_FILE in \
  app/src/io/tvos/Russian.java app/src/io/tvos/Glyphs.java \
  app/res/drawable/coin_btc.xml app/res/drawable/coin_eth.xml \
  app/res/drawable/coin_sol.xml app/res/drawable/coin_usdt.xml \
  app/assets/symbols/bitcoin.svg app/assets/symbols/bitcoin.png; do
  rm -f -- "$TARGET/$RETIRED_FILE"
done
# These fonts were replaced by SF Pro Display. The complete previous app is already in BACKUP.
for FONT_FILE in InterVariable.ttf sf_text_regular.otf sf_text_medium.otf sf_text_semibold.otf OFL.txt; do
  rm -f -- "$TARGET/app/assets/fonts/$FONT_FILE"
done
chmod +x "$TARGET/build.sh" "$TARGET/release.sh"
echo "Source applied. Backup: $BACKUP"
echo 'Existing config.properties and freemytv.keystore preserved.'
