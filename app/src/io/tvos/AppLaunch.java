package io.tvos;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** External Android calls only. Expected refusals and unexpected firmware faults preserve caller fallbacks. */
final class AppLaunch {
    private AppLaunch() { }

    static boolean start(Context context, Intent intent, String operation) {
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            unavailable(operation, e);
        } catch (RuntimeException e) {
            AppLog.unexpected(operation, e);
        }
        return false;
    }

    static boolean start(Activity activity, Intent intent, Bundle options, String operation) {
        try {
            activity.startActivity(intent, options);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            unavailable(operation, e);
        } catch (RuntimeException e) {
            AppLog.unexpected(operation, e);
        }
        return false;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        try {
            Intent tv = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (tv != null) return tv;
        } catch (SecurityException e) {
            unavailable("TV launch intent", e);
        } catch (RuntimeException e) {
            AppLog.unexpected("TV launch intent", e);
        }
        try {
            return pm.getLaunchIntentForPackage(pkg);
        } catch (SecurityException e) {
            unavailable("Regular launch intent", e);
        } catch (RuntimeException e) {
            AppLog.unexpected("Regular launch intent", e);
        }
        return null;
    }

    private static void unavailable(String operation, RuntimeException failure) {
        // Exception messages can contain a title, query or deep-link URI.
        AppLog.w(operation + ": " + failure.getClass().getSimpleName());
    }
}
