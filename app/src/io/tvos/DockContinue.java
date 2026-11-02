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
                .append(p.meta).append('\n').append(p.title).append('\n').append(p.poster).append('\n');
        if (signature.equals(key.toString())) return;
        String selected = index < items.size() ? items.get(index).watchingKey : null;
        signature = key.toString(); generation++; pending.clear(); paths.clear(); imageAppeared.clear();
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
        items = Collections.emptyList(); paths.clear(); pending.clear();
        shadows.clear();
        images.setWindow(Collections.emptySet()); changed.run();
    }

    private void prepare() {
        int first = windowStart();
        for (int i = first; i < Math.min(items.size(), first + 4); i++) {
            Recs.Pick p = items.get(i);
            String key = p.watchingKey;
            if (paths.containsKey(key) || !pending.add(key)) continue;
            int token = generation;
            worker.execute(() -> {
                if (token != generation) return;
                String url = "";
                try { url = DockArtwork.lookup(p.title, p.poster == null ? "" : p.poster, "", io.tvos.network.ConnectionObserver.NONE).backdrop; }
                catch (java.io.IOException | org.json.JSONException | RuntimeException e) {
                    AppLog.w("Dock resume artwork unavailable: " + e.getClass().getSimpleName());
                }
                final String ready = url.isEmpty() ? p.poster : url;
                main.post(() -> {
                    if (!visible || token != generation) return;
                    pending.remove(key); paths.put(key, ready == null ? "" : ready);
                    window(); changed.run();
                });
            });
        }
        window();
    }

    private void window() {
        Set<String> wanted = new HashSet<>();
        int first = windowStart();
        for (int i = first; i < Math.min(items.size(), first + 4); i++) {
            String path = paths.get(items.get(i).watchingKey);
            if (path != null && !path.isEmpty()) wanted.add(path);
        }
        images.setWindow(wanted);
        for (String path : wanted) images.poster(path);
    }

    private int windowStart() { return Math.max(0, Math.min(Math.max(0, index - 1), (int) Math.floor(scroll))); }

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
        drawFrame.ink = ActionSurface.ink(night, 0);
        drawFrame.titleFont = Ui.semibold; drawFrame.metaFont = Ui.medium; drawFrame.headingFont = Ui.displayBold;
        drawFrame.heading = AppStrings.get(R.string.home_continue_watching);
        drawFrame.emptyMessage = AppStrings.get(R.string.home_watching_empty); drawFrame.empty = items.isEmpty();
        if (!renderer.begin(c, drawFrame)) return;
        boolean fadingImage = false;
        int first = Math.max(0, (int) Math.floor(scroll));
        for (int i = first; i < Math.min(items.size(), first + 4); i++) {
            if (!renderer.visible(i - scroll)) continue;
            Recs.Pick pick = items.get(i);
            String url = paths.get(pick.watchingKey);
            Bitmap image = url == null || url.isEmpty() ? null : images.poster(url);
            int alpha = 255;
            if (image != null) {
                long now = SystemClock.uptimeMillis();
                Long appeared = imageAppeared.get(url);
                if (appeared == null) { appeared = now; imageAppeared.put(url, appeared); }
                float progress = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)
                        ? 1 : Ui.clamp((now - appeared) / 240f);
                fadingImage |= progress < 1;
                alpha = Math.round(255 * progress * progress * (3 - 2 * progress));
            }
            renderer.card(c, i - scroll, cardFocus[i], pick.title, WatchingText.caption(pick), image, alpha);
        }
        renderer.end(c);
        if (fadingImage) { main.removeCallbacks(frame); main.postDelayed(frame, 16); }
    }
}
