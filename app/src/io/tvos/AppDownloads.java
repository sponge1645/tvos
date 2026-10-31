package io.tvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** UI and lifetime of one third-party app download. SelfUpdate handles tvOS updates separately. */
public final class AppDownloads {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private AppDownloads() {}


    public static void start(Activity activity, int index) {
        AppDownloadCatalog.Entry app = AppDownloadCatalog.get(index);
        if (app == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (!BUSY.compareAndSet(false, true)) {
            message(activity, app.name, AppStrings.get(R.string.runtime_another_download_is_still_running));
            return;
        }
        try {
            new Job(activity, app).start();
        } catch (RuntimeException e) {
            BUSY.set(false);
            message(activity, app.name, AppStrings.get(R.string.runtime_could_not_start_download) + e.getMessage());
        }
    }

    private static void message(Activity owner, String title, String message) {
        if (owner.isFinishing() || owner.isDestroyed()) return;
        new AlertDialog.Builder(owner).setTitle(title).setMessage(message).setPositiveButton("OK", null).show();
    }

    /** Worker retains application context only; destroying the Activity cancels delivery and transfer. */
    private static final class Job implements Runnable, Application.ActivityLifecycleCallbacks {
        private final Context context;
        private final Application application;
        private final WeakReference<Activity> owner;
        private final WeakReference<AlertDialog> progress;
        private final AppDownloadCatalog.Entry app;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private boolean resumed = true;

        Job(Activity activity, AppDownloadCatalog.Entry app) {
            this.app = app;
            context = activity.getApplicationContext();
            application = activity.getApplication();
            owner = new WeakReference<>(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(app.name)
                    .setMessage(AppStrings.get(R.string.runtime_checking_app))
                    .setNegativeButton(AppStrings.get(R.string.runtime_cancel), (d, which) -> cancelled.set(true)).create();
            dialog.setOnCancelListener(d -> cancelled.set(true));
            dialog.show();
            progress = new WeakReference<>(dialog);
        }

        void start() {
            application.registerActivityLifecycleCallbacks(this);
            try { new Thread(this, "tvOS-apk-download").start(); }
            catch (RuntimeException e) { application.unregisterActivityLifecycleCallbacks(this); dismiss(); throw e; }
        }

        private void updateProgress(int percent) {
            UI.post(() -> {
                AlertDialog dialog = progress.get();
                if (!cancelled.get() && dialog != null && dialog.isShowing())
                    dialog.setMessage(AppStrings.get(R.string.runtime_downloading_apk) + percent + "%");
            });
        }

        @Override public void run() {
            File temporary = new File(context.getCacheDir(), "tvOS-installer.tmp.apk");
            boolean install = false;
            String result = null;
            try {
                PackageInfo installed = ApkTrust.installed(context.getPackageManager(), app.packageName);
                if (ApkTrust.reviewedVersionInstalled(app, installed)) {
                    result = AppStrings.get(R.string.runtime_installed_version_is_not_older_than_the_reviewed_build);
                    return;
                }
                AppDownloadCatalog.Source source = AppDownloadCatalog.resolve(app, installed != null,
                        Build.SUPPORTED_ABIS, cancelled::get);
                ApkTransfer.download(source, temporary, cancelled::get, this::updateProgress);
                ApkTransfer.checkCancelled(cancelled::get);
                // Re-read: the installed version may have changed while downloading.
                installed = ApkTrust.installed(context.getPackageManager(), app.packageName);
                if (installed == null && ApkTrust.requiresInstalledApp(app, source))
                    throw new IOException(AppStrings.get(R.string.runtime_app_was_removed_during_download_retry_with_the_reviewed_installat));
                PackageInfo archive = ApkTrust.verify(context.getPackageManager(), temporary, app, installed);
                if (installed != null && installed.getLongVersionCode() >= archive.getLongVersionCode()) {
                    result = AppStrings.get(R.string.runtime_app_is_already_installed);
                } else {
                    ApkTransfer.checkCancelled(cancelled::get);
                    File destination = new File(context.getCacheDir(), "tvOS-installer.apk");
                    if (destination.exists() && !destination.delete()) throw new IOException("Could not replace APK");
                    if (!temporary.renameTo(destination)) throw new IOException("Could not publish verified APK");
                    install = true;
                }
            } catch (Exception e) {
                if (!cancelled.get()) result = AppStrings.get(R.string.runtime_error) + e.getMessage();
            } finally {
                if (temporary.exists()) temporary.delete();
                final boolean ready = install;
                final String message = result;
                UI.post(() -> finish(ready, message));
            }
        }

        private void dismiss() {
            AlertDialog dialog = progress.get();
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        }

        private void finish(boolean install, String result) {
            application.unregisterActivityLifecycleCallbacks(this);
            dismiss();
            BUSY.set(false);
            Activity activity = owner.get();
            if (cancelled.get() || activity == null || activity.isFinishing() || activity.isDestroyed() || !resumed) return;
            if (!install) { if (result != null) message(activity, app.name, result); return; }
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse("content://io.tvos.apkshare/installer.apk"), "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { activity.startActivity(intent); }
            catch (RuntimeException e) {
                message(activity, app.name, AppStrings.get(R.string.runtime_could_not_open_installer) + e.getMessage());
            }
        }

        @Override public void onActivityDestroyed(Activity activity) {
            if (activity != owner.get()) return;
            cancelled.set(true);
            dismiss();
            application.unregisterActivityLifecycleCallbacks(this);
        }
        @Override public void onActivityResumed(Activity activity) { if (activity == owner.get()) resumed = true; }
        @Override public void onActivityPaused(Activity activity) { if (activity == owner.get()) resumed = false; }
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {
            if (activity == owner.get()) { cancelled.set(true); dismiss(); }
        }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    }
}
