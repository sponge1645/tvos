package io.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
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
    private boolean visible, focused, pressed;
    private final RectF rect = new RectF(), imageRect = new RectF();
    private final Path clip = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    DockContinue(Context context, Runnable changed) {
        this(context, changed, IO);
    }

    DockContinue(Context context, Runnable changed, java.util.concurrent.Executor worker) {
        this.changed = changed;
        this.worker = worker;
        images = new PosterCache(context, () -> main.post(changed));
    }

    boolean visible() { return visible; }
    void show(List<Recs.Pick> picks) {
        visible = true;
        StringBuilder key = new StringBuilder();
        for (Recs.Pick p : picks) key.append(p.watchingKey).append(p.resumeUri).append(p.meta).append('\n');
        if (signature.equals(key.toString())) return;
        String selected = index < items.size() ? items.get(index).watchingKey : null;
        signature = key.toString(); generation++; pending.clear(); paths.clear();
        items = new ArrayList<>(picks.subList(0, Math.min(10, picks.size())));
        index = 0;
        for (int i = 0; i < items.size(); i++) if (java.util.Objects.equals(selected, items.get(i).watchingKey)) index = i;
        if (items.isEmpty()) focused = pressed = false;
        prepare(); changed.run();
    }

    void hide() {
        if (!visible) return;
        visible = focused = pressed = false; generation++; signature = "";
        items = Collections.emptyList(); paths.clear(); pending.clear();
        images.setWindow(Collections.emptySet()); changed.run();
    }

    private void prepare() {
        int first = index / 2 * 2;
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
        int first = index / 2 * 2;
        for (int i = first; i < Math.min(items.size(), first + 4); i++) {
            String path = paths.get(items.get(i).watchingKey);
            if (path != null && !path.isEmpty()) wanted.add(path);
        }
        images.setWindow(wanted);
        for (String path : wanted) images.poster(path);
    }

    boolean key(KeyEvent e, boolean canEnter, Runnable top, Consumer<Recs.Pick> open) {
        if (!visible) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        if (!focused) {
            if (canEnter && !items.isEmpty() && code == KeyEvent.KEYCODE_DPAD_UP) {
                if (down) { focused = true; pressed = false; changed.run(); }
                return true;
            }
            return false;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down) { index = Math.max(0, Math.min(items.size() - 1, index + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1))); prepare(); changed.run(); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP) {
            if (down) { focused = pressed = false; if (code == KeyEvent.KEYCODE_DPAD_UP) top.run(); changed.run(); }
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
        if (!visible) return;
        Paint title = Ui.text(Ui.semibold, 36 * k, ActionSurface.ink(night, 0));
        Paint meta = Ui.text(Ui.medium, 26 * k, ActionSurface.ink(night, 0));
        float top = Math.max(170 * k, bottom - 550 * k);
        c.drawText(AppStrings.get(R.string.home_continue_watching), 40 * k, top, title);
        if (items.isEmpty()) { c.drawText(AppStrings.get(R.string.home_watching_empty), 40 * k, top + 65 * k, meta); return; }
        int first = index / 2 * 2;
        float width = 850 * k, height = width * 9 / 16;
        height = Math.min(height, Math.max(180 * k, bottom - top - 110 * k));
        width = height * 16 / 9;
        for (int i = first; i < Math.min(items.size(), first + 3); i++) {
            Recs.Pick p = items.get(i);
            float left = 40 * k + (i - first) * (width + 45 * k);
            rect.set(left, top + 28 * k, left + width, top + 28 * k + height);
            paint.setColor(0xFF343A3D); paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(rect, 28 * k, 28 * k, paint);
            String url = paths.get(p.watchingKey);
            Bitmap image = url == null || url.isEmpty() ? null : images.poster(url);
            if (image != null) {
                clip.reset(); clip.addRoundRect(rect, 28 * k, 28 * k, Path.Direction.CW);
                c.save(); c.clipPath(clip);
                float scale = Math.max(rect.width() / image.getWidth(), rect.height() / image.getHeight());
                float w = image.getWidth() * scale, h = image.getHeight() * scale;
                imageRect.set(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2);
                c.drawBitmap(image, null, imageRect, paint); c.restore();
            }
            if (focused && i == index) { paint.setColor(0xFFFFFFFF); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4 * k); c.drawRoundRect(rect, 28 * k, 28 * k, paint); paint.setStyle(Paint.Style.FILL); }
            String label = android.text.TextUtils.ellipsize(p.title, new android.text.TextPaint(title), width, android.text.TextUtils.TruncateAt.END).toString();
            c.drawText(label, left, rect.bottom + 37 * k, title);
            c.drawText(WatchingText.caption(p), left, rect.bottom + 70 * k, meta);
        }
    }
}
