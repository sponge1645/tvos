package io.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;

/** One transparent, cached glyph-shadow badge for all screensaver sources. */
final class ScreensaverWeather {
    private final Context context;
    private Bitmap badge;
    private String symbol = "", degrees = "";
    private float textSize, density, padding, baseline;

    ScreensaverWeather(Context context) { this.context = context; }

    void setWeather(String symbol, String degrees) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24, metrics);
        if (badge != null && this.symbol.equals(symbol) && this.degrees.equals(degrees)
                && textSize == size && density == metrics.density) return;
        this.symbol = symbol;
        this.degrees = degrees;
        textSize = size;
        density = metrics.density;
        padding = 10 * density; // Expanded below to include the actual blurred alpha bounds.

        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTypeface(Ui.displayMedium);
        text.setTextSize(textSize); // exactly the location label's 24 sp
        text.setColor(0xEBFFFFFF);
        text.setShadowLayer(8 * density, 0, 2 * density, 0xE6000000);
        float aspect = Symbols.aspect(context, symbol);
        // Normalize every weather symbol to the letters' cap height, including wide clouds.
        float iconHeight = Ui.cap(text);
        float iconWidth = iconHeight * aspect;
        float gap = aspect == 0 ? 0 : 10 * density;
        Bitmap icon = Symbols.mask(context, symbol, (int) Math.ceil(iconWidth), (int) Math.ceil(iconHeight));
        Bitmap shadow = null;
        int[] offset = new int[2];
        float sx = 1f, sy = 1f;
        if (icon != null) {
            // Bitmap shadow layers can shadow the bitmap rectangle on older renderers.
            // Blur only the symbol's alpha, then composite that mask behind the glyph.
            Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG);
            blur.setMaskFilter(new BlurMaskFilter(8 * density, BlurMaskFilter.Blur.NORMAL));
            shadow = icon.extractAlpha(blur, offset);
            sx = iconWidth / icon.getWidth();
            sy = iconHeight / icon.getHeight();
            float horizontal = Math.max(-offset[0] * sx,
                    (offset[0] + shadow.getWidth() - icon.getWidth()) * sx);
            float vertical = Math.max(-offset[1] * sy - 2 * density,
                    (offset[1] + shadow.getHeight() - icon.getHeight()) * sy + 2 * density);
            padding = (float) Math.ceil(Math.max(padding, Math.max(horizontal, vertical)));
        }
        baseline = padding - text.ascent();
        int width = Math.max(1, (int) Math.ceil(padding * 2 + iconWidth + gap + text.measureText(degrees)));
        int height = Math.max(1, (int) Math.ceil(padding * 2 + text.descent() - text.ascent()));
        // Bake shadows from the glyph alpha into a small transparent bitmap, never the video/photo layer.
        // Keep previous bitmaps valid for any in-flight RenderNode; Android releases them when no longer used.
        badge = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(badge);
        if (icon != null) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            paint.setColor(0xEBFFFFFF);
            float top = baseline - iconHeight;
            Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            shade.setColor(0xE6000000);
            float left = padding + offset[0] * sx;
            float shadowTop = top + 2 * density + offset[1] * sy;
            canvas.drawBitmap(shadow, null, new RectF(left, shadowTop,
                    left + shadow.getWidth() * sx, shadowTop + shadow.getHeight() * sy), shade);
            canvas.drawBitmap(icon, null, new RectF(padding, top, padding + iconWidth, baseline), paint);
        }
        canvas.drawText(degrees, padding + iconWidth + gap, baseline, text);
    }

    int width() { return badge == null ? 0 : badge.getWidth(); }
    int height() { return badge == null ? 0 : badge.getHeight(); }

    void draw(Canvas canvas, float left, float top) {
        if (badge != null) canvas.drawBitmap(badge, left, top, null);
    }

    void drawRightAligned(Canvas canvas, float right, float baseline) {
        draw(canvas, right - width() + padding, baseline - this.baseline);
    }
}
