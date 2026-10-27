package io.tvos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** App-context owner: network work survives Settings pauses without retaining an Activity. */
final class SelfUpdate {
    static final int IDLE = 0, CHECKING = 1, CURRENT = 2, AVAILABLE = 3, DOWNLOADING = 4, READY = 5, ERROR = 6;
    private static final long AUTO_INTERVAL = 24 * 60 * 60_000L;
    interface Listener { void onUpdate(); }
    static final class Status {
        final int kind, percent;
        final String message;
        final UpdateRelease release;
        Status(int kind, UpdateRelease release, int percent, String message) {
            this.kind = kind; this.release = release; this.percent = percent; this.message = message;
        }
    }
    private static SelfUpdate instance;
    static synchronized SelfUpdate get(Context context) {
        if (instance == null) instance = new SelfUpdate(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    // Listeners are registered, removed and notified only on the main thread.
    private final List<Listener> listeners = new ArrayList<>();
    private volatile Status status = new Status(IDLE, null, -1, "");
    private boolean fakeSession;
    private final FakeUpdate fakeUpdate = new FakeUpdate(main, (kind, percent) -> {
        busy.set(kind == CHECKING || kind == DOWNLOADING);
        publish(kind, UpdateRelease.rehearsal(), percent,
                kind == ERROR ? AppStrings.get(R.string.settings_fake_update_complete) : "");
    });
    void setFake(boolean enabled) {
        if (busy.get() && !fakeSession) return; // Do not interrupt a real transfer.
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("fake_update", enabled).apply();
        fakeUpdate.cancel();
        fakeSession = false;
        busy.set(false);
        publish(IDLE, null, -1, "");
    }
    private SelfUpdate(Context context) { this.context = context; }
    Status status() { return status; }
    void addListener(Listener listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    void removeListener(Listener listener) { listeners.remove(listener); }
    private void publish(int kind, UpdateRelease release, int percent, String message) {
        status = new Status(kind, release, percent, message);
        for (Listener listener : new ArrayList<>(listeners)) listener.onUpdate();
    }

    void maybeAutomatic(boolean force) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (prefs.getBoolean("fake_update", false) || !prefs.getBoolean("auto_update", false) || busy.get()) return;
        long now = System.currentTimeMillis(), last = prefs.getLong("self_update_attempt", 0);
        if (!force && last != 0 && now >= last && now - last < AUTO_INTERVAL) return;
        check(true);
    }

    void check(boolean automatic) {
        if (!busy.compareAndSet(false, true)) return;
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putLong("self_update_attempt", System.currentTimeMillis()).apply();
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("fake_update", false)) {
            fakeSession = true;
            fakeUpdate.check();
            return;
        }
        fakeSession = false;
        final Status previous = status;
        publish(CHECKING, null, -1, "");
        io.execute(() -> {
            try {
                UpdateRelease release = UpdateRelease.parse(new JSONObject(readManifest()));
                PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                boolean newer = release.versionCode > installed.getLongVersionCode();
                File ready = new File(context.getCacheDir(), "tvos-update.apk");
                boolean reuseReady = previous.kind == READY && sameRelease(previous.release, release)
                        && ready.isFile() && ready.length() == release.size;
                main.post(() -> {
                    busy.set(false);
                    publish(newer ? (reuseReady ? READY : AVAILABLE) : CURRENT, release, reuseReady ? 100 : -1, "");
                    if (newer && !reuseReady && automatic && context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                            .getBoolean("auto_update", false)) download();
                });
            } catch (Exception e) { fail(e); }
        });
    }

    void download() {
        if (fakeSession) { fakeUpdate.download(); return; }
        final UpdateRelease release = status.release;
        if (release == null || !busy.compareAndSet(false, true)) return;
        publish(DOWNLOADING, release, -1, "");
        io.execute(() -> {
            File tmp = new File(context.getCacheDir(), "tvos-update.part.apk");
            try {
                HttpURLConnection connection = connection(release.apkUrl());
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long received = 0;
                int lastPercent = -1;
                try {
                    long declared = connection.getContentLengthLong();
                    if (declared >= 0 && declared != release.size) throw new IOException(AppStrings.get(R.string.runtime_apk_size_mismatch));
                    try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            received += count;
                            if (received > release.size) throw new IOException(AppStrings.get(R.string.runtime_apk_exceeds_expected_size));
                            out.write(buffer, 0, count);
                            digest.update(buffer, 0, count);
                            int percent = (int) (received * 100 / release.size);
                            if (percent >= lastPercent + 5) {
                                lastPercent = percent;
                                main.post(() -> publish(DOWNLOADING, release, percent, ""));
                            }
                        }
                        out.getFD().sync();
                    }
                } finally { connection.disconnect(); }
                if (received != release.size || !hex(digest.digest()).equals(release.sha256))
                    throw new IOException(AppStrings.get(R.string.runtime_sha_256_verification_failed));
                verifyApk(tmp, release);
                File ready = new File(context.getCacheDir(), "tvos-update.apk");
                // Same private filesystem: rename replaces the published file only after verification.
                if (!tmp.renameTo(ready)) throw new IOException(AppStrings.get(R.string.runtime_could_not_save_update));
                main.post(() -> { busy.set(false); publish(READY, release, 100, ""); });
            } catch (Exception e) {
                tmp.delete();
                fail(e);
            }
        });
    }

    // Retain legacy signer metadata as a fallback for older TV package managers.
    @SuppressWarnings("deprecation")
    private static final int SIGNER_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES;

    private void verifyApk(File file, UpdateRelease release) throws Exception {
        PackageManager pm = context.getPackageManager();
        // Read package/version independently: requesting certificates can fail on some Android builds.
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), 0);
        if (archive == null)
            throw new IOException(AppStrings.get(R.string.runtime_android_could_not_read_update_apk_metadata));
        if (!context.getPackageName().equals(archive.packageName))
            throw new IOException(AppStrings.get(R.string.runtime_unexpected_update_package) + archive.packageName);
        if (archive.getLongVersionCode() != release.versionCode || !release.versionName.equals(archive.versionName))
            throw new IOException(AppStrings.get(R.string.runtime_apk_version_differs_from_release_metadata)
                    + archive.versionName + " (" + archive.getLongVersionCode() + "), "
                    + AppStrings.get(R.string.runtime_expected) + release.versionName + " (" + release.versionCode + ")");
        if (archive.getLongVersionCode() <= installed.getLongVersionCode())
            throw new IOException(AppStrings.get(R.string.runtime_installed_version_is_equal_to_or_newer_than_the_update)
                    + installed.versionName + " (" + installed.getLongVersionCode() + ")");
        PackageInfo signedArchive, signedInstalled;
        try {
            signedArchive = pm.getPackageArchiveInfo(file.getAbsolutePath(), SIGNER_FLAGS);
            signedInstalled = pm.getPackageInfo(context.getPackageName(), SIGNER_FLAGS);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // Android's installer still MUST verify the APK signature before replacing the app.
            return;
        }
        if (signedArchive != null && signedInstalled != null
                && currentSigners(signedArchive) != null && currentSigners(signedInstalled) != null
                && !sameSigners(signedInstalled, signedArchive))
            throw new IOException(AppStrings.get(R.string.runtime_update_signer_differs_from_installed_tvos));
        // Missing signer metadata is not proof of a bad signature. The mandatory Android installer
        // verifies the real APK and rejects incompatible certificates; this app never installs silently.
    }

    private static boolean sameRelease(UpdateRelease a, UpdateRelease b) {
        return a != null && b != null && a.versionCode == b.versionCode && a.size == b.size
                && a.versionName.equals(b.versionName) && a.tag.equals(b.tag) && a.sha256.equals(b.sha256);
    }

    /** Prefer current signers; firmware may populate only the legacy representation. Never accept signing history. */
    // Some TV firmware exposes legacy signatures instead of SigningInfo.
    @SuppressWarnings("deprecation")
    private static Signature[] currentSigners(PackageInfo info) {
        try {
            Signature[] signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) signatures = info.signatures;
            if (signatures == null || signatures.length == 0) return null;
            for (Signature signature : signatures) if (signature == null) return null;
            return signatures;
        } catch (RuntimeException unavailable) {
            return null; // Mandatory system verification still runs during installation.
        }
    }

    private static boolean sameSigners(PackageInfo installed, PackageInfo archive) {
        Signature[] a = currentSigners(installed), b = currentSigners(archive);
        return a != null && b != null && a.length == b.length
                && new HashSet<>(Arrays.asList(a)).equals(new HashSet<>(Arrays.asList(b)));
    }

    void install(Activity activity) {
        if (fakeSession) return;
        if (status.kind != READY || status.release == null || activity.isFinishing() || activity.isDestroyed()) return;
        try {
            File file = new File(context.getCacheDir(), "tvos-update.apk");
            if (!file.isFile() || file.length() != status.release.size) throw new IOException(AppStrings.get(R.string.runtime_cached_update_was_removed_check_for_updates_again));
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(activity, AppStrings.get(R.string.runtime_allow_installation_from_this_source_then_select_install_again), Toast.LENGTH_LONG).show();
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName())));
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse("content://io.tvos.apkshare/update.apk"), "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(intent);
        } catch (Exception e) { publish(ERROR, status.release, -1, error(e)); }
    }

    private void fail(Exception error) {
        main.post(() -> { busy.set(false); publish(ERROR, status.release, -1, error(error)); });
    }
    private static String error(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return AppStrings.get(R.string.runtime_could_not_obtain_update);
        return message.length() > 90 ? message.substring(0, 87) + "…" : message;
    }
    private String readManifest() throws IOException {
        HttpURLConnection connection = connection(UpdateRelease.LATEST + "?check=" + System.currentTimeMillis());
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 65536) throw new IOException(AppStrings.get(R.string.runtime_update_manifest_is_too_large));
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }
    private static HttpURLConnection connection(String address) throws IOException {
        URL url = new URL(address);
        for (int redirects = 0; redirects < 8; redirects++) {
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("HTTPS required");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "tvOS-Updater/1.0");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int code;
            try { code = connection.getResponseCode(); }
            catch (IOException e) { connection.disconnect(); throw e; }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect without Location");
                url = new URL(url, location);
                continue;
            }
            if (code < 200 || code >= 300) {
                connection.disconnect();
                if (code == 404) throw new IOException(AppStrings.get(R.string.runtime_release_or_update_asset_has_not_been_published_yet));
                throw new IOException("HTTP " + code);
            }
            return connection;
        }
        throw new IOException(AppStrings.get(R.string.runtime_too_many_redirects));
    }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) { int n = value & 255; out.append(Character.forDigit(n >>> 4, 16)).append(Character.forDigit(n & 15, 16)); }
        return out.toString();
    }
}
