package io.tvos;

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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Poster download/decode and bounded RAM/disk caches; no catalog, Pick or UI state. */
final class PosterCache {
    private final Context context;
    private final Runnable changed;
    private final Executor posterIo;

    PosterCache(Context context, Runnable changed) {
        this(context, changed, Executors.newSingleThreadExecutor());
    }

    PosterCache(Context context, Runnable changed, Executor worker) {
        this.context = context.getApplicationContext();
        this.changed = changed;
        this.posterIo = worker;
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
    // Used only by the single poster worker.
    private boolean posterCacheChecked;

    /** Retain only visible/prefetched paths; disk copies remain when browsing back. */
    void setWindow(java.util.Set<String> wanted) {
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
    }

    private boolean posterWanted(String path) {
        synchronized (posters) { return posterWindow == null || posterWindow.contains(path); }
    }

    /** Small RAM LRU for immediate draws; downloaded posters persist on disk up to 200 MiB. */
    Bitmap poster(String path) {
        if (path == null || path.isEmpty()) return null;
        synchronized (posters) {
            Bitmap ready = posters.get(path);
            if (ready != null) return ready;
            if (posterWindow != null && !posterWindow.contains(path)) return null;
            Long failed = posterFailures.get(path);
            if (failed != null && SystemClock.elapsedRealtime() - failed < POSTER_RETRY_MS) return null;
            if (posterPending.size() >= POSTER_PENDING_MAX || !posterPending.add(path)) return null;
        }
        posterIo.execute(() -> {
            File tmp = null;
            boolean loaded = false;
            boolean abandoned = false;
            try {
                if (!posterWanted(path)) { abandoned = true; return; }
                File dir = new File(context.getCacheDir(), "tmdb-posters");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create poster cache");
                File f = new File(dir, path.replace("/", ""));
                boolean downloaded = false;
                if (!f.isFile() || f.length() == 0) {
                    tmp = new File(f.getPath() + ".part");
                    HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/w500" + path).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    try {
                        long expected = c.getContentLengthLong();
                        if (expected > POSTER_FILE_MAX_BYTES) throw new IOException("Poster exceeds size limit");
                        long received = 0;
                        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                if (!posterWanted(path)) { abandoned = true; return; }
                                received += n;
                                if (received > POSTER_FILE_MAX_BYTES) throw new IOException("Poster exceeds size limit");
                                out.write(buf, 0, n);
                            }
                        }
                        if (expected >= 0 && received != expected) throw new IOException("Incomplete poster download");
                    } finally { c.disconnect(); }
                    if (!tmp.renameTo(f)) throw new IOException("Poster cache write failed");
                    downloaded = true;
                }
                if (!posterWanted(path)) { abandoned = true; return; }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), opts);
                if (bmp == null) {
                    f.delete(); // Re-download a damaged image next time.
                } else {
                    f.setLastModified(System.currentTimeMillis()); // LRU: recently used images survive.
                    // Disk hits cannot grow the directory; do not scan 200 MiB of cache after every decode.
                    if (downloaded || !posterCacheChecked) {
                        prunePosterCache(dir);
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
                AppLog.w("poster: " + e.getMessage());
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
            }
        });
        return null;
    }

    /** Evict least recently used poster files only after the disk cache exceeds 200 MiB. */
    private static void prunePosterCache(File dir) {
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null) return;
        long total = 0;
        for (File f : files) if (f.isFile()) total += f.length();
        if (total <= POSTER_CACHE_MAX_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= POSTER_CACHE_MAX_BYTES) break;
            long size = f.length();
            if (f.isFile() && f.delete()) total -= size;
        }
    }

}
