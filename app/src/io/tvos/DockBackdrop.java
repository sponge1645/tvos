package io.tvos;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Focus-debounced static recommendations artwork. No per-frame queries or per-app bitmap collection. */
final class DockBackdrop {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Context context;
    private final Consumer<Backdrop> changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable load = this::loadFocused;
    private volatile int generation;
    private String focused;
    private String cachedPackage;
    private Backdrop cached;

    DockBackdrop(Context context, Consumer<Backdrop> changed) {
        this.context = context.getApplicationContext(); this.changed = changed;
    }

    static void requestPermission(Activity activity) {
        if (activity.checkSelfPermission("android.permission.READ_TV_LISTINGS") == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE);
        if (prefs.getBoolean("dock_backdrop_permission_requested", false)) return;
        prefs.edit().putBoolean("dock_backdrop_permission_requested", true).apply();
        activity.requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"}, WatchingCatalog.PERMISSION_REQUEST);
    }

    void focus(String pkg) {
        if (java.util.Objects.equals(focused, pkg)) return;
        focused = pkg; generation++; main.removeCallbacks(load);
        if (pkg == null) { changed.accept(null); return; }
        if (pkg.equals(cachedPackage) && cached != null) { changed.accept(cached); return; }
        main.postDelayed(load, 450);
    }

    void pause() {
        generation++; focused = null; main.removeCallbacks(load);
        cached = null; cachedPackage = null;
    }

    private void loadFocused() {
        final String pkg = focused;
        final int token = generation;
        if (pkg == null) return;
        IO.execute(() -> {
            if (token != generation) return;
            Backdrop result = null;
            try {
                List<String> urls = candidates(pkg);
                for (String url : urls) {
                    if (token != generation) return;
                    try { result = read(url, token); }
                    catch (IOException | SecurityException | IllegalArgumentException e) { /* Try another published image. */ }
                    if (result != null) break;
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                AppLog.w("Dock backdrop unavailable: " + e.getClass().getSimpleName());
            }
            final Backdrop ready = result;
            main.post(() -> {
                if (token != generation) return;
                cachedPackage = pkg; cached = ready;
                changed.accept(ready);
            });
        });
    }

    static int priority(String name, String id) {
        String text = (name + " " + id).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("history") || text.contains("истори") || text.contains("watch_next") || text.contains("continued") || text.contains("смотр")) return -1;
        if ((text.contains("нов") || text.contains("new")) && (text.contains("фильм") || text.contains("movie"))) return 100;
        if (text.contains("recs") || text.contains("recommend") || text.contains("рекоменд")) return 90;
        if (text.contains("movie") || text.contains("фильм")) return 80;
        return 10;
    }

    private List<String> candidates(String pkg) {
        List<long[]> channels = new ArrayList<>();
        String service = WatchingSources.id(pkg);
        try (Cursor c = context.getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                new String[]{"_id", "package_name", "display_name", "internal_provider_id", "app_link_intent_uri"}, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                if (!service.equals(WatchingSources.id(c.getString(1)))) continue;
                int score = priority(value(c, 2), value(c, 3) + " " + value(c, 4));
                channels.add(new long[]{c.getLong(0), score});
            }
        }
        channels.sort(Comparator.comparingLong((long[] c) -> c[1]).reversed());
        List<String> urls = new ArrayList<>();
        for (long[] channel : channels) {
            if (channel[1] < 0 || urls.size() >= 6) continue;
            try (Cursor c = context.getContentResolver().query(Uri.parse("content://android.media.tv/preview_program?channel=" + channel[0]),
                    new String[]{"package_name", "poster_art_uri", "thumbnail_uri", "poster_art_aspect_ratio", "thumbnail_aspect_ratio"}, null, null, "weight DESC, _id ASC")) {
                if (c != null) while (c.moveToNext() && urls.size() < 6) {
                    if (!service.equals(WatchingSources.id(c.getString(0)))) continue;
                    if (c.getInt(4) == 0) add(urls, value(c, 2));
                    if (c.getInt(3) == 0) add(urls, value(c, 1));
                }
            }
        }
        if (urls.isEmpty()) {
            try (Cursor c = context.getContentResolver().query(Uri.parse("content://android.media.tv/watch_next_program"),
                    new String[]{"package_name", "poster_art_uri", "thumbnail_uri", "poster_art_aspect_ratio", "thumbnail_aspect_ratio"}, null, null, "last_engagement_time_utc_millis DESC")) {
                if (c != null) while (c.moveToNext() && urls.size() < 6) {
                    if (!service.equals(WatchingSources.id(c.getString(0)))) continue;
                    if (c.getInt(4) == 0) add(urls, value(c, 2));
                    if (c.getInt(3) == 0) add(urls, value(c, 1));
                }
            }
        }
        return urls;
    }

    private static String value(Cursor c, int column) { String s = c.getString(column); return s == null ? "" : s; }
    static void add(List<String> urls, String url) {
        if (urls.size() < 6 && !urls.contains(url) && (url.startsWith("https://") || url.startsWith("content://") || url.startsWith("android.resource://"))) urls.add(url);
    }

    private Backdrop read(String url, int token) throws IOException {
        File dir = new File(context.getCacheDir(), "dock-backdrops");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create backdrop cache");
        File file = new File(dir, PosterCache.cacheName(url));
        if (!file.isFile()) {
            File part = new File(file.getPath() + ".part");
            HttpURLConnection connection = null;
            try {
                InputStream stream;
                if (url.startsWith("https://")) {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
                    stream = connection.getInputStream();
                } else stream = context.getContentResolver().openInputStream(Uri.parse(url));
                if (stream == null) throw new IOException("No image stream");
                try (InputStream input = stream; FileOutputStream output = new FileOutputStream(part)) {
                    byte[] buffer = new byte[16384]; long count = 0; int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (token != generation) throw new IOException("Stale focus");
                        count += n; if (count > 16L * 1024 * 1024) throw new IOException("Image too large");
                        output.write(buffer, 0, n);
                    }
                }
                if (!part.renameTo(file)) throw new IOException("Cannot publish image");
            } finally { if (connection != null) connection.disconnect(); part.delete(); }
        }
        file.setLastModified(System.currentTimeMillis());
        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (int i = 6; i < files.length; i++) files[i].delete();
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { file.delete(); return null; }
        if ((double) bounds.outWidth / bounds.outHeight < 1.2) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 1920 || bounds.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
        if (token != generation) return null;
        Bitmap image = BitmapFactory.decodeFile(file.getPath(), options);
        return image == null ? null : Backdrop.hero(image);
    }
}
