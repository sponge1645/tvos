package io.tvos;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

/** Global remote app-key handling through the enabled accessibility key filter. */
final class RemoteKeys {
    private RemoteKeys() {}
    private static final int KEY_YOUTUBE_TCL = 4065;

    static boolean supported() {
        return "TCL".equalsIgnoreCase(Build.MANUFACTURER)
                || "TCL".equalsIgnoreCase(Build.BRAND)
                || "CHiQ".equalsIgnoreCase(Build.BRAND)
                || "CHiQ".equalsIgnoreCase(Build.MANUFACTURER);
    }

    static boolean onKey(Context c, KeyEvent e) {
        int source = e.getSource();
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return false;

        // CHiQ / MStar IR receiver: use both Android code and Linux scan code to
        // avoid mistaking unrelated keys on other remotes for app shortcuts.
        int key = -1;
        int code = e.getKeyCode();
        int scan = e.getScanCode();
        boolean chiq = false;
        if (code == 265 && scan == 161) { key = Prefs.KEY_NETFLIX; chiq = true; }
        else if (code == 247 && scan == 96) { key = Prefs.KEY_YOUTUBE; chiq = true; }
        else if (code == 249 && scan == 500) { key = Prefs.KEY_PRIME; chiq = true; }
        else if ("TCL".equalsIgnoreCase(Build.MANUFACTURER) || "TCL".equalsIgnoreCase(Build.BRAND)) {
            key = code == KEY_YOUTUBE_TCL ? Prefs.KEY_YOUTUBE : code;
            boolean recognized = false;
            for (int candidate : Prefs.KEYS) if (candidate == key) recognized = true;
            if (!recognized) return false;
        } else return false;

        String pkg = Prefs.keyApp(key);
        // CHiQ's firmware otherwise starts its own app, even if the choice is "Nothing".
        // TCL's historical behavior for "Nothing" is preserved.
        if (pkg.isEmpty()) return chiq;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            try {
                Intent intent = launchIntent(c.getPackageManager(), pkg);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    c.startActivity(intent);
                    Log.i("tvOS-RemoteKeys", "LAUNCH key=" + key + " pkg=" + pkg);
                } else Log.w("tvOS-RemoteKeys", "NOT_INSTALLED key=" + key + " pkg=" + pkg);
            } catch (Exception ex) {
                Log.e("tvOS-RemoteKeys", "LAUNCH_FAILED key=" + key + " pkg=" + pkg, ex);
            }
        }
        return true;
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }
}
