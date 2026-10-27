package io.tvos;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import java.io.IOException;
import java.io.InputStream;

/** Theme wallpaper, decoded once per size/theme with a bounded, single-image cache. */
final class SettingsBackground {
    private final AssetManager assets;
    private Bitmap bitmap;
    private boolean cachedLight;
    private int cachedWidth, cachedHeight;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect source = new Rect(), destination = new Rect();

    SettingsBackground(Context context) { assets = context.getApplicationContext().getAssets(); }

    void clear() {
        // A submitted hardware frame may still reference the old image. Let Android release it.
        bitmap = null;
        cachedWidth = cachedHeight = 0;
    }

    void draw(Canvas canvas, int width, int height, boolean light) {
        if (width <= 0 || height <= 0) return;
        if (cachedLight != light || cachedWidth != width || cachedHeight != height) {
            clear();
            cachedLight = light;
            cachedWidth = width;
            cachedHeight = height;
            try {
                bitmap = load(width, height, light);
            } catch (IOException | OutOfMemoryError error) {
                // Remember the failed size/theme too: no decode/log loop on every frame.
                android.util.Log.w("tvOS-Settings", "Settings wallpaper unavailable", error);
            }
        }
        if (bitmap == null) {
            canvas.drawColor(light ? 0xFFB2F0F4 : 0xFF092632);
            return;
        }
        // Fill the viewport without stretching. Only the soft wallpaper is sampled down;
        // labels, icons, glass and focus remain at the screen's native resolution.
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();
        if ((long) bw * height > (long) bh * width) {
            int cropWidth = Math.max(1, Math.round(bh * (float) width / height));
            int left = (bw - cropWidth) / 2;
            source.set(left, 0, left + cropWidth, bh);
        } else {
            int cropHeight = Math.max(1, Math.round(bw * (float) height / width));
            int top = (bh - cropHeight) / 2;
            source.set(0, top, bw, top + cropHeight);
        }
        destination.set(0, 0, width, height);
        canvas.drawBitmap(bitmap, source, destination, bitmapPaint);
    }

    private Bitmap load(int width, int height, boolean light) throws IOException {
        String path = light ? "settings/light.webp" : "settings/dark.webp";
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream input = assets.open(path)) { BitmapFactory.decodeStream(input, null, options); }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("Invalid wallpaper dimensions");
        int targetWidth = Math.max(1, Math.min(960, width / 2));
        int targetHeight = Math.max(1, Math.min(540, height / 2));
        options.inSampleSize = 1;
        while ((options.outWidth + (long) options.inSampleSize - 1) / options.inSampleSize > targetWidth
                || (options.outHeight + (long) options.inSampleSize - 1) / options.inSampleSize > targetHeight) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap result;
        try (InputStream input = assets.open(path)) { result = BitmapFactory.decodeStream(input, null, options); }
        if (result == null) throw new IOException("Invalid wallpaper image");
        return result;
    }
}
