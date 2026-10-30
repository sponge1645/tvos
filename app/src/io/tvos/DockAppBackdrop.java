package io.tvos;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Local monochrome app artwork, composed once on the Dock artwork worker. */
final class DockAppBackdrop {
    private DockAppBackdrop() {}

    static Backdrop create(Context context, String pkg) {
        final Drawable icon;
        try { icon = context.getPackageManager().getApplicationIcon(pkg); }
        catch (PackageManager.NameNotFoundException | RuntimeException e) {
            AppLog.w("Dock app icon unavailable: " + e.getClass().getSimpleName());
            return null;
        }
        if (icon == null) return null;
        Bitmap stamp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
        Bitmap image = null;
        try {
            int width = icon.getIntrinsicWidth(), height = icon.getIntrinsicHeight();
            float scale = width > 0 && height > 0 ? 512f / Math.max(width, height) : 1;
            int w = width > 0 && height > 0 ? Math.max(1, Math.round(width * scale)) : 512;
            int h = width > 0 && height > 0 ? Math.max(1, Math.round(height * scale)) : 512;
            icon.setBounds((512 - w) / 2, (512 - h) / 2, (512 + w) / 2, (512 + h) / 2);
            icon.draw(new Canvas(stamp));
            image = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            paint.setShader(new LinearGradient(0, 0, 0, 1080, 0xFF343A36, 0xFF10130F, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, 1920, 1080, paint);
            paint.setShader(null);
            // Preserve alpha and shading, lifting dark icon detail above the dark background.
            float r = .299f * .49f, g = .587f * .49f, b = .114f * .49f;
            paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                    r, g, b, 0, 100, r, g, b, 0, 100, r, g, b, 0, 100, 0, 0, 0, 1, 0})));
            canvas.drawBitmap(stamp, null, new RectF(780, 220, 1140, 580), paint);
            Backdrop ready = Backdrop.hero(image);
            image = null; // hero owns and recycles its source.
            return ready;
        } finally {
            stamp.recycle();
            if (image != null && !image.isRecycled()) image.recycle();
        }
    }
}
