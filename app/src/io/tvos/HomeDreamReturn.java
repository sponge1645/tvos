package io.tvos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;

/** Tracks a real system dream over a visible home, independently of DreamService's process. */
@SuppressWarnings("deprecation") // Observe the system-only close-dialogs signal on old and new firmware.
final class HomeDreamReturn extends BroadcastReceiver {
    private final Context context;
    private final Runnable reveal;
    private long pendingUntil;
    private boolean visible, resumed, focused, dreaming, armed, pending;

    HomeDreamReturn(Context context, Runnable reveal) {
        this.context = context;
        this.reveal = reveal;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(this, filter);
    }

    void started() { visible = true; }
    void resumed() { resumed = true; deliver(); }
    void paused() { resumed = false; focused = false; }
    void stopped() { visible = false; }
    void focused(boolean value) {
        focused = value;
        deliver();
        if (value && !dreaming && !pending) armed = false;
    }
    void close() {
        context.unregisterReceiver(this);
        visible = resumed = focused = dreaming = armed = pending = false;
    }

    @Override public void onReceive(Context ignored, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action) && "dream".equals(intent.getStringExtra("reason"))) {
            // DreamController sends this before attaching its window. Retain the underlying home
            // even when DreamActivity pauses/stops it before DREAMING_STARTED is delivered.
            if (!dreaming) armed = visible;
        } else if (Intent.ACTION_DREAMING_STARTED.equals(action)) {
            if (!dreaming) armed |= visible;
            dreaming = true;
        } else if (Intent.ACTION_DREAMING_STOPPED.equals(action) && dreaming) {
            pending = dreaming && armed;
            pendingUntil = SystemClock.uptimeMillis() + 3000;
            dreaming = armed = false;
            deliver();
        }
    }

    private void deliver() {
        if (pending && SystemClock.uptimeMillis() > pendingUntil) pending = false;
        if (!pending || !resumed || !focused || dreaming) return;
        pending = false;
        reveal.run();
    }
}
