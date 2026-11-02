package io.tvos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.util.Base64;
import io.tvos.remote.VolumeHoldRunner;
import io.tvos.remote.VolumeHoldStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Android bindings for the opt-in volume helper; never runs work in onKeyEvent. */
final class RemoteVolume implements AutoCloseable {
    static final String PERMISSION = "io.tvos.permission.VOLUME_CONTROL";
    static final String CHANGE = "io.tvos.VOLUME_HOLD_CHANGE", STATUS = "io.tvos.VOLUME_HOLD_STATUS";
    private final Context context;
    private final VolumeHoldRunner runner;
    private String lastStatus = "";
    private String helper;
    private boolean registered, closed;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { refresh(); }
    };

    RemoteVolume(Context context) {
        this.context = context.getApplicationContext();
        runner = new VolumeHoldRunner(this::connect, this::publish);
    }
    void start() {
        IntentFilter filter = new IntentFilter(CHANGE);
        filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter, PERMISSION, null);
        registered = true;
        refresh();
    }
    private void refresh() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        runner.update(VolumeHoldStore.enabled(context.getFilesDir()) && power != null && power.isInteractive());
    }
    void interrupted() { refresh(); }
    private synchronized void publish(String status) {
        if (!closed) writeStatus(status);
    }
    private void writeStatus(String status) {
        if (lastStatus.equals(status)) return;
        try {
            VolumeHoldStore.setStatus(context.getFilesDir(), status);
            lastStatus = status;
            context.sendBroadcast(new Intent(STATUS).setPackage(context.getPackageName()));
        } catch (IOException error) { AppLog.w("volume status: " + error.getMessage()); }
    }
    private VolumeHoldRunner.Connection connect() throws IOException {
        if (helper == null) {
            try (InputStream in = context.getAssets().open("volume-hold.jar")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] bytes = new byte[4096]; int size;
                while ((size = in.read(bytes)) != -1) {
                    if (out.size() + size > 65536) throw new IOException("Volume helper exceeds transfer bound");
                    out.write(bytes, 0, size);
                }
                helper = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }
        }
        final Adb adb = Adb.connect(context, 10000, null);
        final String path = "/data/local/tmp/tvos-volume-" + UUID.randomUUID().toString();
        // Only generated hex/path characters and base64 enter the shell. The shell owns cleanup.
        final String command = "mkdir " + path + " && { trap 'rm -f " + path + "/helper.jar; rmdir " + path
                + "' EXIT; trap 'exit' HUP INT TERM; printf '%s' '" + helper + "' | base64 -d > " + path
                + "/helper.jar && CLASSPATH=" + path + "/helper.jar app_process /data/local/tmp io.tvos.tools.VolumeHoldDevice audio quiet; }";
        return new VolumeHoldRunner.Connection() {
            @Override public void run(BooleanSupplier active, Runnable ready) throws IOException {
                final StringBuilder line = new StringBuilder();
                final long started = System.nanoTime();
                final java.util.concurrent.atomic.AtomicBoolean readySeen = new java.util.concurrent.atomic.AtomicBoolean();
                adb.stream(command, new Adb.Stream() {
                    @Override public boolean active() {
                        return active.getAsBoolean() && (readySeen.get() || System.nanoTime() - started < 15000000000L);
                    }
                    @Override public void output(byte[] bytes) throws IOException {
                        for (byte value : bytes) {
                            if (value == '\n') {
                                String text = line.toString(); line.setLength(0);
                                if (text.startsWith("READY device helper mode=audio")) { readySeen.set(true); ready.run(); }
                                else if (text.contains("ERROR") || text.contains("Exception")) AppLog.w("volume helper: " + text);
                            } else if (line.length() < 1024) line.append((char) (value & 255));
                            else throw new IOException("Volume helper output exceeds line bound");
                        }
                    }
                });
            }
            @Override public void close() { adb.close(); }
        };
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        runner.close(); // Stop input before status I/O or receiver cleanup.
        if (registered) { context.unregisterReceiver(receiver); registered = false; }
        writeStatus(VolumeHoldStore.enabled(context.getFilesDir()) ? "WAITING" : "OFF");
    }
}
