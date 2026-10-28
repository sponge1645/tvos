package io.tvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

/** Device-name and maintenance confirmations; no settings navigation or drawing. */
final class SettingsDeviceDialogs {
    private final Activity activity;
    private final Runnable changed;

    SettingsDeviceDialogs(Activity activity, Runnable changed) {
        this.activity = activity;
        this.changed = changed;
    }

    private SharedPreferences localPrefs() {
        return activity.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static boolean deleteCacheContents(java.io.File dir) {
        if (dir == null || !dir.exists()) return true;
        boolean ok = true;
        java.io.File[] children = dir.listFiles();
        if (children == null) return false;
        for (java.io.File child : children) {
            if (child.isDirectory() && !java.nio.file.Files.isSymbolicLink(child.toPath()))
                ok &= deleteCacheContents(child);
            if (!child.delete()) ok = false;
        }
        return ok;
    }

    void confirmClearCache() {
        new AlertDialog.Builder(activity)
                .setTitle(AppStrings.get(R.string.settings_clear_cache_confirmation))
                .setMessage(AppStrings.get(R.string.settings_temporary_posters_images_and_screensaver_cache_will_be_deleted_se))
                .setNegativeButton(AppStrings.get(R.string.settings_cancel), null)
                .setPositiveButton(AppStrings.get(R.string.settings_clear), (dialog, which) -> {
                    boolean ok = deleteCacheContents(activity.getCacheDir());
                    java.io.File external = activity.getExternalCacheDir();
                    if (external != null) ok &= deleteCacheContents(external);
                    android.widget.Toast.makeText(activity,
                            ok ? AppStrings.get(R.string.settings_cache_cleared) : AppStrings.get(R.string.settings_some_cache_could_not_be_cleared),
                            android.widget.Toast.LENGTH_SHORT).show();
                }).show();
    }

    void confirmResetTvos() {
        new AlertDialog.Builder(activity)
                .setTitle(AppStrings.get(R.string.settings_reset_tvos_confirmation))
                .setMessage(AppStrings.get(R.string.settings_all_tvos_settings_selected_apps_and_app_data_will_be_erased_this_))
                .setNegativeButton(AppStrings.get(R.string.settings_cancel), null)
                .setPositiveButton(AppStrings.get(R.string.settings_reset_tvos_3), (dialog, which) -> {
                    android.app.ActivityManager am = (android.app.ActivityManager)
                            activity.getSystemService(Context.ACTIVITY_SERVICE);
                    if (am == null || !am.clearApplicationUserData())
                        android.widget.Toast.makeText(activity,
                                AppStrings.get(R.string.settings_could_not_reset_tvos),
                                android.widget.Toast.LENGTH_LONG).show();
                }).show();
    }

    void askDeviceName() {
        final android.widget.EditText input =
                new android.widget.EditText(activity);
        input.setSingleLine(true);
        input.setText(localPrefs().getString("device_name", "ТВ"));
        input.selectAll();

        new android.app.AlertDialog.Builder(activity)
                .setTitle(AppStrings.get(R.string.settings_name))
                .setView(input)
                .setNegativeButton(AppStrings.get(R.string.settings_cancel), null)
                .setPositiveButton(AppStrings.get(R.string.settings_save), (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "ТВ";
                    localPrefs().edit()
                            .putString("device_name", name).apply();
                    changed.run();
                })
                .show();

        input.requestFocus();
    }

}
