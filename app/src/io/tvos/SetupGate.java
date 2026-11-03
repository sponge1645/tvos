package io.tvos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import io.tvos.setup.SetupState;

/** Fresh installs enter setup; configured upgrades keep their settings. Completion is explicit. */
final class SetupGate {
    private SetupGate() { }
    private static boolean deferred;
    static void defer() { deferred = true; }
    static void restart() { deferred = false; }
    static boolean pending(Context c) {
        SharedPreferences setup = c.getSharedPreferences("setup", Context.MODE_PRIVATE);
        if (setup.getBoolean("complete", false)) return false;
        boolean upgrade = false;
        try {
            android.content.pm.PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            upgrade = p.firstInstallTime > 0 && p.lastUpdateTime > p.firstInstallTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException error) { return false; }
        boolean needed = SetupState.needed(false, setup.getBoolean("started", false), upgrade,
                !c.getSharedPreferences("settings", Context.MODE_PRIVATE).getAll().isEmpty());
        if (!needed) setup.edit().putBoolean("complete", true).apply();
        return needed;
    }
    static boolean blocking(Context c) { return !deferred && pending(c); }
    static void route(Activity a) {
        if (!deferred && !(a instanceof SetupActivity) && pending(a)) a.startActivity(new Intent(a, SetupActivity.class));
    }
}
