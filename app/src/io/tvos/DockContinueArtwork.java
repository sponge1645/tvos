package io.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import io.tvos.artwork.PosterCache;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** Resume artwork requests and the bounded poster/logo cache window, owned on the UI thread. */
final class DockContinueArtwork {
    private final Context context;
    private final Handler main;
    private static final Executor CANCEL = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "resume-artwork-cancel"); thread.setDaemon(true); return thread;
    });
    private final Executor worker, cancel;
    private final Object connectionLock = new Object();
    private java.net.HttpURLConnection activeConnection;
    private final Runnable changed;
    private final PosterCache images;
    private final Map<String, String> paths = new HashMap<>(), logoPaths = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private volatile int generation;
    private List<Recs.Pick> items = Collections.emptyList();
    private int first;

    DockContinueArtwork(Context context, Handler main, Executor worker, Runnable changed) {
        this(context, main, worker, changed, CANCEL);
    }
    DockContinueArtwork(Context context, Handler main, Executor worker, Runnable changed, Executor cancel) {
        this.context = context; this.main = main; this.worker = worker; this.changed = changed; this.cancel = cancel;
        images = new PosterCache(context, () -> main.post(() -> {
            if (!items.isEmpty()) changed.run();
        }), AppLog::w);
    }

    void reset() {
        java.net.HttpURLConnection connection;
        synchronized (connectionLock) {
            generation++; connection = activeConnection; activeConnection = null;
        }
        if (connection != null) cancel.execute(connection::disconnect);
        paths.clear(); logoPaths.clear(); pending.clear();
        items = Collections.emptyList(); first = 0;
    }

    private io.tvos.network.ConnectionObserver observer(int token) {
        return new io.tvos.network.ConnectionObserver() {
            public void opened(java.net.HttpURLConnection connection) {
                synchronized (connectionLock) {
                    if (token == generation) { activeConnection = connection; return; }
                }
                connection.disconnect();
                throw new java.util.concurrent.CancellationException("Resume artwork cancelled");
            }
            public void closed(java.net.HttpURLConnection connection) {
                synchronized (connectionLock) {
                    if (activeConnection == connection) activeConnection = null;
                }
            }
        };
    }

    void clear() { reset(); images.setWindow(Collections.emptySet()); }
    String posterPath(String key) { return paths.get(key); }
    String logoPath(String key) { return logoPaths.get(key); }
    Bitmap image(String path) { return path == null || path.isEmpty() ? null : images.poster(path); }

    void prepare(List<Recs.Pick> items, int first) {
        this.items = items; this.first = first;
        for (int i = first; i < Math.min(items.size(), first + 4); i++) {
            Recs.Pick p = items.get(i);
            String key = p.watchingKey;
            if (paths.containsKey(key) || !pending.add(key)) continue;
            int token = generation, itemIndex = i;
            worker.execute(() -> {
                if (token != generation) return;
                io.tvos.network.ConnectionObserver observer = observer(token);
                String url = "", source = "history-fallback";
                if (AppLog.artworkEnabled()) AppLog.artwork("resume input: item=" + itemIndex + ", package=" + p.pkg
                        + ", title=" + p.title + ", year=" + p.watchingYear + ", kind=" + p.kind
                        + ", posterPresent=" + (p.poster != null && !p.poster.isEmpty())
                        + ", backdropPresent=" + (p.backdrop != null && !p.backdrop.isEmpty()));
                DockArtwork resolved = null;
                String year = p.watchingYear == null ? "" : p.watchingYear;
                try {
                    io.tvos.watching.DockPrograms programs = new io.tvos.watching.DockPrograms(context,
                            (image, owner) -> ProviderImages.allowed(context, image, owner));
                    io.tvos.watching.DockPrograms.Candidate published = programs.forResume(p.pkg, p.title, p.poster);
                    if (published != null) {
                        if (AppLog.artworkEnabled()) AppLog.artwork("published: item=" + itemIndex + ", year=" + published.year + ", wideCount=" + published.urls.size());
                        if (!published.year.isEmpty()) year = published.year;
                        if (!published.urls.isEmpty()) { url = published.urls.get(0); source = "published-landscape"; }
                    }
                } catch (RuntimeException e) {
                    AppLog.w("Dock resume channels unavailable: " + e.getClass().getSimpleName());
                }
                if (token != generation) return;
                year = io.tvos.watching.ResumeArtworkIdentity.year(p.pkg, p.title, year);
                try {
                    if (url.isEmpty()) {
                        resolved = DockArtwork.lookup(p.title, p.poster == null ? "" : p.poster, year, p.kind == null ? "" : p.kind, observer);
                        url = resolved.backdrop;
                        if (!url.isEmpty()) source = "tmdb-landscape";
                    }
                }
                catch (java.io.IOException | org.json.JSONException | RuntimeException e) {
                    if (token == generation) AppLog.artworkError("resume metadata item=" + itemIndex, e);
                    if (token == generation) AppLog.w("Dock resume artwork unavailable: " + e.getClass().getSimpleName());
                }
                if (token != generation) return;
                if (AppLog.artworkEnabled()) AppLog.artwork("resume resolved: item=" + itemIndex + ", title=" + p.title
                        + ", year=" + year + ", source=" + source);
                final String ready = !url.isEmpty() ? url : p.backdrop != null && !p.backdrop.isEmpty() ? p.backdrop : p.poster;
                main.post(() -> {
                    if (token != generation) return;
                    pending.remove(key); paths.put(key, resumeImage(ready));
                    window(); changed.run();
                });
                final String logoYear = year;
                final DockArtwork resolvedArtwork = resolved;
                // Queue optional metadata after the current poster lookup so images
                // can appear before logos; never create bitmaps in the draw loop.
                if (token != generation) return;
                worker.execute(() -> {
                    if (token != generation) return;
                    try {
                        DockArtwork artwork = resolvedArtwork != null ? resolvedArtwork : DockArtwork.lookup(p.title, p.poster == null ? "" : p.poster,
                                logoYear, p.kind == null ? "" : p.kind, observer);
                        if (token != generation) return;
                        DockArtwork.loadLogo(artwork, observer);
                        if (AppLog.artworkEnabled()) AppLog.artwork("resume logo: item=" + itemIndex + ", title=" + p.title
                                + ", year=" + logoYear + ", status=" + artwork.logoStatus);
                        if (artwork.logo.isEmpty() || token != generation) return;
                        main.post(() -> {
                            if (token != generation) return;
                            logoPaths.put(key, artwork.logo); window(); changed.run();
                        });
                    } catch (java.io.IOException | org.json.JSONException | RuntimeException e) {
                        if (token == generation) AppLog.w("Dock resume logo unavailable: " + e.getClass().getSimpleName());
                    }
                });
            });
        }
        window();
    }

    // w780 fits an 850px card without decoding w1280 down to 640px under the cache budget.
    private static String resumeImage(String url) {
        if (url == null) return "";
        return url.startsWith("https://image.tmdb.org/t/p/")
                ? url.replaceFirst("/t/p/(w[0-9]+|original)/", "/t/p/w780/") : url;
    }

    private void window() {
        Set<String> wanted = new HashSet<>();
        for (int i = first; i < Math.min(items.size(), first + 4); i++) {
            String path = paths.get(items.get(i).watchingKey);
            if (path != null && !path.isEmpty()) wanted.add(path);
            String logo = logoPaths.get(items.get(i).watchingKey);
            if (logo != null && !logo.isEmpty()) wanted.add(logo);
        }
        images.setWindow(wanted);
        for (String path : wanted) images.poster(path);
    }
}
