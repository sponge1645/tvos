package io.tvos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;

/** Static settings atmosphere and its size/theme cache; no Activity or menu state. */
final class SettingsBackground {
    private Bitmap bitmap;
    private boolean cachedLight;
    private int cachedWidth, cachedHeight;
    private final Paint atmosphere = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect destination = new Rect();

    void clear() {
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
        }
    }

    void draw(Canvas canvas, int width, int height, boolean light) {
        if (width <= 0 || height <= 0) return;
        if (bitmap == null || cachedLight != light || cachedWidth != width || cachedHeight != height) {
            clear();
            // Only the smooth gradient is downsampled; text, symbols and controls remain native size.
            int bw = Math.max(1, (width + 1) / 2);
            int bh = Math.max(1, (height + 1) / 2);
            try {
                bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                Canvas cached = new Canvas(bitmap);
                cached.scale((float) bw / width, (float) bh / height);
                render(cached, width, height, light);
                cachedLight = light;
                cachedWidth = width;
                cachedHeight = height;
            } catch (OutOfMemoryError oom) {
                clear();
                render(canvas, width, height, light);
                return;
            }
        }
        destination.set(0, 0, width, height);
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint);
    }

    /** Deep blue / petrol / charcoal atmosphere from the tvOS-style reference. */
    private void render(Canvas c, int w, int h, boolean light) {
        if (light) {
            // Airy cyan/blue with a faint lavender and coral bloom, like tvOS light mode.
            atmosphere.setShader(new LinearGradient(0, 0, w, h,
                    0xFFB6EAF9, 0xFFE4F8FA, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, atmosphere);
            atmosphere.setShader(new RadialGradient(w * 0.12f, h * 0.40f, w * 0.68f,
                    new int[]{0xC5D8FFFF, 0x70C3F3FB, 0x00FFFFFF},
                    new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, atmosphere);
            atmosphere.setShader(new RadialGradient(w * 0.57f, h * 1.03f, w * 0.55f,
                    new int[]{0xA9F6BFC7, 0x55E7C8E6, 0x00FFFFFF},
                    new float[]{0f, .50f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, atmosphere);
            atmosphere.setShader(new RadialGradient(w * 0.91f, h * 0.22f, w * 0.50f,
                    new int[]{0xA6B2E9FF, 0x4CB5E9F7, 0x00FFFFFF},
                    new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, atmosphere);
            atmosphere.setShader(null);
            return;
        }
        atmosphere.setShader(new LinearGradient(0, 0, w, h,
                0xFF16323F, 0xFF131A1F, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, atmosphere);
        atmosphere.setShader(new RadialGradient(w * 0.62f, h * 0.05f, w * 0.73f,
                new int[]{0xA6266774, 0x4C1E4451, 0x00151C21},
                new float[]{0f, .44f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, atmosphere);
        atmosphere.setShader(new RadialGradient(w * 0.06f, h * 0.66f, w * 0.7f,
                new int[]{0x70304C5C, 0x2B28434D, 0x00131B20},
                new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, atmosphere);
        atmosphere.setShader(new LinearGradient(0, h * .28f, 0, h,
                0x0010161B, 0xA80A0F13, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, atmosphere);
        atmosphere.setShader(null);
    }
}
