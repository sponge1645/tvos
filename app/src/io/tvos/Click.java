package io.tvos;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Preloaded, short navigation samples. No file I/O or decoding on key presses. */
final class Click {
    private final Context context;
    private SoundPool pool;
    private final Set<Integer> ready = new HashSet<>();
    private int move, app, open, back, movementStream, actionStream;

    Click(Context c) {
        context = c.getApplicationContext();
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool.setOnLoadCompleteListener((loadedPool, id, status) -> {
                if (loadedPool == pool && status == 0) ready.add(id);
            });
            move = load("move_button"); app = load("move_app");
            open = load("open"); back = load("back");
        } catch (IOException | RuntimeException e) {
            AppLog.w("Navigation sounds unavailable: " + e.getClass().getSimpleName());
            release();
        }
    }
    private int load(String name) throws IOException {
        try (AssetFileDescriptor file = context.getAssets().openFd("sounds/" + name + ".wav")) {
            return pool.load(file.getFileDescriptor(), file.getStartOffset(), file.getLength(), 1);
        }
    }
    void move() { play(move, false); }
    void app() { play(app, false); }
    void open() { play(open, true); }
    void back() { play(back, true); }
    private void play(int id, boolean action) {
        if (pool == null || !ready.contains(id) || !context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("navigation_clicks", true)) return;
        if (movementStream != 0) { pool.stop(movementStream); movementStream = 0; }
        if (action) {
            if (actionStream != 0) pool.stop(actionStream);
            actionStream = pool.play(id, .35f, .35f, 2, 0, 1f);
        } else movementStream = pool.play(id, .28f, .28f, 1, 0, 1f);
    }
    void release() {
        if (pool != null) { pool.setOnLoadCompleteListener(null); pool.release(); }
        pool = null; ready.clear(); movementStream = actionStream = 0;
    }
}
