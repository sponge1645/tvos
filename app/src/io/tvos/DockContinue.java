package io.tvos;

import android.content.Context;
import android.animation.ValueAnimator;
import android.view.animation.DecelerateInterpolator;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
    static final String KEY = "dock_continue";
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
    private final Paint headingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float opacity = 1;
    private final Map<String, Long> imageAppeared = new HashMap<>();
    private final Runnable frame = () -> changedFrame();
    private final Context context;
    private final android.text.TextPaint titlePaint = new android.text.TextPaint();
    private final Paint metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean visible, focused, pressed;
    private final RectF rect = new RectF(), imageRect = new RectF();
    private final Path clip = new Path();
    private final Shadows shadows = new Shadows();
    private final RectF shadowRect = new RectF();
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient focusSheen, focusRim;
    private float shadowScale = -1, focusWidth = -1, focusHeight = -1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    DockContinue(Context context, Runnable changed) {
        this(context, changed, IO);
    }

    DockContinue(Context context, Runnable changed, java.util.concurrent.Executor worker) {
        this.context = context.getApplicationContext();
        this.changed = changed;
        this.worker = worker;
        titlePaint.setAntiAlias(true);
        images = new PosterCache(context, () -> main.post(changed));
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
                try { url = DockArtwork.lookup(p.title, p.poster == null ? "" : p.poster, "", connection -> {}).backdrop; }
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

    void draw(Canvas c, float k, float bottom, float night, boolean noWidgets) {
        if (!visible || opacity <= .001f) return;
        int layer = opacity < .999f ? c.saveLayerAlpha(0, 0, 1920 * k, bottom, Math.round(255 * opacity)) : c.save();
        titlePaint.setTypeface(Ui.semibold); titlePaint.setTextSize(36 * k); titlePaint.setColor(ActionSurface.ink(night, 0));
        metaPaint.setTypeface(Ui.medium); metaPaint.setTextSize(26 * k); metaPaint.setColor(ActionSurface.ink(night, 0));
        Paint title = titlePaint, meta = metaPaint;
        headingPaint.setTypeface(Ui.displayBold); headingPaint.setTextSize(54 * k); headingPaint.setColor(ActionSurface.ink(night, 0));
        float headingBaseline = Math.max(170 * k, bottom - 550 * k);
        c.drawText(AppStrings.get(R.string.home_continue_watching), 60 * k, headingBaseline - 20 * k, headingPaint);
        if (items.isEmpty()) { c.drawText(AppStrings.get(R.string.home_watching_empty), 60 * k, headingBaseline + 65 * k, meta); c.restoreToCount(layer); return; }
        if (shadowScale != k) {
            shadowScale = k;
            shadow.setMaskFilter(new BlurMaskFilter(30 * k, BlurMaskFilter.Blur.NORMAL));
        }
        float areaTop = headingBaseline + 40 * k;
        float height = Math.min(410 * k, Math.max(180 * k, bottom - areaTop - 100 * k));
        float width = height * 16 / 9;
        float top = areaTop + (bottom - areaTop - height - 90 * k) / 2;
        c.save(); c.clipRect(40 * k, headingBaseline + 15 * k, 1880 * k, bottom);
        boolean fadingImage = false;
        int first = Math.max(0, (int) Math.floor(scroll));
        for (int i = first; i < Math.min(items.size(), first + 4); i++) {
            Recs.Pick p = items.get(i);
            float left = 60 * k + (i - scroll) * (width + 45 * k);
            if (left >= 1880 * k) break;
            if (left + width <= 40 * k) continue;
            float scaleFocus = 1 + .05f * cardFocus[i];
            float cx = left + width / 2, cy = top + height / 2 - 20 * k - 6 * k * cardFocus[i];
            rect.set(cx - width * scaleFocus / 2, cy - height * scaleFocus / 2, cx + width * scaleFocus / 2, cy + height * scaleFocus / 2);
            float focus = Ui.clamp(cardFocus[i]);
            c.save(); c.translate(cx, cy); c.scale(scaleFocus, scaleFocus);
            shadowRect.set(-width / 2, -height / 2, width / 2, height / 2);
            shadowRect.offset(0, (10 + 14 * focus) * k);
            float restAlpha = .14f + (.32f - .14f) * night;
            float focusAlpha = .2f + (.28f - .2f) * night;
            shadow.setColor(Ui.alpha(0xFF000000, restAlpha + focusAlpha * focus));
            shadows.draw(c, shadowRect, 28 * k, 30 * k, shadow);
            c.restore();
            paint.setAlpha(255);
            paint.setColor(0xFF343A3D); paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(rect, 28 * k, 28 * k, paint);
            String url = paths.get(p.watchingKey);
            Bitmap image = url == null || url.isEmpty() ? null : images.poster(url);
            if (image != null) {
                long now = SystemClock.uptimeMillis();
                Long appeared = imageAppeared.get(url);
                if (appeared == null) { appeared = now; imageAppeared.put(url, appeared); }
                float progress = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false)
                        ? 1 : Ui.clamp((now - appeared) / 240f);
                fadingImage |= progress < 1;
                paint.setAlpha(Math.round(255 * progress * progress * (3 - 2 * progress)));
                clip.reset(); clip.addRoundRect(rect, 28 * k, 28 * k, Path.Direction.CW);
                c.save(); c.clipPath(clip);
                float scale = Math.max(rect.width() / image.getWidth(), rect.height() / image.getHeight());
                float w = image.getWidth() * scale, h = image.getHeight() * scale;
                imageRect.set(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2);
                c.drawBitmap(image, null, imageRect, paint); c.restore(); paint.setAlpha(255);
            }
            if (focus > .01f) {
                if (focusSheen == null || focusWidth != width || focusHeight != height) {
                    focusWidth = width; focusHeight = height;
                    focusSheen = new LinearGradient(-width / 2, -height / 2, 0, height / 2,
                            0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                    focusRim = new LinearGradient(0, -height / 2, 0, height / 2,
                            0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                }
                c.save(); c.translate(cx, cy); c.scale(scaleFocus, scaleFocus);
                shadowRect.set(-width / 2, -height / 2, width / 2, height / 2);
                sheen.setShader(focusSheen); sheen.setAlpha(Math.round(255 * focus));
                c.drawRoundRect(shadowRect, 28 * k, 28 * k, sheen);
                rim.setStyle(Paint.Style.STROKE); rim.setShader(focusRim);
                rim.setAlpha(Math.round(255 * focus)); rim.setStrokeWidth(2.5f * k / scaleFocus);
                c.drawRoundRect(shadowRect, 28 * k, 28 * k, rim);
                c.restore();
            }
            String label = android.text.TextUtils.ellipsize(p.title, titlePaint, width, android.text.TextUtils.TruncateAt.END).toString();
            c.drawText(label, left, top + height + 37 * k, title);
            c.drawText(WatchingText.caption(p), left, top + height + 70 * k, meta);
        }
        c.restore(); c.restoreToCount(layer);
        if (fadingImage) { main.removeCallbacks(frame); main.postDelayed(frame, 16); }
    }
}
