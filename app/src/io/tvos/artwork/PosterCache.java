package io.tvos.artwork;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.concurrent.Executors;

/** Poster download/decode and bounded RAM/disk caches; no catalog, Pick or UI state. */
public final class PosterCache {
    public interface RetryScheduler { void after(Runnable task, long delayMs); }
    private static final java.util.concurrent.ExecutorService DEFAULT_WORKER = Executors.newFixedThreadPool(3,
            task -> { Thread thread = new Thread(task, "poster-artwork"); thread.setDaemon(true); return thread; });
    private static final java.util.concurrent.ScheduledExecutorService RETRIES = Executors.newSingleThreadScheduledExecutor(
            task -> { Thread thread = new Thread(task, "poster-retry"); thread.setDaemon(true); return thread; });
    private static final Object DISK_LOCK = new Object();
    private final RetryScheduler retries;
    private boolean retryScheduled;
    private final Context context;
    private final Runnable changed;
    private final Executor posterIo;
    private final Consumer<String> warning;

    public PosterCache(Context context, Runnable changed, Consumer<String> warning) {
        this(context, changed, DEFAULT_WORKER, warning);
    }

    public PosterCache(Context context, Runnable changed, Executor worker, Consumer<String> warning) {
        this(context, changed, worker, warning, (task, delay) -> RETRIES.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS));
    }

    public PosterCache(Context context, Runnable changed, Executor worker, Consumer<String> warning, RetryScheduler retries) {
        this.retries = retries;
        this.context = context.getApplicationContext();
        this.changed = changed;
        this.posterIo = worker;
        this.warning = warning;
    }

    private final Map<String, Bitmap> posters = new LinkedHashMap<>(48, 0.75f, true);
    private static final long POSTER_CACHE_MAX_BYTES = 200L * 1024 * 1024;
    private static final long POSTER_RAM_MAX_BYTES = 12L * 1024 * 1024;
    private final java.util.Set<String> posterPending = new java.util.HashSet<>();
    // One worker plus queued requests; enough for the current page and its prefetch window.
    private static final int POSTER_PENDING_MAX = 24;
    private static final long POSTER_RETRY_MS = 30_000L;
    private static final long POSTER_FILE_MAX_BYTES = 8L * 1024 * 1024;
    // Guarded by posters, including access-order mutations.
    private final Map<String, Long> posterFailures = new LinkedHashMap<>(32, 0.75f, true);
    private long posterRamBytes;
    // Null until a screen supplies a window. An empty window stops loading while Home is hidden.
    private java.util.Set<String> posterWindow;
    // Shared workers may decode independent paths concurrently.
    private volatile boolean posterCacheChecked;

    public static String imageUrl(String path) {
        if (path.startsWith("https://")) return path;
        if (!path.startsWith("/") || path.startsWith("//")) throw new IllegalArgumentException("Invalid poster path");
        return "https://image.tmdb.org/t/p/w500" + path;
    }

    public static String cacheName(String path) {
        if (path.startsWith("/")) return path.replace("/", "");
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder("external-");
            for (byte value : digest) name.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return name.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Retain only visible/prefetched paths; disk copies remain when browsing back. */
    public void setWindow(java.util.Set<String> wanted) {
        synchronized (posters) {
            if (wanted.equals(posterWindow)) return;
            posterWindow = new java.util.HashSet<>(wanted);
            java.util.Iterator<Map.Entry<String, Bitmap>> entries = posters.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, Bitmap> entry = entries.next();
                if (wanted.contains(entry.getKey())) continue;
                posterRamBytes -= entry.getValue().getAllocationByteCount();
                entries.remove(); // No recycle: the last submitted frame may still reference this bitmap.
            }
        }
        scheduleRetry();
    }

    private void scheduleRetry() {
        synchronized (posters) {
            if (retryScheduled || !visibleFailure()) return;
            retryScheduled = true;
        }
        java.lang.ref.WeakReference<PosterCache> weak = new java.lang.ref.WeakReference<>(this);
        retries.after(() -> {
            PosterCache cache = weak.get();
            if (cache != null) cache.retryVisible();
        }, POSTER_RETRY_MS);
    }

    private void retryVisible() {
        java.util.List<String> failed;
        synchronized (posters) {
            retryScheduled = false;
            failed = new java.util.ArrayList<>(posterFailures.keySet());
        }
        for (String path : failed) if (posterWanted(path)) poster(path);
        scheduleRetry();
    }

    // Caller holds posters. Hidden windows do not poll or retry.
    private boolean visibleFailure() {
        for (String path : posterFailures.keySet()) if (posterWindow == null || posterWindow.contains(path)) return true;
        return false;
    }

    private boolean posterWanted(String path) {
        synchronized (posters) { return posterWindow == null || posterWindow.contains(path); }
    }

    /** Small RAM LRU for immediate draws; downloaded posters persist on disk up to 200 MiB. */
    public Bitmap poster(String path) {
        if (path == null || path.isEmpty()) return null;
        synchronized (posters) {
            Bitmap ready = posters.get(path);
            if (ready != null) return ready;
            if (posterWindow != null && !posterWindow.contains(path)) return null;
            Long failed = posterFailures.get(path);
            if (failed != null && SystemClock.elapsedRealtime() - failed < POSTER_RETRY_MS) return null;
            if (posterPending.size() >= POSTER_PENDING_MAX || !posterPending.add(path)) return null;
        }
        try { posterIo.execute(() -> {
            File tmp = null;
            boolean loaded = false;
            boolean abandoned = false;
            try {
                if (!posterWanted(path)) { abandoned = true; return; }
                File dir = new File(context.getCacheDir(), "tmdb-posters");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create poster cache");
                File f = new File(dir, cacheName(path));
                boolean downloaded = false;
                if (!f.isFile() || f.length() == 0) {
                    tmp = File.createTempFile("poster-", ".part", dir);
                    if (path.startsWith("content://") || path.startsWith("android.resource://")) {
                        try (InputStream in = context.getContentResolver().openInputStream(android.net.Uri.parse(path))) {
                            if (!copyPoster(in, tmp, path, -1)) { abandoned = true; return; }
                        }
                    } else {
                        HttpURLConnection c = (HttpURLConnection) new URL(imageUrl(path)).openConnection();
                        c.setConnectTimeout(8000);
                        c.setReadTimeout(20000);
                        try {
                            long expected = c.getContentLengthLong();
                            if (expected > POSTER_FILE_MAX_BYTES) throw new IOException("Poster exceeds size limit");
                            try (InputStream in = c.getInputStream()) {
                                if (!copyPoster(in, tmp, path, expected)) { abandoned = true; return; }
                            }
                        } finally { c.disconnect(); }
                    }
                    synchronized (DISK_LOCK) {
                        // Another screen can finish the same URL first; never share its partial file.
                        if (f.isFile() && f.length() > 0) tmp.delete();
                        else if (!tmp.renameTo(f)) throw new IOException("Poster cache write failed");
                    }
                    downloaded = true;
                }
                if (!posterWanted(path)) { abandoned = true; return; }
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                int count;
                synchronized (posters) { count = posterWindow == null ? 6 : Math.max(1, posterWindow.size()); }
                long budget = Math.min(2L*1024*1024, POSTER_RAM_MAX_BYTES/count);
                while (bounds.outWidth > 0 && bounds.outHeight > 0
                        && (long)(bounds.outWidth/opts.inSampleSize)*(bounds.outHeight/opts.inSampleSize)*4 > budget
                        && opts.inSampleSize < 32768) opts.inSampleSize *= 2;
                Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), opts);
                if (bmp == null) {
                    f.delete(); // Retry from the network without retaining corrupt bytes.
                } else {
                    f.setLastModified(System.currentTimeMillis()); // LRU: recently used images survive.
                    // Disk hits cannot grow the directory; do not scan 200 MiB of cache after every decode.
                    if (downloaded || !posterCacheChecked) {
                        synchronized (DISK_LOCK) { prunePosterCache(dir); }
                        posterCacheChecked = true;
                    }
                    synchronized (posters) {
                        if (posterWindow != null && !posterWindow.contains(path)) { abandoned = true; return; }
                        Bitmap previous = posters.put(path, bmp);
                        if (previous != null) posterRamBytes -= previous.getAllocationByteCount();
                        posterRamBytes += bmp.getAllocationByteCount();
                        while (posterRamBytes > POSTER_RAM_MAX_BYTES && posters.size() > 1) {
                            String eldest = posters.keySet().iterator().next();
                            Bitmap removed = posters.remove(eldest);
                            // Do not recycle: a frame may still be using an evicted bitmap.
                            if (removed != null) posterRamBytes -= removed.getAllocationByteCount();
                        }
                        posterFailures.remove(path);
                    }
                    loaded = true;
                    changed.run();
                }
            } catch (IOException | IllegalArgumentException | SecurityException | OutOfMemoryError e) {
                warning.accept("poster: " + e.getMessage());
            } finally {
                if (tmp != null && tmp.exists()) tmp.delete();
                synchronized (posters) {
                    if (!loaded && !abandoned && (posterWindow == null || posterWindow.contains(path))) {
                        posterFailures.put(path, SystemClock.elapsedRealtime());
                        // A long browsing session must not accumulate an unbounded failure history.
                        while (posterFailures.size() > 128) {
                            posterFailures.remove(posterFailures.keySet().iterator().next());
                        }
                    }
                    posterPending.remove(path);
                }
                if (!loaded && !abandoned) scheduleRetry();
            }
        }); } catch (java.util.concurrent.RejectedExecutionException stopped) {
            synchronized (posters) { posterPending.remove(path); }
        }
        return null;
    }

    /** False means the caller's image window changed; partial files are cleaned up by the worker. */
    private boolean copyPoster(InputStream in, File file, String path, long expected) throws IOException {
        if (in == null) throw new IOException("Poster provider returned no stream");
        long received = 0;
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (!posterWanted(path)) return false;
                received += count;
                if (received > POSTER_FILE_MAX_BYTES) throw new IOException("Poster exceeds size limit");
                out.write(buffer, 0, count);
            }
        }
        if (received == 0) throw new IOException("Empty poster download");
        if (expected >= 0 && received != expected) throw new IOException("Incomplete poster download");
        return true;
    }

    /** Evict least recently used poster files only after the disk cache exceeds 200 MiB. */
    private static void prunePosterCache(File dir) {
        ArtworkDiskCache.trim(dir, POSTER_CACHE_MAX_BYTES, Integer.MAX_VALUE);
    }

}
