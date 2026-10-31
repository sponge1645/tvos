package io.tvos;

import io.tvos.artwork.AppBranding;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import java.util.HashMap;
import java.util.Map;

/** Dock logo sizing, filtering and bounded per-app caches. Owned by one HomeView/UI thread. */
final class DockLogoRenderer {
    private final Map<String, Cached> normal = new HashMap<>(), dark = new HashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final PorterDuffColorFilter white = new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);

    private static final class Cached {
        final Bitmap source, image;
        final float width, height, focusGrow, ink, scale;
        Cached(Bitmap source, Bitmap image, float width, float height, float focusGrow, float ink, float scale) {
            this.source = source; this.image = image; this.width = width; this.height = height;
            this.focusGrow = focusGrow; this.ink = ink; this.scale = scale;
        }
        boolean matches(Bitmap source, float width, float height, float focusGrow, HomeApps.Item item) {
            return this.source == source && this.width == width && this.height == height
                    && this.focusGrow == focusGrow && ink == item.logoInk && scale == item.logoScale;
        }
    }

    void clear() { normal.clear(); dark.clear(); }

    void draw(Canvas canvas, HomeApps.Item item, RectF box, float focusScale, int mode, RectF base, float focusGrow) {
        Bitmap logo = prepared(item, mode == 2 && item.logoDark != null, base, focusGrow);
        if (logo == null) return;
        float scale = focusScale / (1 + focusGrow);
        float boost = AppBranding.dockBoost(item.pkg, item.key);
        float width = logo.getWidth() * scale * boost, height = logo.getHeight() * scale * boost;
        float left = box.centerX() - width / 2, top = box.centerY() - height / 2;
        if ("com.applemusicktv".equals(item.pkg)) {
            // Anchor to a proportional right inset so focus/resize keeps the intentional asymmetry.
            left = box.right - box.width() * (92f / HomeLayout.MAX_TILE_WIDTH) - width;
        }
        // Preserve fractional coordinates through focus overshoot, reversal and settling.
        destination.set(left, top, left + width, top + height);
        paint.setColorFilter(mode == 1 ? white : null);
        canvas.drawBitmap(logo, null, destination, paint);
        paint.setColorFilter(null);
    }

    private Bitmap prepared(HomeApps.Item item, boolean useDark, RectF base, float focusGrow) {
        Bitmap source = useDark ? item.logoDark : item.logo;
        if (source == null || base == null) return source;
        Map<String, Cached> cache = useDark ? dark : normal;
        float width = base.width(), height = base.height();
        Cached old = cache.get(item.key);
        if (old != null && old.matches(source, width, height, focusGrow, item)) return old.image;
        float bw = width * (1 + focusGrow), bh = height * (1 + focusGrow);
        float lw = source.getWidth(), lh = source.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, item.logoInk))) * item.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        Bitmap image = downscale(source, Math.round(lw * scale), Math.round(lh * scale));
        // Replacing an app's source replaces its one entry; no growing source-ID string keys per reload.
        cache.put(item.key, new Cached(source, image, width, height, focusGrow, item.logoInk, item.logoScale));
        return image;
    }

    /** Halve with filtering, then finish at the exact size. Never recycle the app's source bitmap. */
    private static Bitmap downscale(Bitmap source, int width, int height) {
        width = Math.max(1, width); height = Math.max(1, height);
        Bitmap bitmap = source;
        while (bitmap.getWidth() / 2 >= width && bitmap.getHeight() / 2 >= height) {
            Bitmap half = Bitmap.createScaledBitmap(bitmap, bitmap.getWidth() / 2, bitmap.getHeight() / 2, true);
            if (bitmap != source) bitmap.recycle();
            bitmap = half;
        }
        Bitmap result = Bitmap.createScaledBitmap(bitmap, width, height, true);
        if (bitmap != source && bitmap != result) bitmap.recycle();
        return result;
    }
}
