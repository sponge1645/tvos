package io.tvos;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Handler;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Notice polling, persisted read cursor, queue, layout and fading presentation. */
final class HomeNotices {
    private static final long TOAST_MS = 6000;
    private static final int SMOKE = 0xFF111113;
    private final Context context;
    private final Scene scene;
    private final SharedPreferences ui;
    private final Executor worker;
    private final Handler handler;
    private final Runnable invalidate, wake;
    private final Consumer<String> announce;
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;
    private TextPaint toastPaint;
    private float k = 1;
    private int w;
    private final RectF tmp = new RectF();

    HomeNotices(Context context, Scene scene, Executor worker, Handler handler,
            Runnable invalidate, Runnable wake, Consumer<String> announce) {
        this.context = context.getApplicationContext();
        this.scene = scene;
        this.worker = worker;
        this.handler = handler;
        this.invalidate = invalidate;
        this.wake = wake;
        this.announce = announce;
        ui = context.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
    }
    void resize(float scale, int width) { k = scale; w = width; toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF)); }
    boolean visible() { return toast != null; }
    boolean busy() { return toast != null || !toasts.isEmpty(); }

    void add(String text) {
        toasts.add(text);
        announce.accept(text);
        wake.run();
    }

    boolean tick(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            invalidate.run();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) invalidate.run();
        return fading;
    }

    void draw(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(context, "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(context, c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    void check() {
        final Context c = context.getApplicationContext();
        worker.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<JSONObject> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o);
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (JSONObject notice : fresh) add(NoticeText.render(notice)); });
        });
    }
}
