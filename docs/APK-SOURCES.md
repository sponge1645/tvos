# APK source and trust policy

The application catalog is defined in `AppDownloadCatalog`. Downloads use HTTPS,
are size-bounded, and are inspected before installation. The expected package and
pinned signing-certificate SHA-256 must match. A source with an APK SHA-256 also
requires an exact file hash; a certificate fingerprint is a separate check and is
not an APK hash. Android's installer remains the final installation step.

## Fixed and rolling sources

Fixed fallback releases retain their recorded file hashes. Supported GitHub
release discovery can select newer APKs with the existing package/signer checks.
A rolling release cannot use a fixed file hash across arbitrary new versions.
Changes to pinned signing identities require an explicit catalog review; fetching
release metadata does not authorize a new signer.

Seena previously used a fixed release. It now resolves the device ABI against
`https://seena.su/updates/release-info.json`, with
`https://seena.su/d.apk` as the official rolling fallback. These sources have no
fixed APK hash. Both still require package `com.seena.app` and certificate SHA-256:

`731e85d4ed0224a0aea4d58058b5d73f82cb4d4e2dcf8dc9ab54b0c5ef20c59d`

This trusts APKs signed by that identity, including future versions; it does not
pin a specific reviewed binary or establish the absence of application defects.

## INCY rolling release (1B160)

The publisher replaces Incy.apk under the `latest` tag. A historical fixed file
hash therefore rejected legitimate replacements. Resolve the exact Incy.apk asset
from the official INCY-DEV/incy-platforms releases/tags/latest API on both first
installation and update. Require its SHA-256 digest and expected official download
URL; missing/malformed metadata fails closed. APK bytes must match that digest,
then package and existing pinned signer checks still apply. No unsigned or
hashless fallback is used. Replacement between metadata and download is rejected;
retry obtains fresh metadata. GitHub rate limits/network failures remain errors.

## KinoPub rolling downloads

First installs and updates both discover the greatest numeric `kino-watch-X.apk`
version in `kotlinbestuser/apk`. Beta and unrelated file names are excluded.
The former 2.19 snapshot is no longer selected on first installation.
The downloaded APK must still have package `watch.kino` and the existing reviewed
signing-certificate fingerprint; updates must also match the installed signer.
This explicitly trusts new versions signed by that identity rather than pinning
each binary hash. Discovery failure is reported instead of installing an old fallback.
