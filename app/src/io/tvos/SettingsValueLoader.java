package io.tvos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** UI-owned bounded snapshots. Platform reads happen on one shared worker, never in Canvas drawing. */
final class SettingsValueLoader<T> {
    private static final int LIMIT = 16;
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(32), work -> {
                Thread thread = new Thread(work, "settings-values"); thread.setDaemon(true); return thread;
            });
    static { IO.allowCoreThreadTimeOut(true); }
    private final ThreadPoolExecutor executor;
    private final Consumer<Runnable> post;
    private final BooleanSupplier active;
    private final Runnable changed;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final class Entry {
        T value;
        UiRequest request;
        Entry(T value) { this.value = value; }
    }
    SettingsValueLoader(Consumer<Runnable> post, BooleanSupplier active, Runnable changed) {
        this(IO, post, active, changed);
    }
    SettingsValueLoader(ThreadPoolExecutor executor, Consumer<Runnable> post, BooleanSupplier active, Runnable changed) {
        this.executor = executor; this.post = post; this.active = active; this.changed = changed;
    }
    T get(String key, T fallback, Supplier<T> read) {
        Entry cached = entries.get(key);
        if (cached != null) return cached.value;
        if (!active.getAsBoolean()) return fallback;
        if (entries.size() == LIMIT) {
            String oldest = entries.keySet().iterator().next();
            Entry removed = entries.remove(oldest);
            if (removed.request != null) removed.request.cancel();
        }
        Entry entry = new Entry(fallback);
        UiRequest request = new UiRequest(executor); entry.request = request; entries.put(key, entry);
        try {
            request.start(() -> {
                T value;
                try { value = read.get(); } catch (RuntimeException unavailable) { value = fallback; }
                final T result = value == null ? fallback : value;
                if (!request.active()) return;
                post.accept(() -> {
                    if (!request.active() || !active.getAsBoolean() || entries.get(key) != entry) return;
                    entry.value = result; entry.request = null; changed.run();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException overloaded) {
            request.cancel(); entry.request = null; // Keep fallback, rather than retrying every animation frame.
        }
        return entry.value;
    }
    void clear() {
        for (Entry entry : entries.values()) if (entry.request != null) entry.request.cancel();
        entries.clear();
    }
}
