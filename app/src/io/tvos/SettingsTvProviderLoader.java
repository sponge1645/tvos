package io.tvos;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** UI-owned TV-provider request. Cancelled visits cannot deliver into a later visit. */
final class SettingsTvProviderLoader {
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(8), work -> {
                Thread thread = new Thread(work, "tv-provider-picker"); thread.setDaemon(true); return thread;
            });
    static { IO.allowCoreThreadTimeOut(true); }
    private final ThreadPoolExecutor executor;
    private final Consumer<Runnable> post;
    private final BooleanSupplier active;
    private final Supplier<List<String[]>> read;
    private UiRequest request;

    SettingsTvProviderLoader(Consumer<Runnable> post, BooleanSupplier active, Supplier<List<String[]>> read) {
        this(IO, post, active, read);
    }
    SettingsTvProviderLoader(ThreadPoolExecutor executor, Consumer<Runnable> post,
                             BooleanSupplier active, Supplier<List<String[]>> read) {
        this.executor = executor; this.post = post; this.active = active; this.read = read;
    }

    void load(Consumer<List<String[]>> deliver) {
        if (request != null || !active.getAsBoolean()) return;
        UiRequest work = new UiRequest(executor);
        request = work;
        try {
            work.start(() -> {
                List<String[]> found;
                try { found = read.get(); }
                catch (RuntimeException unavailable) { found = Collections.emptyList(); }
                final List<String[]> result = found == null ? Collections.emptyList() : found;
                if (!work.active()) return;
                post.accept(() -> {
                    if (!work.active() || request != work) return;
                    request = null;
                    if (active.getAsBoolean()) deliver.accept(result);
                });
            });
        } catch (RejectedExecutionException overloaded) {
            work.cancel(); request = null;
        }
    }

    void cancel() {
        if (request != null) request.cancel();
        request = null;
    }
}
