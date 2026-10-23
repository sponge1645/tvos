package io.tvos;

import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;

/** Cooperative cancellation: skip queued work and delivery without interrupting a shared worker. */
final class UiRequest {
    private final ThreadPoolExecutor executor;
    private volatile boolean cancelled;
    private volatile FutureTask<Void> task;

    UiRequest(ThreadPoolExecutor executor) { this.executor = executor; }

    boolean active() { return !cancelled; }

    void start(Runnable work) {
        FutureTask<Void> submitted = new FutureTask<>(() -> {
            if (active()) work.run();
            return null;
        });
        task = submitted;
        if (!active()) { submitted.cancel(false); return; }
        executor.execute(submitted);
        // Also cover cancellation between the first check and queue insertion.
        if (!active()) {
            submitted.cancel(false);
            executor.remove(submitted);
        }
    }

    void cancel() {
        cancelled = true;
        FutureTask<Void> submitted = task;
        if (submitted != null) {
            submitted.cancel(false);
            executor.remove(submitted);
        }
    }
}
