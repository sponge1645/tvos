package io.tvos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;

/** Approved SVG gradients rendered natively, with a bounded single-theme raster cache. */
final class SettingsBackground {
    private static final float[] STOPS = new float[49];
    static { for (int i = 0; i < STOPS.length; i++) STOPS[i] = i / 48f; }

    private static final class Glow {
        final float cx, cy, rx, ry;
        final int[] colors = new int[STOPS.length];
        Glow(float cx, float cy, float rx, float ry, int color, float opacity) {
            this.cx = cx; this.cy = cy; this.rx = rx; this.ry = ry;
            double edge = Math.exp(-4.5);
            for (int i = 0; i < colors.length; i++) {
                double value = (Math.exp(-4.5 * STOPS[i] * STOPS[i]) - edge) / (1 - edge);
                int alpha = Math.max(0, Math.min(255, (int) Math.round(255 * opacity * value)));
                colors[i] = color & 0x00FFFFFF | alpha << 24;
            }
        }
    }
    // Reference coordinates and colors match docs/design/settings-{light,dark}.svg.
    private static final Glow[] LIGHT = {
        new Glow(971.974f, 669.021f, 756.238f, 1066.378f, 0xFFF1FBF1, 0.471408f),
        new Glow(923.778f, 826.562f, 799.977f, 817.499f, 0xFF00B5F1, 0.176345f)
    };
    private static final Glow[] DARK = {
        new Glow(1021.398f, 123.860f, 1555.396f, 887.556f, 0xFF355044, 1.000000f),
        new Glow(1037.092f, 226.521f, 1987.793f, 938.544f, 0xFF001435, 0.393656f)
    };
    private Bitmap bitmap;
    private boolean cachedLight;
    private int cachedWidth, cachedHeight;
    private final Paint gradient = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect destination = new Rect();

    void clear() {
        // Submitted GPU frames may still reference the old image.
        bitmap = null;
        cachedWidth = cachedHeight = 0;
    }

    void draw(Canvas canvas, int width, int height, boolean light) {
        if (width <= 0 || height <= 0) return;
        if (cachedLight != light || cachedWidth != width || cachedHeight != height) {
            clear();
            cachedLight = light; cachedWidth = width; cachedHeight = height;
            int bw = Math.max(1, Math.min(960, (width + 1) / 2));
            int bh = Math.max(1, Math.min(540, (height + 1) / 2));
            try {
                bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                render(new Canvas(bitmap), bw, bh, light);
            } catch (OutOfMemoryError error) {
                bitmap = null;
                gradient.setShader(null);
                android.util.Log.w("tvOS-Settings", "Settings gradient cache unavailable", error);
            }
        }
        if (bitmap == null) {
            canvas.drawColor(light ? 0xFFB7D3F1 : 0xFF141923);
            return;
        }
        destination.set(0, 0, width, height);
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint);
    }

    private void render(Canvas canvas, int width, int height, boolean light) {
        gradient.setShader(new LinearGradient(0, 0, 0, height,
                light ? 0xFFB7D3F1 : 0xFF141923,
                light ? 0xFFB9D4F1 : 0xFF191715, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, gradient);
        for (Glow glow : light ? LIGHT : DARK) {
            int saved = canvas.save();
            canvas.translate(glow.cx * width / 1920, glow.cy * height / 1080);
            canvas.scale(glow.rx * width / 1920, glow.ry * height / 1080);
            gradient.setShader(new RadialGradient(0, 0, 1, glow.colors, STOPS, Shader.TileMode.CLAMP));
            canvas.drawRect(-1, -1, 1, 1, gradient);
            canvas.restoreToCount(saved);
        }
        gradient.setShader(null);
    }
}
