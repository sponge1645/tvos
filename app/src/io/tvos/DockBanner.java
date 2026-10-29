package io.tvos;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;

/** Exclusive logo/title caption below the left-side header, with an idle-friendly delayed fallback. */
final class DockBanner {
    private static final long OUT_MS = 260, IN_MS = 420, TITLE_DELAY_MS = 700;
    private DockBackdrop.Artwork current, target;
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private long fadeAt = -1, appearAt;
    private float fadeFrom;
    private boolean outgoing;

    void show(DockBackdrop.Artwork value, boolean motion) {
        long now = SystemClock.uptimeMillis();
        advance(now);
        if (value != null && value.logo == null && value.title.isEmpty()) value = null;
        if (same(target, value)) return;
        target = value;
        if (!motion) { current = target; fadeAt = -1; outgoing = false; return; }
        float opacity = opacity(now);
        if (current != null && opacity > 0) {
            fadeFrom = opacity; fadeAt = now; outgoing = true;
            appearAt = now + OUT_MS;
        } else {
            current = null; fadeAt = -1; outgoing = false;
            appearAt = now;
        }
        if (target != null && target.logo == null) appearAt += TITLE_DELAY_MS;
    }

    private static boolean same(DockBackdrop.Artwork a, DockBackdrop.Artwork b) {
        return a == b || (a != null && b != null && a.logo == b.logo
                && (a.logo != null || a.title.equals(b.title)));
    }

    private void advance(long up) {
        if (outgoing && up >= fadeAt + OUT_MS) {
            current = null; fadeAt = -1; outgoing = false;
        }
        if (current == null && target != null && up >= appearAt) {
            current = target; fadeAt = appearAt; fadeFrom = 0;
        }
        if (!outgoing && fadeAt >= 0 && up >= fadeAt + IN_MS) fadeAt = -1;
    }

    private float opacity(long up) {
        if (fadeAt < 0) return current == null ? 0 : 1;
        float p = Math.max(0, Math.min(1, (up - fadeAt) / (float) (outgoing ? OUT_MS : IN_MS)));
        float eased = p * p * (3 - 2 * p);
        return outgoing ? fadeFrom * (1 - eased) : eased;
    }

    boolean tick(long up) {
        boolean changed = fadeAt >= 0;
        DockBackdrop.Artwork before = current;
        advance(up);
        return changed || before != current || fadeAt >= 0;
    }

    long nextDelay(long up) {
        return current == null && target != null ? Math.max(0, appearAt - up) : Long.MAX_VALUE;
    }

    void draw(Canvas canvas, float k) {
        long up = SystemClock.uptimeMillis();
        advance(up);
        float alpha = opacity(up);
        if (current == null || alpha <= .004f) return;
        float left = 40 * k, top = 180 * k;
        if (current.logo != null) {
            float scale = Math.min(420 * k / current.logo.getWidth(), 170 * k / current.logo.getHeight());
            bounds.set(left, top, left + current.logo.getWidth() * scale, top + current.logo.getHeight() * scale);
            image.setAlpha(Math.round(255 * alpha));
            canvas.drawBitmap(current.logo, null, bounds, image);
        } else {
            title.setTypeface(Ui.semibold); title.setTextSize(36 * k); title.setColor(0xFFFFFFFF);
            title.setShadowLayer(4 * k, 0, 2 * k, 0xB0000000);
            title.setAlpha(Math.round(255 * alpha));
            String text = TextUtils.ellipsize(current.title, title, 600 * k, TextUtils.TruncateAt.END).toString();
            canvas.drawText(text, left, top - title.ascent(), title);
        }
    }
}
