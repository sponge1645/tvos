package io.tvos;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Coalesced initialization requests; never replace Seena's pending 45-second job on every resume. */
final class SeenaChannelRefresh {
    private static final SeenaChannelRefresh INSTANCE = new SeenaChannelRefresh(Executors.newSingleThreadScheduledExecutor());
    private final ScheduledExecutorService io;
    private static final long INTERVAL = 90_000;
    private long nextAt;
    private boolean pending;
    SeenaChannelRefresh(ScheduledExecutorService io) { this.io = io; }

    static void request(Context supplied) { INSTANCE.enqueue(supplied); }

    synchronized void enqueue(Context supplied) {
        if (pending) return;
        Context context = supplied.getApplicationContext();
        pending = true;
        io.schedule(() -> {
            try {
                send(context);
                Log.i("tvOS-WatchNext", "Seena channel refresh requested; publication starts after its 45-second delay");
            } catch (IOException | RuntimeException e) {
                Log.w("tvOS-WatchNext", "Seena refresh request failed: " + e.getClass().getSimpleName());
            } finally {
                synchronized (this) {
                    nextAt = SystemClock.elapsedRealtime() + INTERVAL;
                    pending = false;
                }
            }
        }, Math.max(0, nextAt - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
    }

    static void send(Context context) throws IOException {
        try {
            context.sendBroadcast(new Intent("android.media.tv.action.INITIALIZE_PROGRAMS")
                    .setComponent(new ComponentName("com.seena.app", "com.seena.app.tv.TvChannelInitializerReceiver")));
            return;
        } catch (SecurityException e) {
            // Some firmware permits this system action only from shell.
        }
        try (Adb adb = Adb.connect(context, 15000, null)) {
            String response = adb.shell("am broadcast -n com.seena.app/com.seena.app.tv.TvChannelInitializerReceiver -a android.media.tv.action.INITIALIZE_PROGRAMS");
            if (!accepted(response)) throw new IOException("Seena initialization broadcast rejected");
        }
    }

    static boolean accepted(String response) {
        return response != null && response.contains("Broadcast completed")
                && !response.contains("Permission Denial") && !response.contains("SecurityException");
    }
}
