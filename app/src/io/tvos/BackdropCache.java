package io.tvos;

import io.tvos.Recs.Pick;
import io.tvos.artwork.ArtworkDiskCache;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Original image files and decoded backdrop LRU; catalog scheduling remains in Recs. */
final class BackdropCache {
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private final Context context;
    private final Executor io;
    private final Runnable changed;
    private final Supplier<List<Pick>> initialPicks;
    private volatile boolean light, checkedDisk;
    BackdropCache(Context context, Executor io, Runnable changed, Supplier<List<Pick>> initialPicks) {
        this.context = context.getApplicationContext(); this.io = io;
        this.changed = changed; this.initialPicks = initialPicks;
    }
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);   // access-order LRU: visible artwork survives
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }
    boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).isFile() && imageFile(p).length() > 0;
    }
    File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }
    void download(Pick p) throws IOException {
        if (p.backdrop == null || p.backdrop.isEmpty()) return;
        File f = imageFile(p);
        if (f.isFile() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis());
            if (!checkedDisk) { prune(f.getParentFile()); checkedDisk = true; }
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = File.createTempFile("backdrop-", ".part", f.getParentFile());
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n; long received = 0, expected = c.getContentLengthLong();
            if (expected > 16L*1024*1024) throw new IOException("Backdrop exceeds size limit");
            while ((n = in.read(buffer)) != -1) {
                received += n;
                if (received > 16L*1024*1024) throw new IOException("Backdrop exceeds size limit");
                out.write(buffer, 0, n);
            }
            if (received == 0 || expected >= 0 && received != expected) throw new IOException("Incomplete backdrop");
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) { part.delete(); throw new IOException("couldn't save the image"); }
        prune(f.getParentFile()); checkedDisk = true;
    }
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("unreadable image bounds");
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            while (bounds.outWidth/opts.inSampleSize > Backdrop.W || bounds.outHeight/opts.inSampleSize > 1080) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                // Keep the first three New Releases backdrops resident while browsing far to the right.
                // Otherwise returning to the beginning flashes blank cards during disk decode.
                // Evict other LRU entries first, retaining the same overall memory cap.
                while (images.size() > (forLight ? 10 : 4)) {
                    String victim = null;
                    for (String candidate : images.keySet()) {
                        if (!isInitialNewReleaseBackdrop(candidate)) {
                            victim = candidate;
                            break;
                        }
                    }
                    if (victim == null) victim = images.keySet().iterator().next();
                    images.remove(victim);
                }
            }
            changed.run();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            if (e instanceof IOException) f.delete();
            // TV bitmap/shader implementations can fail at runtime; keep the visual fallback,
            // but make unexpected code/platform faults visible without logging private payloads.
            if (e instanceof RuntimeException && !(e instanceof IllegalArgumentException)
                    && !(e instanceof SecurityException))
                AppLog.unexpected("recommendation image", (RuntimeException) e);
            else AppLog.w("recommendation image: " + e.getMessage());
        }
    }
    private boolean isInitialNewReleaseBackdrop(String key) {
        List<Pick> initial = initialPicks.get();
        for (int i = 0; i < Math.min(3, initial.size()); i++) {
            Pick p = initial.get(i);
            if (key.equals(p.backdrop)) return true;
        }
        return false;
    }
    private static void prune(File dir) {
        ArtworkDiskCache.trim(dir, 96L*1024*1024, 48);
    }
    void retain(Set<String> keep) { synchronized (images) { images.keySet().retainAll(keep); } }
}
