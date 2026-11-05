package io.tvos;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses Android's inactivity policy, rather than a launcher timer that interrupts playback. */
final class SleepMode {
    private static final int[] MINUTES = {5, 10, 15, 30, 60, 120, 240};
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private SleepMode() { }

    interface Shell { String run(String command) throws IOException; }

    static void apply(Shell shell, int choice) throws IOException {
        if (choice == -1) {
            if (!shell.run("input keyevent 223").trim().isEmpty()) throw new IOException("Sleep request rejected");
            return;
        }
        if (choice < 0 || choice >= MINUTES.length) throw new IllegalArgumentException("Sleep choice");
        long millis = MINUTES[choice] * 60_000L;
        String written = shell.run("settings --user current put secure sleep_timeout " + millis).trim();
        if (!written.isEmpty()) throw new IOException("Sleep timeout rejected");
        if (!Long.toString(millis).equals(shell.run("settings --user current get secure sleep_timeout").trim()))
            throw new IOException("Sleep timeout not saved");
    }

    static void request(Context context, int choice, Runnable success) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (!BUSY.compareAndSet(false, true)) {
            Toast.makeText(app, AppStrings.get(R.string.settings_sleep_busy), Toast.LENGTH_SHORT).show();
            return;
        }
        IO.execute(() -> {
            boolean saved = false;
            try (Adb adb = Adb.connect(app, 120_000, () -> main.post(() ->
                    Toast.makeText(app, AppStrings.get(R.string.runtime_accept_allow_usb_debugging_on_the_tv_tick_always_allow), Toast.LENGTH_LONG).show()))) {
                apply(adb::shell, choice);
                if (choice >= 0) app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("sleep_choice", choice).apply();
                saved = true;
            } catch (IOException | RuntimeException error) {
                AppLog.w("Sleep setting unavailable: " + error.getClass().getSimpleName());
            }
            final boolean ready = saved;
            main.post(() -> {
                BUSY.set(false);
                if (!ready || choice >= 0) Toast.makeText(app, AppStrings.get(ready
                        ? R.string.settings_sleep_saved : R.string.settings_sleep_failed), Toast.LENGTH_LONG).show();
                if (ready) success.run();
            });
        });
    }
}
