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

## Shared stable release discovery

AirPlay, Apple Music TV, TizenTube and Lampa now resolve GitHub stable releases
on both first installation and update. Historical catalog artifact records no
longer select the first-install download. Drafts/prereleases/debug APKs are
excluded; compatible ABI selection is shared between install and update.
First installations require the selected release asset's SHA-256 digest; when
provided on updates it is checked too. Invalid digests fail closed. There is no
silent fallback to the old catalog APK on metadata failures. Package, Android
version, pinned signer and installed signer checks remain in effect.

KinoPub 2.19 and 2.23 both declare Android versionCode 169. After APK identity
and signer verification, equal-code KinoPub builds compare numeric versionName:
a strictly newer name can be handed to Android's installer. Equal/older names,
unknown name formats, and lower Android version codes are not offered. Other
packages retain the normal version-code comparison.

## tvOS build-aware OTA ordering

Release preparation writes `buildSequence` from `SoftwareBuild.SEQUENCE` into
update.json. When Android versionCode and versionName are equal, a greater
buildSequence is offered and accepted after the usual package/version, file hash
and signer verification. Lower codes and equal/older builds are rejected.
Legacy manifests without buildSequence remain supported for higher versionCode
updates but cannot authorize an equal-code update. Snooze and ready-APK identity
include the build. Old installed updater code must first be replaced manually or
by a release with a higher Android versionCode to gain this behavior.
