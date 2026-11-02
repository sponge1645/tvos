package io.tvos;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Experimental privileged listener. No dumps, polling or global hidden-API policy changes. */
final class SystemVolume implements AutoCloseable {
    static final String PERMISSION = "android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER";
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Object manager, listener;
    private Method setter;
    private int direction, steps;
    private long started;
    private boolean active, blocked;
    private int generation;
    SystemVolume(Context context) { this.context = context; }
    boolean start() {
        if (active) return true;
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return false;
        final int token = ++generation;
        try {
            manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return false;
            Class<?> type = Class.forName("android.media.session.MediaSessionManager$OnVolumeKeyLongPressListener");
            setter = manager.getClass().getMethod("setOnVolumeKeyLongPressListener", type, Handler.class);
            listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                if ("onVolumeKeyLongPress".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof KeyEvent) {
                    if (active && token == generation) key((KeyEvent) args[0]);
                } else if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                else if ("equals".equals(method.getName())) return args != null && args.length == 1 && proxy == args[0];
                else if ("toString".equals(method.getName())) return "tvOS volume listener";
                return null;
            });
            setter.invoke(manager, listener, handler); active = true; return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            AppLog.w("System volume listener unavailable: " + error.getClass().getSimpleName());
            close(); return false;
        }
    }
    private void key(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP || event.isCanceled()) { stop(); blocked = false; return; }
        if (event.getAction() != KeyEvent.ACTION_DOWN || blocked) return;
        int d = event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP ? 1
                : event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN ? -1 : 0;
        if (d == 0 || direction == d) return;
        stop(); direction = d; started = SystemClock.uptimeMillis(); steps = 0; step.run();
    }
    private final Runnable step = new Runnable() {
        @Override public void run() {
            if (!active || direction == 0) return;
            if (steps >= 100 || SystemClock.uptimeMillis() - started >= 6000) { blocked = true; stop(); return; }
            try {
                AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (audio == null) { stop(); return; }
                audio.adjustSuggestedStreamVolume(direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                steps++; handler.postDelayed(this, 85);
            } catch (RuntimeException error) { stop(); }
        }
    };
    private void stop() { direction = 0; handler.removeCallbacks(step); }
    @Override public void close() {
        generation++; active = false; blocked = false; stop();
        if (manager != null && setter != null && listener != null) {
            try { setter.invoke(manager, null, null); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError error) { AppLog.w("System volume listener cleanup unavailable"); }
        }
        manager = null; setter = null; listener = null;
    }
}
