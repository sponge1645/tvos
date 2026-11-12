# TizenTube version choice — 1C242

Short OK on Download TizenTube keeps the existing latest-release download.
Holding OK for 500 ms opens a single-choice list of official stable versions;
the installed version is checked when present, and the newest stable row is
labelled Latest. Download starts only after the explicit Download button.
The original held-key release is ignored by the dialog, preventing a stray
selection or immediate latest download. A local Handler timer works even when
firmware supplies no key repeats. Moving focus, window focus loss or pause
cancels the hold. Other download buttons keep their existing behavior.

TizenReleases fetches the official GitHub releases, rejects drafts/prereleases,
selects an exact cobalt-arm.apk/cobalt-arm64.apk according to the existing ABI
ranking, and requires its published SHA-256 and exact upstream release URL.
APK package, Android SDK and signer checks remain in ApkTrust. The selected
release is passed directly to the worker; it never resolves latest again.

For a lower version, the worker downloads and verifies the APK while the
existing app is still installed. Only successful preparation offers an
explanation of app-data removal, followed by Android's native uninstall UI.
Cancellation of removal leaves the existing app in place and never opens an installer.
After confirmed removal, the installer opens the already verified selected
APK. It does not silently uninstall, use pm install -d or bypass package trust.
Before native uninstall, the selected version/URL/digest are atomically persisted
and flushed to disk. Installer cancellation/failure offers Retry, Later or Other
Versions. A cached APK can be retried offline. If Android evicted the cache, the
same release can be downloaded again. Recovery survives process death and is
checked when Settings reopens. Success or cancelled removal clears the record.
For OEM installers that omit activity results, 500 ms of stable returned window
focus triggers a PackageManager check. These safeguards are not an atomic
rollback: removed app data cannot be restored, and installation may still need
user action or a different release. Real-TV validation remains required.

Selected APKs use digest-named private cache files and a strict read-only
provider path. Ordinary app downloads keep installer.apk and tvOS keeps its
separate update.apk path. Cleanup retains the selected APK plus at most one
older selected file. Failed verification never offers uninstallation.

Tests execute hold timing/cancellation, the real release parser/ABI ranking,
the actual download worker preparation/failure path, existing downloader trust
and streaming rules, native uninstall return routing, and provider path limits.
Native installer/downgrade behavior still requires real-TV validation.

The official v1.0.8 cobalt-arm.apk was also downloaded and inspected: package
io.gh.reisxd.tizentube.cobalt, versionCode 108, minSdk 24, armeabi-v7a. Its SHA-256
59c7a8655424947cae7b4c29a65e723fb4ba6ddb45863473dc2a612470aa1e85 matches the
GitHub release digest, and certificate SHA-256
378ea3935af1b95c32f6ce193f4ff7f420cdc2d7160c8c866d28d6a91042ec9a matches the
existing catalog signer pin. No device installation was performed.
