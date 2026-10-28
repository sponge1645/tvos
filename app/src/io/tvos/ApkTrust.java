package io.tvos;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Package identity and exact current signer-set checks; key rotation requires an explicit catalog review. */
final class ApkTrust {
    private ApkTrust() {}

    // Request both representations: some TV firmware leaves SigningInfo empty for archives.
    // Retain the reviewed legacy-signature fallback; do not weaken APK trust checks.
    @SuppressWarnings("deprecation")
    private static final int SIGNER_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES;

    static PackageInfo installed(PackageManager manager, String packageName) {
        try { return manager.getPackageInfo(packageName, SIGNER_FLAGS); }
        catch (PackageManager.NameNotFoundException missing) { return null; }
    }

    static PackageInfo verify(PackageManager manager, File file, AppDownloadCatalog.Entry app,
                              PackageInfo installed) throws IOException {
        PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), SIGNER_FLAGS);
        if (archive == null || !app.packageName.equals(archive.packageName) || archive.getLongVersionCode() <= 0)
            throw new IOException(AppStrings.get(R.string.runtime_unexpected_apk_package_or_version));
        if (archive.applicationInfo == null || archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
            throw new IOException(AppStrings.get(R.string.runtime_apk_requires_a_newer_android_version));
        Set<String> actual = signers(archive);
        if (actual.isEmpty())
            throw new IOException(AppStrings.get(R.string.runtime_android_returned_no_apk_signing_certificate));
        if (app.signerSha256 != null && !actual.equals(Collections.singleton(app.signerSha256)))
            throw new IOException(AppStrings.get(R.string.runtime_apk_signer_pin_mismatch));
        if (installed == null && (app.bootstrap == null || app.signerSha256 == null))
            throw new IOException(AppStrings.get(R.string.runtime_first_installation_is_not_trusted));
        if (installed != null && (!app.packageName.equals(installed.packageName) || !actual.equals(signers(installed))))
            throw new IOException(AppStrings.get(R.string.runtime_apk_signer_differs_from_the_installed_app));
        return archive;
    }

    static boolean reviewedVersionInstalled(AppDownloadCatalog.Entry app, PackageInfo installed) {
        return app.fixedArtifact && app.reviewedVersionCode > 0 && installed != null
                && app.packageName.equals(installed.packageName) && app.signerSha256 != null
                && installed.getLongVersionCode() >= app.reviewedVersionCode
                && signers(installed).equals(Collections.singleton(app.signerSha256));
    }

    // Some TV package managers still expose only the legacy signature array.
    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo info) {
        Set<String> result = new HashSet<>();
        // Prefer the full current signer set. Never use signing history or merge legacy data.
        Signature[] signatures = info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : null;
        if (signatures == null || signatures.length == 0) signatures = info.signatures;
        if (signatures == null) return result;
        for (Signature signature : signatures) {
            if (signature == null) return Collections.emptySet();
            result.add(ApkTransfer.hex(ApkTransfer.digest().digest(signature.toByteArray())));
        }
        return result;
    }
}
