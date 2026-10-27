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
    private static final float[] STOPS = new float[25];
    static { for (int i = 0; i < STOPS.length; i++) STOPS[i] = i / 24f; }

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
        new Glow(957.662f, 831.439f, 471.844f, 662.348f, 0xFFFFE8BF, 1f),
        new Glow(947.177f, 720.356f, 504.540f, 386.796f, 0xFFFFEEC9, 0.874650f)
    };
    private static final Glow[] DARK = {
        new Glow(1176.515f, 12.345f, 961.493f, 1136.069f, 0xFF006176, 1f),
        new Glow(201.015f, 306.489f, 1361.093f, 1287.957f, 0xFF0B7EA4, 0.327686f)
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
            canvas.drawColor(light ? 0xFFB9F6FB : 0xFF09283A);
            return;
        }
        destination.set(0, 0, width, height);
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint);
    }

    private void render(Canvas canvas, int width, int height, boolean light) {
        gradient.setShader(new LinearGradient(0, 0, 0, height,
                light ? 0xFFB9F6FB : 0xFF09283A,
                light ? 0xFFB6F1F9 : 0xFF0B1824, Shader.TileMode.CLAMP));
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
