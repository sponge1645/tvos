package io.tvos;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

/** Restore missing volume repeats on TVs whose accessibility input filter disables autorepeat. */
final class VolumeKeyRepeat {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private int code, device;
    private long downTime, deadline;
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (code == 0 || SystemClock.uptimeMillis() >= deadline) { cancel(); return; }
            try {
                audio.adjustSuggestedStreamVolume(code == KeyEvent.KEYCODE_VOLUME_UP
                        ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                        AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
            } catch (RuntimeException e) {
                cancel(); AppLog.w("Volume repeat unavailable: " + e.getClass().getSimpleName()); return;
            }
            handler.postDelayed(this, 80);
        }
    };
    VolumeKeyRepeat(Context context) { audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE); }
    void onKey(KeyEvent event) {
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) cancel();
            return;
        }
        if (event.getAction() == KeyEvent.ACTION_UP || event.isCanceled()) {
            if (key == code && event.getDeviceId() == device) cancel();
            return;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return;
        if (event.getRepeatCount() > 0) {
            // Firmware already supplies repeats: originals pass through, never add our own.
            cancel(); return;
        }
        if (key == code && event.getDeviceId() == device && event.getDownTime() == downTime) return;
        cancel();
        if (audio == null) return;
        code = key; device = event.getDeviceId(); downTime = event.getDownTime();
        // Bound a lost release; a normal release always cancels immediately.
        deadline = SystemClock.uptimeMillis() + 30000;
        handler.postDelayed(repeat, ViewConfiguration.getKeyRepeatTimeout() + 100L);
    }
    void cancel() { code = 0; handler.removeCallbacks(repeat); }
}
