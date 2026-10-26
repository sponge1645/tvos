package io.tvos;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import java.util.Map;
import java.util.WeakHashMap;

/** Shared rounded bitmap rendering, preserving the shader path for Android 9–11. */
final class RoundedImages implements RouletteRenderer.Images {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Map<Bitmap, java.lang.ref.WeakReference<BitmapShader>> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF cropBox = new RectF();
    void clear() { imageShaders.clear(); }

    @Override public void draw(Canvas c, Bitmap image, RectF cell, float r) { draw(c, image, cell, r, false); }

    /** Preserve a banner's artwork proportions inside the taller Dock tile, using a centered crop. */
    void drawCropped(Canvas c, Bitmap image, RectF cell, float r) { draw(c, image, cell, r, true); }

    private void draw(Canvas c, Bitmap image, RectF cell, float r, boolean crop) {
        RectF destination = cell;
        if (crop) {
            float scale = Math.max(cell.width() / image.getWidth(), cell.height() / image.getHeight());
            float width = image.getWidth() * scale, height = image.getHeight() * scale;
            cropBox.set(cell.centerX() - width / 2, cell.centerY() - height / 2,
                    cell.centerX() + width / 2, cell.centerY() + height / 2);
            destination = cropBox;
        }
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, destination, bitmapPaint);
            c.restore();
            return;
        }
        java.lang.ref.WeakReference<BitmapShader> ref = imageShaders.get(image);
        BitmapShader shader = ref == null ? null : ref.get();
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, new java.lang.ref.WeakReference<>(shader));
        }
        imageMatrix.setScale(destination.width() / image.getWidth(), destination.height() / image.getHeight());
        imageMatrix.postTranslate(destination.left, destination.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }
}
