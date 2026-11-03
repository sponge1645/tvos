package io.tvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.function.BooleanSupplier;

/** Foreground-only offer/install UI and a sparse automatic check timer. No Activity held by network work. */
final class HomeUpdatePrompt {
    private final Activity activity;
    private final BooleanSupplier canShow;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SelfUpdate updater;
    private boolean active, installRequested, background;
    private AlertDialog dialog;
    private int dialogKind = -1;
    private long dialogVersion = -1;
    private final SelfUpdate.Listener listener = this::changed;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            updater.maybeAutomatic(false); changed();
            if (prefs().getBoolean("auto_update", false)) handler.postDelayed(this, 60 * 60_000L);
        }
    };
    HomeUpdatePrompt(Activity activity, BooleanSupplier canShow) {
        this.activity = activity; this.canShow = canShow; updater = SelfUpdate.get(activity);
    }
    private SharedPreferences prefs() { return activity.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    void resume() {
        if (active) return;
        active = true; background = false; updater.addListener(listener); poll.run();
    }
    void pause() {
        active = false; installRequested = false; updater.removeListener(listener); handler.removeCallbacks(poll); dismiss();
    }
    private void dismiss() {
        if (dialog != null) { dialog.setOnCancelListener(null); dialog.dismiss(); dialog = null; }
        dialogKind = -1;
    }
    private void later(long code) {
        prefs().edit().putLong("update_offer_later_code", code)
                .putLong("update_offer_later_until", System.currentTimeMillis() + 24 * 60 * 60_000L).apply();
        installRequested = false; dismiss();
    }
    private boolean snoozed(long code) {
        long until = prefs().getLong("update_offer_later_until", 0), now = System.currentTimeMillis();
        return prefs().getLong("update_offer_later_code", -1) == code && until > now && until - now <= 24 * 60 * 60_000L;
    }
    void changed() {
        if (!active || activity.isFinishing() || activity.isDestroyed() || dialog == null && !canShow.getAsBoolean()) return;
        SelfUpdate.Status status = updater.status();
        if (status.release == null) return;
        long code = status.release.versionCode;
        if (status.kind == SelfUpdate.READY && installRequested) {
            installRequested = false; dismiss(); updater.install(activity); return;
        }
        if (status.kind == SelfUpdate.DOWNLOADING && installRequested && !background) {
            String message = AppStrings.get(R.string.settings_setup_downloading) + (status.percent < 0 ? "" : " " + status.percent + "%");
            if (dialog != null && dialogKind == status.kind) { dialog.setMessage(message); return; }
            show(status.kind, code, message, R.string.settings_setup_background, () -> {
                background = true; installRequested = false; dismiss();
            }, false); return;
        }
        if (status.kind == SelfUpdate.ERROR && installRequested) {
            installRequested = false;
            show(status.kind, code, status.message, R.string.settings_setup_retry,
                    () -> { installRequested = true; updater.download(); }, true); return;
        }
        if (!prefs().getBoolean("auto_update", false) || background || snoozed(code)
                || status.kind != SelfUpdate.AVAILABLE && status.kind != SelfUpdate.READY) return;
        if (dialog != null && dialogKind == status.kind && dialogVersion == code) return;
        show(status.kind, code, AppStrings.get(R.string.settings_setup_update_offer) + " " + status.release.versionName,
                status.kind == SelfUpdate.READY ? R.string.settings_setup_install : R.string.settings_setup_download_install,
                () -> {
                    if (status.kind == SelfUpdate.READY) updater.install(activity);
                    else { installRequested = true; updater.download(); }
                }, true);
    }
    private void show(int kind, long code, String message, int positive, Runnable action, boolean postpone) {
        dismiss(); dialogKind = kind; dialogVersion = code;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(AppStrings.get(R.string.settings_tvos_update)).setMessage(message)
                .setPositiveButton(AppStrings.get(positive), null);
        if (postpone) builder.setNegativeButton(AppStrings.get(R.string.settings_setup_later), (d, w) -> later(code));
        dialog = builder.create();
        dialog.setOnCancelListener(d -> { if (postpone) later(code); else { background = true; installRequested = false; dismiss(); } });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { dismiss(); action.run(); });
    }
}
