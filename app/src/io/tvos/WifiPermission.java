package io.tvos;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Request location once so Android may expose the connected Wi-Fi SSID. */
final class WifiPermission {
    private WifiPermission() {}

    static void requestOnce(Activity activity) {
        if (Build.VERSION.SDK_INT < 23 ||
                activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences sp = activity.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (sp.getBoolean("wifi_ssid_permission_requested", false)) return;
        sp.edit().putBoolean("wifi_ssid_permission_requested", true).apply();
        activity.requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION}, 5041);
    }
}
