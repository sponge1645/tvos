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
