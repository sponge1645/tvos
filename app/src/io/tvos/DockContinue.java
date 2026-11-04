package io.tvos;

import io.tvos.artwork.PosterCache;
import io.tvos.home.dock.ResumeRowRenderer;

import android.content.Context;
import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Optional Dock resume row. Provider links remain owned by WatchingCatalog. */
final class DockContinue {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final java.util.concurrent.Executor worker;
    private final PosterCache images;
    private final Map<String, String> paths = new HashMap<>();
    private final Map<String, String> logoPaths = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private List<Recs.Pick> items = Collections.emptyList();
    private String signature = "";
    private volatile int generation;
    private int index;
    private float scroll;
    private ValueAnimator slide, entrance, focusAnimation;
    private final float[] cardFocus = new float[10];
    private float opacity = 1;
    private final Map<String, Long> imageAppeared = new HashMap<>();
    private final Runnable frame = () -> changedFrame();
    private final Context context;
    private boolean visible, focused, pressed;
    private final Shadows shadows = new Shadows();
    private final ResumeRowRenderer renderer = new ResumeRowRenderer(shadows::draw);
    private final ResumeRowRenderer.Frame drawFrame = new ResumeRowRenderer.Frame();

    DockContinue(Context context, Runnable changed) {
        this(context, changed, IO);
    }

    DockContinue(Context context, Runnable changed, java.util.concurrent.Executor worker) {
        this.context = context.getApplicationContext();
        this.changed = changed;
        this.worker = worker;
        images = new PosterCache(context, () -> main.post(changed), AppLog::w);
    }

    private void changedFrame() { if (visible) changed.run(); }

    private void enter() {
        if (entrance != null) entrance.cancel();
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)) { opacity = 1; return; }
        opacity = 0;
        entrance = ValueAnimator.ofFloat(0, 1);
        entrance.setStartDelay(400); // Let the previous Dock background finish its fade to the plain background.
        entrance.setDuration(320);
        entrance.setInterpolator(new DecelerateInterpolator(1.5f));
        entrance.addUpdateListener(animation -> { opacity = (Float) animation.getAnimatedValue(); changedFrame(); });
        entrance.start();
    }

    boolean visible() { return visible; }
    void show(List<Recs.Pick> picks) {
        if (!visible) enter();
        visible = true;
        StringBuilder key = new StringBuilder();
        for (Recs.Pick p : picks) key.append(p.watchingKey).append('\n').append(p.resumeUri).append('\n')
                .append(p.watchingYear).append(p.kind).append(p.meta).append('\n').append(p.watchingProgress).append('\n').append(p.backdrop).append('\n').append(p.title).append('\n').append(p.poster).append('\n');
        if (signature.equals(key.toString())) return;
        String selected = index < items.size() ? items.get(index).watchingKey : null;
        signature = key.toString(); generation++; pending.clear(); paths.clear(); logoPaths.clear(); imageAppeared.clear();
        items = new ArrayList<>(picks.subList(0, Math.min(10, picks.size())));
        index = 0;
        for (int i = 0; i < items.size(); i++) if (java.util.Objects.equals(selected, items.get(i).watchingKey)) index = i;
        if (items.isEmpty()) focused = pressed = false;
        if (slide != null) slide.cancel();
        scroll = Math.max(0, index - 1);
        animateFocus(); prepare(); changed.run();
    }

    void hide() {
        if (!visible) return;
        visible = focused = pressed = false; generation++; signature = "";
        if (slide != null) { slide.cancel(); slide = null; }
        if (entrance != null) { entrance.cancel(); entrance = null; }
        if (focusAnimation != null) { focusAnimation.cancel(); focusAnimation = null; }
        java.util.Arrays.fill(cardFocus, 0);
        main.removeCallbacks(frame); opacity = 0; imageAppeared.clear();
        scroll = 0;
        items = Collections.emptyList(); paths.clear(); logoPaths.clear(); pending.clear();
        shadows.clear();
        images.setWindow(Collections.emptySet()); changed.run();
    }

    private void prepare() {
        int first = windowStart();
        for (int i = first; i < Math.min(items.size(), first + 4); i++) {
            Recs.Pick p = items.get(i);
            String key = p.watchingKey;
            if (paths.containsKey(key) || !pending.add(key)) continue;
            int token = generation, itemIndex = i;
            worker.execute(() -> {
                if (token != generation) return;
                String url = "";
                DockArtwork resolved = null;
                String year = p.watchingYear == null ? "" : p.watchingYear, source = "history-fallback";
                try {
                    io.tvos.watching.DockPrograms programs = new io.tvos.watching.DockPrograms(context,
                            (image, owner) -> ProviderImages.allowed(context, image, owner));
                    io.tvos.watching.DockPrograms.Candidate published = programs.forResume(p.pkg, p.title, p.poster);
                    if (published != null) { url = published.urls.get(0); year = published.year; source = "published-landscape"; }
                } catch (RuntimeException e) {
                    AppLog.w("Dock resume channels unavailable: " + e.getClass().getSimpleName());
                }
                try {
                    if (url.isEmpty()) {
                        resolved = DockArtwork.lookup(p.title, p.poster == null ? "" : p.poster, year, p.kind == null ? "" : p.kind, io.tvos.network.ConnectionObserver.NONE);
                        url = resolved.backdrop;
                        if (!url.isEmpty()) source = "tmdb-landscape";
                    }
                }
                catch (java.io.IOException | org.json.JSONException | RuntimeException e) {
                    AppLog.w("Dock resume artwork unavailable: " + e.getClass().getSimpleName());
                }
                AppLog.w("Resume artwork: package=" + p.pkg + ", item=" + itemIndex + ", source=" + source + ", kind=" + p.kind + ", year=" + year);
                final String ready = !url.isEmpty() ? url : p.backdrop != null && !p.backdrop.isEmpty() ? p.backdrop : p.poster;
                main.post(() -> {
                    if (!visible || token != generation) return;
                    pending.remove(key); paths.put(key, resumeImage(ready));
                    window(); changed.run();
                });
                final String logoYear = year;
                final DockArtwork resolvedArtwork = resolved;
                // Queue optional metadata after the current poster lookup so images
                // can appear before logos; never create bitmaps in the draw loop.
                worker.execute(() -> {
                    if (token != generation) return;
                    try {
                        DockArtwork artwork = resolvedArtwork != null ? resolvedArtwork : DockArtwork.lookup(p.title, p.poster == null ? "" : p.poster,
                                logoYear, p.kind == null ? "" : p.kind, io.tvos.network.ConnectionObserver.NONE);
                        DockArtwork.loadLogo(artwork, io.tvos.network.ConnectionObserver.NONE);
                        if (artwork.logo.isEmpty() || token != generation) return;
                        main.post(() -> {
                            if (!visible || token != generation) return;
                            logoPaths.put(key, artwork.logo); window(); changed.run();
                        });
                    } catch (java.io.IOException | org.json.JSONException | RuntimeException e) {
                        AppLog.w("Dock resume logo unavailable: " + e.getClass().getSimpleName());
                    }
                });
            });
        }
        window();
    }

    // 780px closely matches an 850px card without decoding a 1280px image
    // only to downsample it to 640px under the shared cache memory budget.
    private static String resumeImage(String url) {
        if (url == null) return "";
        return url.startsWith("https://image.tmdb.org/t/p/")
                ? url.replaceFirst("/t/p/(w[0-9]+|original)/", "/t/p/w780/") : url;
    }

    private void window() {
        Set<String> wanted = new HashSet<>();
        int first = windowStart();
        for (int i = first; i < Math.min(items.size(), first + 4); i++) {
            String path = paths.get(items.get(i).watchingKey);
            if (path != null && !path.isEmpty()) wanted.add(path);
            String logo = logoPaths.get(items.get(i).watchingKey);
            if (logo != null && !logo.isEmpty()) wanted.add(logo);
        }
        images.setWindow(wanted);
        for (String path : wanted) images.poster(path);
    }

    private int windowStart() { return Math.max(0, Math.min(Math.max(0, index - 1), (int) Math.floor(scroll) - 1)); }

    private void animateFocus() {
        if (focusAnimation != null) focusAnimation.cancel();
        float[] from = cardFocus.clone();
        boolean moving = false;
        for (int i = 0; i < cardFocus.length; i++) if (from[i] != (focused && i == index ? 1 : 0)) moving = true;
        if (!moving) return;
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)) {
            for (int i = 0; i < cardFocus.length; i++) cardFocus[i] = focused && i == index ? 1 : 0;
            return;
        }
        final int selected = focused ? index : -1;
        focusAnimation = ValueAnimator.ofFloat(0, 1);
        focusAnimation.setDuration(260);
        focusAnimation.setInterpolator(new DecelerateInterpolator(1.5f));
        focusAnimation.addUpdateListener(animation -> {
            float progress = (Float) animation.getAnimatedValue();
            for (int i = 0; i < cardFocus.length; i++) cardFocus[i] = from[i] + ((i == selected ? 1 : 0) - from[i]) * progress;
            changedFrame();
        });
        focusAnimation.start();
    }

    private void moveTo(int next) {
        if (next == index) return;
        index = next;
        animateFocus();
        if (slide != null) slide.cancel();
        float target = Math.max(0, index - 1);
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false) || target == scroll) {
            scroll = target; prepare(); changed.run(); return;
        }
        prepare();
        slide = ValueAnimator.ofFloat(scroll, target);
        slide.setDuration(360);
        slide.setInterpolator(new DecelerateInterpolator(1.5f));
        slide.addUpdateListener(animation -> {
            scroll = (Float) animation.getAnimatedValue();
            if (scroll == target) prepare();
            changed.run();
        });
        slide.start();
        changed.run();
    }

    boolean key(KeyEvent e, boolean canEnter, Runnable top, Consumer<Recs.Pick> open) {
        if (!visible) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        if (!focused) {
            if (canEnter && !items.isEmpty() && code == KeyEvent.KEYCODE_DPAD_UP) {
                if (down) { focused = true; pressed = false; animateFocus(); changed.run(); }
                return true;
            }
            return false;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down) moveTo(Math.max(0, Math.min(items.size() - 1, index + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1))));
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP) {
            if (down) { focused = pressed = false; animateFocus(); if (code == KeyEvent.KEYCODE_DPAD_UP) top.run(); changed.run(); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down && e.getRepeatCount() == 0) pressed = true;
            else if (!down) { if (pressed && !e.isCanceled() && index < items.size()) open.accept(items.get(index)); pressed = false; }
            return true;
        }
        return false;
    }

    void draw(Canvas c, float k, float bottom, float night) {
        if (!visible || opacity <= .001f) return;
        drawFrame.scale = k; drawFrame.bottom = bottom; drawFrame.night = night; drawFrame.opacity = opacity;
        drawFrame.ink = 0xFFF5F5F7;
        drawFrame.titleFont = Ui.semibold; drawFrame.metaFont = Ui.medium; drawFrame.headingFont = Ui.displayBold;
        drawFrame.heading = AppStrings.get(R.string.home_continue_watching);
        drawFrame.emptyMessage = AppStrings.get(R.string.home_watching_empty); drawFrame.empty = items.isEmpty();
        if (!renderer.begin(c, drawFrame)) return;
        boolean fadingImage = false;
        int first = Math.max(0, (int) Math.floor(scroll) - 1);
        for (int i = first; i < Math.min(items.size(), first + 4); i++) {
            if (!renderer.visible(i - scroll)) continue;
            Recs.Pick pick = items.get(i);
            String url = paths.get(pick.watchingKey);
            Bitmap image = url == null || url.isEmpty() ? null : images.poster(url);
            int alpha = 255;
            if (image != null) {
                long now = SystemClock.uptimeMillis();
                Long appeared = imageAppeared.get(url);
                if (appeared == null) {
                    appeared = now; imageAppeared.put(url, appeared);
                    AppLog.w("Resume decoded: package=" + pick.pkg + ", item=" + i
                            + ", size=" + image.getWidth() + "x" + image.getHeight());
                }
                float progress = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)
                        ? 1 : Ui.clamp((now - appeared) / 240f);
                fadingImage |= progress < 1;
                alpha = Math.round(255 * progress * progress * (3 - 2 * progress));
            }
            String logoPath = logoPaths.get(pick.watchingKey);
            Bitmap logo = logoPath == null ? null : images.poster(logoPath);
            int logoAlpha = 0;
            if (logo != null) {
                long now = SystemClock.uptimeMillis();
                Long appeared = imageAppeared.get(logoPath);
                if (appeared == null) { appeared = now; imageAppeared.put(logoPath, appeared); }
                float t = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)
                        ? 1 : Ui.clamp((now - appeared) / 300f);
                fadingImage |= t < 1;
                logoAlpha = Math.min(alpha, Math.round(255 * t * t * (3 - 2 * t)));
            }
            renderer.card(c, i - scroll, cardFocus[i], pick.title, WatchingText.overlay(pick), image, alpha, pick.watchingProgress, logo, logoAlpha);
        }
        renderer.end(c);
        if (fadingImage) { main.removeCallbacks(frame); main.postDelayed(frame, 16); }
    }
}
