package io.tvos.remote;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One serialized connection, cancellation generations and a bounded reconnect burst. */
public final class VolumeHoldRunner implements Closeable {
    public interface Connection extends Closeable { void run(BooleanSupplier active, Runnable ready) throws IOException; }
    public interface Connector { Connection open() throws IOException; }
    private final Connector connector;
    private final Consumer<String> status;
    private final ScheduledExecutorService executor;
    private final LongSupplier clock;
    private long generation;
    private boolean closed;
    private Connection connection;
    private ScheduledFuture<?> task;
    private static final long[] RETRY = {1000, 2000, 5000, 10000};

    public VolumeHoldRunner(Connector connector, Consumer<String> status) {
        this(connector, status, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "tvos-volume"); thread.setDaemon(true); return thread;
        }), () -> System.nanoTime() / 1000000);
    }
    public VolumeHoldRunner(Connector connector, Consumer<String> status,
                            ScheduledExecutorService executor, LongSupplier clock) {
        this.connector = connector; this.status = status; this.executor = executor; this.clock = clock;
    }
    public synchronized void update(boolean enabled) {
        if (closed) return;
        final long token = ++generation;
        if (task != null) task.cancel(true);
        disconnect();
        task = executor.schedule(() -> {
            if (enabled) attempt(token, 0);
            else publish(token, "OFF");
        }, 0, TimeUnit.MILLISECONDS);
    }
    private synchronized boolean active(long token) { return !closed && token == generation; }
    private void publish(long token, String value) { if (active(token)) status.accept(value); }
    private void attempt(long token, int failures) {
        if (!active(token)) return;
        publish(token, "STARTING");
        final long[] readyAt = {-1};
        try (Connection opened = connector.open()) {
            synchronized (this) {
                if (!active(token)) return;
                connection = opened;
            }
            opened.run(() -> active(token), () -> {
                if (active(token)) { readyAt[0] = clock.getAsLong(); publish(token, "READY"); }
            });
        } catch (IOException | RuntimeException error) {
            // A cancelled generation never changes status or schedules another connection.
        } finally {
            synchronized (this) { if (active(token)) connection = null; }
        }
        if (!active(token)) return;
        int count = readyAt[0] >= 0 && clock.getAsLong() - readyAt[0] >= 30000 ? -1 : failures;
        synchronized (this) {
            if (!active(token)) return;
            if (count < RETRY.length) {
                task = executor.schedule(() -> attempt(token, count + 1), RETRY[Math.max(0, count)], TimeUnit.MILLISECONDS);
                return;
            }
        }
        publish(token, "ERROR");
    }
    private void disconnect() {
        if (connection != null) {
            try { connection.close(); } catch (IOException ignored) { }
            connection = null;
        }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; generation++;
        if (task != null) task.cancel(true);
        disconnect();
        executor.shutdownNow();
    }
}
