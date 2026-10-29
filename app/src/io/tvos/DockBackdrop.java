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
    private static final ExecutorService CANCEL = Executors.newSingleThreadExecutor();
    private final Context context;
    private final Consumer<Artwork> changed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable load = this::loadFocused;
    private volatile int generation;
    private volatile HttpURLConnection activeConnection;
    private String focused;
    private String cachedPackage;
    private Artwork cached;

    DockBackdrop(Context context, Consumer<Artwork> changed) {
        this.context = context.getApplicationContext(); this.changed = changed;
    }

    static final class Artwork {
        final Backdrop background;
        final String title;
        final Bitmap logo;
        Artwork(Backdrop background, String title, Bitmap logo) { this.background = background; this.title = title; this.logo = logo; }
    }
    static final class Candidate {
        final String title, poster, year;
        final List<String> urls = new ArrayList<>();
        Candidate(String title, String poster, String year) { this.title = title; this.poster = poster; this.year = year; }
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
        focused = pkg; generation++; cancelConnection(); main.removeCallbacks(load);
        if (pkg == null) { changed.accept(null); return; }
        if (pkg.equals(cachedPackage) && cached != null) {
            changed.accept(cached);
            if (bundled(pkg) != null || !cached.title.isEmpty() || cached.logo != null) return;
        }
        main.postDelayed(load, 450);
    }

    void pause() {
        generation++; cancelConnection(); focused = null; main.removeCallbacks(load);
        cached = null; cachedPackage = null;
    }

    private void cancelConnection() {
        HttpURLConnection connection = activeConnection;
        activeConnection = null;
        if (connection != null) CANCEL.execute(connection::disconnect);
    }

    private void trackConnection(int token, HttpURLConnection connection) {
        activeConnection = connection;
        if (connection != null && token != generation) {
            connection.disconnect();
            throw new java.util.concurrent.CancellationException();
        }
    }

    static String bundled(String pkg) {
        if ("io.tvos".equals(pkg) || "com.android.tv.settings".equals(pkg)) return "settings.webp";
        if ("com.google.android.youtube.tv".equals(pkg) || "com.google.android.youtube".equals(pkg)
                || "io.gh.reisxd.tizentube.cobalt".equals(pkg)) return "youtube.webp";
        return null;
    }

    private void loadFocused() {
        final String pkg = focused;
        final int token = generation;
        if (pkg == null) return;
        IO.execute(() -> {
            if (token != generation) return;
            Artwork result = null;
            try {
                String bundled = bundled(pkg);
                if (bundled != null) {
                    try (InputStream input = context.getAssets().open("banners/" + bundled)) {
                        Bitmap image = BitmapFactory.decodeStream(input);
                        if (image == null) throw new IOException("Unreadable bundled backdrop");
                        if (token != generation) { image.recycle(); return; }
                        publish(pkg, token, new Artwork(Backdrop.wideHero(image), "", null));
                    }
                    return;
                }
                for (Candidate candidate : candidates(pkg)) {
                    if (token != generation) return;
                    Backdrop background = null;
                    for (String url : candidate.urls) {
                        try { Bitmap image = read(url, token, true); if (image != null) background = Backdrop.hero(image); }
                        catch (IOException | SecurityException | IllegalArgumentException e) { /* Try another published image. */ }
                        if (background != null || token != generation) break;
                    }
                    if (token != generation) return;
                    if (background != null) publish(pkg, token, new Artwork(background, "", null));
                    DockArtwork extra = new DockArtwork();
                    try { extra = DockArtwork.lookup(candidate.title, candidate.poster, candidate.year, connection -> trackConnection(token, connection)); }
                    catch (IOException | org.json.JSONException e) { AppLog.w("Dock artwork metadata unavailable: " + e.getClass().getSimpleName()); }
                    if (token != generation) return;
                    if (background == null && !extra.backdrop.isEmpty()) {
                        try { Bitmap image = read(extra.backdrop, token, true); if (image != null) background = Backdrop.hero(image); }
                        catch (IOException | SecurityException | IllegalArgumentException e) { /* Next published title. */ }
                    }
                    if (background == null) continue;
                    publish(pkg, token, new Artwork(background, "", null));
                    DockArtwork.loadLogo(extra, connection -> trackConnection(token, connection));
                    if (token != generation) return;
                    Bitmap logo = null;
                    if (!extra.logo.isEmpty() && token == generation) {
                        try { logo = read(extra.logo, token, false); }
                        catch (IOException | SecurityException | IllegalArgumentException e) { /* The published title remains available. */ }
                    }
                    result = new Artwork(background, candidate.title, logo);
                    break;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (token == generation) AppLog.w("Dock backdrop unavailable: " + e.getClass().getSimpleName());
            }
            if (result != null) publish(pkg, token, result);
            else main.post(() -> { if (token == generation && !pkg.equals(cachedPackage)) changed.accept(null); });
        });
    }

    private void publish(String pkg, int token, Artwork ready) {
        main.post(() -> {
            if (token != generation) return;
            cachedPackage = pkg; cached = ready;
            changed.accept(ready);
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

    private List<Candidate> candidates(String pkg) {
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
        List<Candidate> result = new ArrayList<>();
        for (long[] channel : channels) {
            if (channel[1] < 0 || result.size() >= 6) continue;
            collect("content://android.media.tv/preview_program?channel=" + channel[0], service,
                    "weight DESC, _id ASC", result);
        }
        if (result.isEmpty()) collect("content://android.media.tv/watch_next_program", service,
                "last_engagement_time_utc_millis DESC", result);
        return result;
    }

    private void collect(String uri, String service, String order, List<Candidate> result) {
        try (Cursor c = context.getContentResolver().query(Uri.parse(uri),
                new String[]{"package_name", "poster_art_uri", "thumbnail_uri", "poster_art_aspect_ratio", "thumbnail_aspect_ratio", "title", "release_date"},
                null, null, order)) {
            if (c != null) while (c.moveToNext() && result.size() < 6) {
                if (!service.equals(WatchingSources.id(c.getString(0)))) continue;
                String date = value(c, 6);
                Candidate candidate = new Candidate(value(c, 5), value(c, 1), date.length() >= 4 ? date.substring(0, 4) : "");
                if (c.getInt(4) == 0) add(candidate.urls, value(c, 2));
                if (c.getInt(3) == 0) add(candidate.urls, value(c, 1));
                if (!candidate.urls.isEmpty() || !candidate.title.isEmpty()) result.add(candidate);
            }
        }
    }

    private static String value(Cursor c, int column) { String s = c.getString(column); return s == null ? "" : s; }
    static void add(List<String> urls, String url) {
        if (urls.size() < 6 && !urls.contains(url) && (url.startsWith("https://") || url.startsWith("content://") || url.startsWith("android.resource://"))) urls.add(url);
    }

    private Bitmap read(String url, int token, boolean landscape) throws IOException {
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
                    trackConnection(token, connection);
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
            } finally { if (connection != null) { connection.disconnect(); if (activeConnection == connection) activeConnection = null; } part.delete(); }
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
        if (landscape && (double) bounds.outWidth / bounds.outHeight < 1.2) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > (landscape ? 1920 : 600) || bounds.outHeight / options.inSampleSize > (landscape ? 1080 : 300)) options.inSampleSize *= 2;
        if (token != generation) return null;
        Bitmap image = BitmapFactory.decodeFile(file.getPath(), options);
        return image;
    }
}
