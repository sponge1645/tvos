package io.tvos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import io.tvos.remote.KeyboardHandoff;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Temporary, foreground ADB controller, alive independently of the selected input method. */
public class RemoteKeyboardBridge extends Service {
    private static final String CHANNEL = "keyboard-handoff", RESTORE = "restore";
    private static final int NOTIFICATION = 1942;
    private static volatile long quietUntil;
    private volatile boolean running, restoreRequested;
    private volatile Adb connection;
    private Thread worker;

    static String ownId(Context c) { return new ComponentName(c, RemoteInputMethod.class).flattenToShortString(); }
    static boolean coolingDown() { return SystemClock.elapsedRealtime() < quietUntil; }
    static List<InputMethodInfo> keyboards(Context c) {
        InputMethodManager manager = (InputMethodManager) c.getSystemService(INPUT_METHOD_SERVICE);
        List<InputMethodInfo> result = new ArrayList<>();
        if (manager != null) for (InputMethodInfo info : manager.getEnabledInputMethodList())
            if (!c.getPackageName().equals(info.getPackageName()) && KeyboardHandoff.validId(info.getId())) result.add(info);
        return result;
    }
    static String target(Context c) {
        String saved = KeyboardHandoff.readTarget(c.getFilesDir());
        List<InputMethodInfo> keyboards = keyboards(c);
        for (InputMethodInfo info : keyboards) if (info.getId().equals(saved)) return saved;
        for (InputMethodInfo info : keyboards) if ("com.google.android.inputmethod.latin".equals(info.getPackageName())
                || "com.google.android.tv.inputmethod".equals(info.getPackageName())) return info.getId();
        return keyboards.isEmpty() ? "" : keyboards.get(0).getId();
    }
    static boolean request(Context context) {
        if (coolingDown()) return false;
        try {
            context.startForegroundService(new Intent(context, RemoteKeyboardBridge.class));
            return true;
        } catch (RuntimeException unavailable) {
            Toast.makeText(context, AppStrings.get(R.string.settings_remote_keyboard_failed), Toast.LENGTH_LONG).show();
            return false;
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                AppStrings.get(R.string.settings_remote_text_keyboard), NotificationManager.IMPORTANCE_LOW));
        PendingIntent restore = PendingIntent.getService(this, 0, new Intent(this, RemoteKeyboardBridge.class).setAction(RESTORE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(NOTIFICATION, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(AppStrings.get(R.string.settings_remote_text_keyboard))
                .setContentText(AppStrings.get(R.string.settings_remote_keyboard_monitoring))
                .setContentIntent(restore).setOngoing(true).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && RESTORE.equals(intent.getAction())) restoreRequested = true;
        if (worker == null) {
            running = true;
            worker = new Thread(this::run, "keyboard-handoff"); worker.start();
        }
        return START_NOT_STICKY;
    }
    private String selected() {
        try { return Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD); }
        catch (RuntimeException unavailable) { return ""; }
    }
    private static boolean same(String a, String b) {
        ComponentName first = ComponentName.unflattenFromString(a == null ? "" : a);
        return first != null && first.equals(ComponentName.unflattenFromString(b == null ? "" : b));
    }
    private void run() {
        String target = "", own = ownId(this);
        boolean switchAttempted = false, restore = false, failed = false;
        try {
            target = target(this);
            if (target.isEmpty()) throw new IOException("No enabled text keyboard");
            connection = Adb.connect(this, 5000, null);
            if (!running) return;
            if (restoreRequested) { restore = same(selected(), target); return; }
            // Delegate only while tvOS is still selected. A manual choice always wins.
            if (same(selected(), own)) {
                String before = connection.shell("dumpsys input_method | grep -m 1 'mInputShown='");
                if (KeyboardHandoff.shown(before) == null) throw new IOException("Unsupported IME state format");
                // Only validated enabled component IDs enter the fixed shell command.
                if (!same(selected(), own)) return;
                switchAttempted = true;
                connection.shell("ime set '" + target + "'");
            } else return;
            if (!same(selected(), target)) throw new IOException("Text keyboard switch rejected");
            KeyboardHandoff policy = new KeyboardHandoff(target, SystemClock.elapsedRealtime());
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            while (running) {
                if (restoreRequested) { restore = true; break; }
                String current = selected();
                if (!same(current, target)) break;
                String state = connection.shell("dumpsys input_method | grep -m 1 'mInputShown='");
                KeyboardHandoff.Result result = policy.step(target, state, SystemClock.elapsedRealtime(), power != null && power.isInteractive());
                if (result != KeyboardHandoff.Result.WAIT) { restore = result == KeyboardHandoff.Result.RESTORE; break; }
                Thread.sleep(400);
            }
        } catch (IOException | RuntimeException error) {
            failed = true; restore = switchAttempted && same(selected(), target);
            Adb adb = connection; connection = null; if (adb != null) adb.close();
            AppLog.w("Keyboard handoff unavailable: " + error.getClass().getSimpleName());
        } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
        finally {
            if (running && restore && same(selected(), target)) {
                quietUntil = SystemClock.elapsedRealtime() + 2000;
                try {
                    if (connection == null) connection = Adb.connect(this, 1000, null);
                    if (same(selected(), target)) {
                        connection.shell("ime set '" + own + "'");
                        if (!same(selected(), own)) failed = true;
                    }
                } catch (IOException | RuntimeException error) { failed = true; }
            }
            Adb adb = connection; connection = null; if (adb != null) adb.close();
            if (failed) new android.os.Handler(getMainLooper()).post(() ->
                    Toast.makeText(this, AppStrings.get(R.string.settings_remote_keyboard_failed), Toast.LENGTH_LONG).show());
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        running = false;
        Adb adb = connection; if (adb != null) adb.close();
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
