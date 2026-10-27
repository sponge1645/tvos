# External font and avatar pack

`./build.sh` invokes `tools/prepare-assets.py` after creating `out/`. The bundled app logos and symbols are copied from `app/assets`; five font files and 18 avatar images come from the external ZIP described in `tools/assets-pack.json`.

The manifest pins both the ZIP SHA-256 and every file's size/hash. Unknown, missing and duplicate ZIP entries are rejected. Downloads use HTTPS, are saved to a temporary file, verified and promoted into `.tvos-assets/<sha256>.zip` only after successful validation. Cached ZIPs are checked again before reuse. Fonts and avatars are staged under `out/assets` and are embedded in the resulting APK with their existing names; runtime rendering is unchanged. The signed APK is checked again against all per-file hashes before release metadata is generated.

## Source URL

The manifest contains the default Dropbox shared link. `dl=0` links are normalized to `dl=1`; Dropbox query keys are retained. Only HTTPS Dropbox links are accepted as initial download URLs, and redirects must remain HTTPS.

For a local override use either:

```bash
TVOS_ASSETS_URL='https://www.dropbox.com/scl/fi/.../tvOS-assets-v1.zip?rlkey=...&dl=1' ./build.sh
```

or `assets_url=...` in your untracked config.properties. URL overrides must serve the same pinned ZIP. To publish another pack, update its URL, archive hash and per-file hashes together.

## Offline builds

After one successful download the ignored `.tvos-assets` cache is enough. To bootstrap from a local copy of the exact ZIP:

```bash
TVOS_ASSETS_ARCHIVE="$HOME/Downloads/tvOS-assets-v1.zip" ./build.sh
```

If all legacy source font/avatar files already match the manifest, they can also be staged without a download. The source updater removes the exact retired managed files from an older checkout after backing it up, preserving custom assets, local config and signing keys.

## Scope

This moves the binaries out of the current source tree, not out of older Git history or published APKs. No history rewrite is performed. The project's code license does not license third-party fonts/images; hosting or downloading them elsewhere does not change their terms.

GitHub Actions uses the same downloader and verifies a normal APK build with its disposable CI signing key. It does not publish the test APK.
