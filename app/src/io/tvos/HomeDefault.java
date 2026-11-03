package io.tvos;

import android.app.Activity;
import android.app.role.RoleManager;
import android.os.Build;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Native Home selection only: never disables stock packages or changes tvOS mode implicitly. */
final class HomeDefault {
    private HomeDefault() { }
    static boolean selected(Context c) {
        try {
            android.content.pm.ResolveInfo found = c.getPackageManager().resolveActivity(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return found != null && found.activityInfo != null && c.getPackageName().equals(found.activityInfo.packageName);
        } catch (RuntimeException error) { return false; }
    }
    @SuppressWarnings("deprecation") // Native role result API supports Android 9 without AndroidX.
    static void request(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager roles = a.getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    a.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 5043); return;
                }
            }
            a.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (RuntimeException error) {
            try { a.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException ignored) { AppLog.w("Default Home settings unavailable"); }
        }
    }
}
