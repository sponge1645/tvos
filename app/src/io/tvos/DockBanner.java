package io.tvos;

import io.tvos.home.dock.CaptionTransition;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;

/** Exclusive logo/title caption below the left-side header, with an idle-friendly delayed fallback. */
final class DockBanner {
    private final CaptionTransition<DockBackdrop.Artwork> transition = new CaptionTransition<>(DockBanner::same, value -> value.logo == null);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();

    void show(DockBackdrop.Artwork value, boolean motion) {
        if (value != null && value.logo == null && value.title.isEmpty()) value = null;
        transition.show(value, motion, SystemClock.uptimeMillis());
    }

    private static boolean same(DockBackdrop.Artwork a, DockBackdrop.Artwork b) {
        return a == b || (a != null && b != null && a.logo == b.logo
                && (a.logo != null || a.title.equals(b.title)));
    }

    boolean tick(long up) { return transition.tick(up); }
    long nextDelay(long up) { return transition.nextDelay(up); }

    void draw(Canvas canvas, float k, boolean noTopWidgets) {
        long up = SystemClock.uptimeMillis();
        DockBackdrop.Artwork current = transition.current(up);
        float alpha = transition.opacity(up);
        if (current == null || alpha <= .004f) return;
        float left = (noTopWidgets ? 70 : 40) * k, top = (noTopWidgets ? 70 : io.tvos.home.HomeLayout.TOP + io.tvos.home.HomeLayout.BUTTON + 40) * k;
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
