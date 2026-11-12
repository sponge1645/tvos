package io.tvos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.UUID;
import java.util.function.Consumer;

/** Signature-protected, short-lived capture session between Settings and the accessibility process. */
final class RemoteKeyCapture implements AutoCloseable {
    static final String COMMAND = "io.tvos.REMOTE_CAPTURE_COMMAND";
    static final String EVENT = "io.tvos.REMOTE_CAPTURE_EVENT";
    static final String PERMISSION = "io.tvos.permission.REMOTE_CONTROL";
    private final Context context;
    private final Consumer<KeyEvent> sink;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String token = "";
    private boolean closed;
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (closed || token.isEmpty() || !token.equals(i.getStringExtra("token"))) return;
            KeyEvent event = event(i);
            if (event != null) sink.accept(event);
        }
    };
    private final Runnable renew = new Runnable() {
        @Override public void run() {
            if (closed || token.isEmpty()) return;
            context.sendBroadcast(new Intent(COMMAND).setPackage(context.getPackageName())
                    .putExtra("token", token).putExtra("until", SystemClock.elapsedRealtime() + 5000), PERMISSION);
            handler.postDelayed(this, 2000);
        }
    };
    RemoteKeyCapture(Context context, Consumer<KeyEvent> sink) {
        this.context = context; this.sink = sink;
        IntentFilter filter = new IntentFilter(EVENT);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(events, filter, PERMISSION, handler, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(events, filter, PERMISSION, handler);
    }
    @SuppressWarnings("deprecation")
    static KeyEvent event(Intent intent) { return intent.getParcelableExtra("key"); }
    void start() {
        if (closed || !token.isEmpty()) return;
        token = UUID.randomUUID().toString(); renew.run();
    }
    void stop() {
        if (token.isEmpty()) return;
        String old = token; token = ""; handler.removeCallbacks(renew);
        context.sendBroadcast(new Intent(COMMAND).setPackage(context.getPackageName())
                .putExtra("token", old).putExtra("until", 0L), PERMISSION);
    }
    @Override public void close() {
        if (closed) return;
        stop(); closed = true; context.unregisterReceiver(events);
    }
}
