package io.tvos;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/** One-shot CHiQ/MediaTek boot race workaround. Never affects normal TV usage. */
final class ChiqBootGuard {
    private static final String TAG = "tvOS-BootGuard";
    private static final String PREF = "chiq_boot_guard";
    private static final String START = "home_start_elapsed";
    private static final String DONE = "done";
    private static final long WINDOW_MS = 12000L;
    private ChiqBootGuard() {}

    private static boolean isTarget(Context context) {
        PackageManager pm = context.getPackageManager();
        return installed(pm, "com.mediatek.wwtv.tvcenter")
                && installed(pm, "com.mediatek.tv.service")
                && installed(pm, "com.homwee.factory");
    }
    private static boolean installed(PackageManager pm, String pkg) {
        try { pm.getPackageInfo(pkg, 0); return true; }
        catch (Exception ignored) { return false; }
    }

    static void homeCreated(Context context) {
        if (!isTarget(context)) return;
        long elapsed = SystemClock.elapsedRealtime();
        // A real Android reboot, not waking from standby or manually opening HOME.
        if (elapsed > 90000L) return;
        SharedPreferences p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long prior = p.getLong(START, -1L);
        // Only arm once per boot. elapsedRealtime resets on a real reboot.
        if (prior >= 0 && prior <= elapsed) return;
        p.edit().putLong(START, elapsed).putBoolean(DONE, false).commit();
        Log.i(TAG, "Armed for CHiQ cold boot");
    }

    static void onWindowEvent(AccessibilityService service, AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (event.getPackageName() == null || !"com.mediatek.wwtv.tvcenter".contentEquals(event.getPackageName())) return;
        SharedPreferences p = service.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long started = p.getLong(START, -1L);
        long elapsed = SystemClock.elapsedRealtime();
        if (started < 0 || elapsed < started || elapsed - started > WINDOW_MS || p.getBoolean(DONE, false)) return;
        // Consume before issuing HOME to avoid any re-entrancy or loops.
        p.edit().putBoolean(DONE, true).commit();
        boolean ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        Log.i(TAG, "TV Center appeared during boot; HOME requested=" + ok);
    }
}
