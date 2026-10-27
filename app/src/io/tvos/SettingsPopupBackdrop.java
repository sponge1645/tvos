package io.tvos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** One bounded, softly blurred settings snapshot per modal session, including older Android. */
final class SettingsPopupBackdrop {
    interface Source { void draw(Canvas canvas); }
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF destination = new RectF();
    private Bitmap bitmap;
    private int width, height;
    private boolean attempted, light;

    void clear() { bitmap = null; attempted = false; width = height = 0; }

    void prepare(int w, int h, boolean theme, Source source) {
        if (width != w || height != h || light != theme) clear();
        if (attempted || source == null || w <= 0 || h <= 0) return;
        width = w; height = h; light = theme; attempted = true;
        int bw = Math.max(1, Math.min(320, w)), bh = Math.max(1, Math.round(h * (bw / (float) w)));
        bh = Math.min(180, bh);
        try {
            Bitmap image = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.scale(bw / (float) w, bh / (float) h);
            source.draw(canvas);
            int[] pixels = new int[bw * bh], scratch = new int[pixels.length];
            image.getPixels(pixels, 0, bw, 0, 0, bw, bh);
            for (int pass = 0; pass < 2; pass++) {
                blur(pixels, scratch, bw, bh, true);
                blur(scratch, pixels, bw, bh, false);
            }
            image.setPixels(pixels, 0, bw, 0, 0, bw, bh);
            bitmap = image;
        } catch (OutOfMemoryError error) {
            bitmap = null;
            android.util.Log.w("tvOS-Settings", "Update backdrop unavailable", error);
        }
    }

    void draw(Canvas canvas, RectF panel, float radius) {
        if (bitmap == null) return;
        int save = canvas.save();
        clip.reset(); clip.addRoundRect(panel, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        destination.set(0, 0, width, height);
        canvas.drawBitmap(bitmap, null, destination, paint);
        canvas.restoreToCount(save);
    }

    private static void blur(int[] source, int[] target, int w, int h, boolean horizontal) {
        int outer = horizontal ? h : w, inner = horizontal ? w : h;
        for (int row = 0; row < outer; row++) {
            int red = 0, green = 0, blue = 0;
            for (int pos = -4; pos < inner; pos++) {
                int add = pos + 4, value;
                if (add < inner) {
                    value = source[horizontal ? row * w + add : add * w + row];
                    red += (value >>> 16) & 255; green += (value >>> 8) & 255; blue += value & 255;
                }
                if (pos >= 0) {
                    int count = Math.min(inner - 1, pos + 4) - Math.max(0, pos - 4) + 1;
                    target[horizontal ? row * w + pos : pos * w + row] = 0xFF000000
                            | (red / count) << 16 | (green / count) << 8 | blue / count;
                }
                int remove = pos - 4;
                if (remove >= 0) {
                    value = source[horizontal ? row * w + remove : remove * w + row];
                    red -= (value >>> 16) & 255; green -= (value >>> 8) & 255; blue -= value & 255;
                }
            }
        }
    }
}
