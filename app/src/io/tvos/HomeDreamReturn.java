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
    private static final String OPAQUE = "io.tvos.DREAM_OPAQUE";
    static void opaque(Context context) {
        context.sendBroadcast(new Intent(OPAQUE).setPackage(context.getPackageName()));
    }
    private final BroadcastReceiver opaqueReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (OPAQUE.equals(intent.getAction()) && dreaming && armed && !staged) {
                staged = true;
                prepare.run(); // The translucent entrance has finished; changing home is now hidden.
            }
        }
    };
    private final Context context;
    private final Runnable prepare, reveal, cancel;
    private long pendingUntil;
    private boolean visible, resumed, focused, dreaming, armed, pending, staged;

    HomeDreamReturn(Context context, Runnable prepare, Runnable reveal, Runnable cancel) {
        this.context = context;
        this.prepare = prepare;
        this.reveal = reveal;
        this.cancel = cancel;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(this, filter);
        IntentFilter own = new IntentFilter(OPAQUE);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(opaqueReceiver, own, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(opaqueReceiver, own);
    }

    void started() { expire(); visible = true; }
    void resumed() {
        resumed = true;
        // Lifecycle callbacks run before the resumed window draws. Do not wait for a late STOP
        // broadcast or for focus (some TV firmware delivers both after the first visible frame).
        if (!expire() && armed) play();
    }
    void paused() { resumed = false; focused = false; }
    void stopped() { visible = false; }
    void focused(boolean value) {
        boolean gained = value && !focused;
        focused = value;
        // Dreams on older firmware can leave the activity resumed and only change window focus.
        if (!expire() && gained && resumed && armed) play();
    }
    void close() {
        context.unregisterReceiver(this);
        context.unregisterReceiver(opaqueReceiver);
        cancel.run();
        visible = resumed = focused = dreaming = armed = pending = staged = false;
    }

    @Override public void onReceive(Context ignored, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action) && "dream".equals(intent.getStringExtra("reason"))) {
            // DreamController sends this before attaching its window. Retain the underlying home
            // even when DreamActivity pauses/stops it before DREAMING_STARTED is delivered.
            if (!dreaming) armed = visible;
        } else if (Intent.ACTION_DREAMING_STARTED.equals(action)) {
            if (!dreaming) {
                armed |= visible;
                staged = false; // Leave home intact while the new dream window is still translucent.
            }
            dreaming = true;
        } else if (Intent.ACTION_DREAMING_STOPPED.equals(action) && dreaming) {
            pending = armed;
            pendingUntil = SystemClock.uptimeMillis() + 3000;
            dreaming = false;
            if (pending && resumed && focused) play();
        }
    }

    private boolean expire() {
        if (!pending || SystemClock.uptimeMillis() <= pendingUntil) return false;
        pending = armed = false;
        cancel.run();
        return true;
    }

    private void play() {
        pending = armed = false; // A late/duplicate STOP must never restart the entrance.
        if (staged) reveal.run();
        else cancel.run(); // Interrupted entrance: keep the still-visible home unchanged.
        staged = false;
    }
}
