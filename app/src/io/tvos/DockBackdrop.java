package io.tvos;

import io.tvos.watching.WatchingSources;

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
    private Runnable pendingLoading;

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
        focused = pkg; generation++; cancelConnection(); cancelLoading(); main.removeCallbacks(load);
        if (pkg == null) { changed.accept(null); return; }
        if (pkg.equals(cachedPackage) && cached != null) {
            boolean temporary = loadingBanner(pkg) != null && cached.title.isEmpty() && cached.logo == null;
            if (!temporary) changed.accept(cached);
            if (bundled(pkg) != null || !cached.title.isEmpty() || cached.logo != null) return;
        }
        main.postDelayed(load, 450);
    }

    void pause() {
        generation++; cancelConnection(); cancelLoading(); focused = null; main.removeCallbacks(load);
        cached = null; cachedPackage = null;
    }

    private void cancelLoading() {
        if (pendingLoading != null) main.removeCallbacks(pendingLoading);
        pendingLoading = null;
    }

    private void scheduleLoading(String pkg, int token, Artwork ready, long requested,
            java.util.concurrent.atomic.AtomicBoolean imageReady) {
        main.post(() -> {
            if (token != generation || imageReady.get()) return;
            cancelLoading();
            pendingLoading = () -> {
                pendingLoading = null;
                if (token != generation || imageReady.get()) return;
                cachedPackage = pkg; cached = ready;
                changed.accept(ready);
            };
            long elapsed = (System.nanoTime() - requested) / 1_000_000;
            main.postDelayed(pendingLoading, Math.max(0, 2000 - elapsed));
        });
    }

    private void imagePrepared(int token, java.util.concurrent.atomic.AtomicBoolean imageReady) {
        imageReady.set(true);
        main.post(() -> { if (token == generation) cancelLoading(); });
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
        if ("com.applemusicktv".equals(pkg)) return "music.webp";
        if ("com.android.vending".equals(pkg) || "com.yandex.devices.appstore".equals(pkg)) return "googleplay.webp";
        if ("io.tvos".equals(pkg) || "com.android.tv.settings".equals(pkg)) return "settings.webp";
        if ("com.google.android.youtube.tv".equals(pkg) || "com.google.android.youtube".equals(pkg)
                || "io.gh.reisxd.tizentube.cobalt".equals(pkg)) return "youtube.webp";
        return null;
    }

    static String loadingBanner(String pkg) {
        if ("watch.kino".equals(pkg) || "watch.kino.mod".equals(pkg)) return "kinopub.webp";
        if ("com.seena.app".equals(pkg)) return "seena.webp";
        if ("top.rootu.lampa".equals(pkg)) return "lampa.webp";
        return null;
    }

    private void loadFocused() {
        final String pkg = focused;
        final int token = generation;
        if (pkg == null) return;
        final long requested = System.nanoTime();
        final java.util.concurrent.atomic.AtomicBoolean imageReady = new java.util.concurrent.atomic.AtomicBoolean();
        IO.execute(() -> {
            if (token != generation) return;
            long started = System.nanoTime();
            Artwork result = null;
            String loading = loadingBanner(pkg);
            boolean hasLoading = false;
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
                if (loading != null) {
                    try (InputStream input = context.getAssets().open("banners/" + loading)) {
                        Bitmap image = BitmapFactory.decodeStream(input);
                        if (image == null) throw new IOException("Unreadable loading backdrop");
                        if (token != generation) { image.recycle(); return; }
                        Artwork preview = new Artwork(Backdrop.wideHero(image), "", null);
                        scheduleLoading(pkg, token, preview, requested, imageReady);
                        hasLoading = true;
                    } catch (IOException | RuntimeException e) {
                        if (token == generation) AppLog.w("Dock loading backdrop unavailable: " + e.getClass().getSimpleName());
                    }
                }
                long stage = System.nanoTime();
                List<Candidate> choices = candidates(pkg);
                int landscapes = 0;
                for (Candidate candidate : choices) if (!candidate.urls.isEmpty()) landscapes++;
                trace(pkg, "channels", stage, "candidates=" + choices.size() + ", landscapes=" + landscapes);
                for (Candidate candidate : choices) {
                    if (token != generation) return;
                    Backdrop background = null;
                    DockArtwork extra = null;
                    boolean resolvedFirst = "lampa".equals(WatchingSources.id(pkg));
                    if (resolvedFirst) {
                        extra = lookup(candidate, pkg, token);
                        if (token != generation) return;
                        background = resolvedBackground(extra, pkg, token);
                    }
                    boolean publishedReady = false;
                    for (String url : candidate.urls) {
                        if (background != null || token != generation) break;
                        stage = System.nanoTime();
                        String outcome = "unusable";
                        try { Bitmap image = read(publishedImage(url), token, true, 2500); if (image != null) { background = Backdrop.hero(image); publishedReady = true; outcome = "ready"; } }
                        catch (IOException | SecurityException | IllegalArgumentException e) { outcome = e.getClass().getSimpleName(); }
                        if (token == generation) trace(pkg, "published image", stage, outcome);
                        if (background != null || token != generation) break;
                    }
                    if (token != generation) return;
                    if (publishedReady) {
                        imagePrepared(token, imageReady);
                        android.util.Log.i("tvOS-DockBackdrop", pkg + " published image ready in " + (System.nanoTime() - started) / 1_000_000 + " ms");
                        result = new Artwork(background, "", null);
                        publish(pkg, token, result);
                    }
                    if (extra == null) extra = lookup(candidate, pkg, token);
                    if (token != generation) return;
                    if (background == null && !resolvedFirst) background = resolvedBackground(extra, pkg, token);
                    if (background == null) continue;
                    imagePrepared(token, imageReady);
                    android.util.Log.i("tvOS-DockBackdrop", pkg + " background ready in " + (System.nanoTime() - started) / 1_000_000 + " ms");
                    if (!publishedReady) {
                        result = new Artwork(background, "", null);
                        publish(pkg, token, result);
                    }
                    result = new Artwork(background, candidate.title, null);
                    stage = System.nanoTime();
                    DockArtwork.loadLogo(extra, connection -> trackConnection(token, connection));
                    if (token != generation) return;
                    trace(pkg, "logo metadata", stage, extra.logo.isEmpty() ? "none" : "matched");
                    Bitmap logo = null;
                    if (!extra.logo.isEmpty() && token == generation) {
                        try { logo = read(extra.logo, token, false); }
                        catch (IOException | SecurityException | IllegalArgumentException e) { /* The published title remains available. */ }
                    }
                    android.util.Log.i("tvOS-DockBackdrop", pkg + " caption ready in " + (System.nanoTime() - started) / 1_000_000 + " ms");
                    result = new Artwork(background, candidate.title, logo);
                    break;
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                if (token == generation) AppLog.w("Dock backdrop unavailable: " + e.getClass().getSimpleName());
            }
            if (result != null) {
                imageReady.set(true);
                main.post(() -> { if (token == generation) cancelLoading(); });
                publish(pkg, token, result);
            } else if (!hasLoading) main.post(() -> { if (token == generation && !pkg.equals(cachedPackage)) changed.accept(null); });
        });
    }

    private DockArtwork lookup(Candidate candidate, String pkg, int token) {
        long started = System.nanoTime();
        DockArtwork extra = new DockArtwork();
        try { extra = DockArtwork.lookup(candidate.title, candidate.poster, candidate.year, connection -> trackConnection(token, connection)); }
        catch (IOException | org.json.JSONException e) { AppLog.w("Dock artwork metadata unavailable: " + e.getClass().getSimpleName()); }
        if (token == generation) trace(pkg, "metadata", started, extra.backdrop.isEmpty() ? "no backdrop match" : "matched");
        return extra;
    }

    private Backdrop resolvedBackground(DockArtwork extra, String pkg, int token) {
        if (extra.backdrop.isEmpty() || token != generation) return null;
        long started = System.nanoTime();
        Backdrop background = null;
        try { Bitmap image = read(extra.backdrop, token, true); if (image != null) background = Backdrop.hero(image); }
        catch (IOException | SecurityException | IllegalArgumentException e) { /* Published artwork remains a fallback. */ }
        if (token == generation) trace(pkg, "fallback image", started, background == null ? "unusable" : "ready");
        return background;
    }

    private static void trace(String pkg, String stage, long started, String outcome) {
        android.util.Log.i("tvOS-DockBackdrop", pkg + " " + stage + ": "
                + (System.nanoTime() - started) / 1_000_000 + " ms, " + outcome);
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
        int visible = 0;
        try (Cursor c = context.getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                new String[]{"_id", "package_name", "display_name", "internal_provider_id", "app_link_intent_uri"}, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                visible++;
                if (!service.equals(WatchingSources.id(c.getString(1)))) continue;
                int score = priority(value(c, 2), value(c, 3) + " " + value(c, 4));
                channels.add(new long[]{c.getLong(0), score});
                android.util.Log.i("tvOS-DockBackdrop", pkg + " channel=" + c.getLong(0)
                        + ", name=" + value(c, 2) + ", score=" + score
                        + ", route=" + route(value(c, 4)));
            }
        }
        android.util.Log.i("tvOS-DockBackdrop", pkg + " visible channels=" + visible + ", owned=" + channels.size());
        channels.sort(Comparator.comparingLong((long[] c) -> c[1]).reversed());
        List<Candidate> result = new ArrayList<>();
        int queried = 0;
        for (long[] channel : channels) {
            if (channel[1] < 0) continue;
            if (queried++ >= 4) break;
            collect("content://android.media.tv/preview_program?channel=" + channel[0], service,
                    "weight DESC, _id ASC", result);
        }
        if (result.isEmpty()) collect("content://android.media.tv/watch_next_program", service,
                "last_engagement_time_utc_millis DESC", result);
        // Prefer published landscapes over portrait-only titles requiring a fallback lookup.
        // Stable sort retains channel priority among equally suitable candidates.
        result.sort(Comparator.comparingInt(candidate -> candidate.urls.isEmpty() ? 1 : 0));
        if (result.size() > 6) result.subList(6, result.size()).clear();
        return result;
    }

    private void collect(String uri, String service, String order, List<Candidate> result) {
        long started = System.nanoTime();
        int rows = 0, owned = 0, before = result.size();
        try (Cursor c = context.getContentResolver().query(Uri.parse(uri),
                new String[]{"package_name", "poster_art_uri", "thumbnail_uri", "poster_art_aspect_ratio", "thumbnail_aspect_ratio", "title", "release_date"},
                null, null, order)) {
            int added = 0;
            if (c != null) while (added < 6 && c.moveToNext()) {
                rows++;
                if (!service.equals(WatchingSources.id(c.getString(0)))) continue;
                owned++;
                String date = value(c, 6);
                Candidate candidate = new Candidate(value(c, 5), value(c, 1), date.length() >= 4 ? date.substring(0, 4) : "");
                if (c.getInt(4) == 0 && ProviderImages.allowed(context, value(c, 2), value(c, 0))) add(candidate.urls, value(c, 2));
                if (c.getInt(3) == 0 && ProviderImages.allowed(context, value(c, 1), value(c, 0))) add(candidate.urls, value(c, 1));
                if (!candidate.urls.isEmpty() || !candidate.title.isEmpty()) { result.add(candidate); added++; }
            }
        }
        trace(service, uri.contains("?channel=") ? "programs channel=" + uri.substring(uri.indexOf("?channel=") + 9) : "watch next",
                started, "rows=" + rows + ", owned=" + owned + ", accepted=" + (result.size() - before));
    }

    private static String route(String link) {
        int end = link.indexOf('?');
        String path = end < 0 ? link : link.substring(0, end);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String value(Cursor c, int column) { String s = c.getString(column); return s == null ? "" : s; }
    static void add(List<String> urls, String url) {
        if (urls.size() < 6 && !urls.contains(url) && (url.startsWith("https://") || url.startsWith("content://") || url.startsWith("android.resource://"))) urls.add(url);
    }

    static String publishedImage(String url) {
        // Avoid fetching original-resolution TMDB artwork for a 1080p background.
        return url.startsWith("https://image.tmdb.org/t/p/original/")
                ? url.replace("/t/p/original/", "/t/p/w1280/") : url;
    }

    private Bitmap read(String url, int token, boolean landscape) throws IOException {
        return read(url, token, landscape, 8000);
    }

    private Bitmap read(String url, int token, boolean landscape, int timeout) throws IOException {
        File dir = new File(context.getCacheDir(), "dock-backdrops");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create backdrop cache");
        File file = new File(dir, PosterCache.cacheName(url));
        boolean downloaded = !file.isFile();
        if (downloaded) {
            File part = new File(file.getPath() + ".part");
            HttpURLConnection connection = null;
            try {
                InputStream stream;
                if (url.startsWith("https://")) {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
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
        File[] files = downloaded ? dir.listFiles() : null;
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
