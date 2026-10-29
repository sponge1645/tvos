package io.tvos;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextPaint;

/** Small banner caption below the right-side controls; no backdrop scrim. */
final class DockBanner {
    private DockBackdrop.Artwork artwork;
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private long shown;
    private boolean animate;

    void show(DockBackdrop.Artwork value, boolean motion) {
        if (artwork != null && value != null && artwork.background == value.background) { artwork = value; return; }
        artwork = value;
        shown = SystemClock.uptimeMillis();
        animate = motion;
    }

    void draw(Canvas canvas, float k, int width) {
        if (artwork == null) return;
        float alpha = animate ? Math.min(1, Math.max(0, (SystemClock.uptimeMillis() - shown) / 800f)) : 1;
        float right = width - 40 * k, top = 180 * k;
        if (artwork.logo != null) {
            float scale = Math.min(420 * k / artwork.logo.getWidth(), 170 * k / artwork.logo.getHeight());
            bounds.set(right - artwork.logo.getWidth() * scale, top, right, top + artwork.logo.getHeight() * scale);
            image.setAlpha(Math.round(255 * alpha));
            canvas.drawBitmap(artwork.logo, null, bounds, image);
        } else if (!artwork.title.isEmpty()) {
            title.setTypeface(Ui.semibold); title.setTextSize(36 * k); title.setColor(0xFFFFFFFF);
            title.setShadowLayer(4 * k, 0, 2 * k, 0xB0000000);
            title.setAlpha(Math.round(255 * alpha));
            String text = TextUtils.ellipsize(artwork.title, title, 600 * k, TextUtils.TruncateAt.END).toString();
            canvas.drawText(text, right - title.measureText(text), top - title.ascent(), title);
        }
    }
}
