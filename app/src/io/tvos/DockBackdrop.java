package io.tvos;

import io.tvos.artwork.DockImageLoader;
import static io.tvos.artwork.DockImageLoader.publishedImage;
import io.tvos.watching.DockPrograms;
import io.tvos.watching.DockPrograms.Candidate;

import io.tvos.watching.WatchingSources;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Focus-debounced static recommendations artwork. No per-frame queries or per-app bitmap collection. */
final class DockBackdrop {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ExecutorService CANCEL = Executors.newSingleThreadExecutor();
    private final Context context;
    private final DockPrograms programs;
    private final DockImageLoader images;
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
        programs = new DockPrograms(this.context, (url, owner) -> ProviderImages.allowed(this.context, url, owner));
        images = new DockImageLoader(this.context, new DockImageLoader.Requests() {
            public boolean active(int token) { return token == generation; }
            public void opened(int token, HttpURLConnection connection) { trackConnection(token, connection); }
            public void closed(HttpURLConnection connection) {
                if (activeConnection == connection) activeConnection = null;
            }
        });
    }

    static final class Artwork {
        final Backdrop background;
        final String title;
        final Bitmap logo;
        Artwork(Backdrop background, String title, Bitmap logo) { this.background = background; this.title = title; this.logo = logo; }
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
                List<Candidate> choices = programs.candidates(pkg);
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
                        try { Bitmap image = images.read(publishedImage(url), token, true, 2500); if (image != null) { background = Backdrop.hero(image); publishedReady = true; outcome = "ready"; } }
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

    private Bitmap read(String url, int token, boolean landscape) throws IOException {
        return images.read(url, token, landscape, 8000);
    }
}
