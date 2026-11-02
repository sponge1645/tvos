package io.tvos;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit supported actions; no arbitrary shell, key injection or unbounded work queue. */
final class RemoteShortcutActions {
    private RemoteShortcutActions() { }
    private static final AtomicBoolean vpnBusy = new AtomicBoolean();
    private static final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "remote-actions"); t.setDaemon(true); return t;
    });
    static void run(Context context, String action) {
        Context c = context.getApplicationContext();
        if ("vpn".equals(action)) {
            if (!vpnBusy.compareAndSet(false, true)) return;
            io.execute(() -> {
                try { VpnControl.toggle(c); }
                catch (IOException | RuntimeException error) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                            Toast.makeText(c, AppStrings.get(R.string.home_vpn_toggle_failed), Toast.LENGTH_LONG).show());
                } finally { vpnBusy.set(false); }
            });
            return;
        }
        try {
            if ("settings".equals(action)) {
                c.startActivity(new Intent(c, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (action.startsWith("app:")) {
                String pkg = action.substring(4);
                Intent intent = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
                if (intent == null) intent = c.getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                AudioManager audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
                if (audio == null) return;
                if ("mute".equals(action)) audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_TOGGLE_MUTE,
                        AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                else {
                    int code = "play".equals(action) ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            : "next".equals(action) ? KeyEvent.KEYCODE_MEDIA_NEXT
                            : "previous".equals(action) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : 0;
                    if (code != 0) {
                        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                    }
                }
            }
        } catch (RuntimeException error) { AppLog.w("Remote action unavailable: " + action); }
    }
}
