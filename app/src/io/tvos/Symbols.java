package io.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Monochrome PNG icons in assets/symbols. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 */
final class Symbols {
    private Symbols() {}

    /**
     * Reference pixels per em. Each symbol keeps its original layout size even when replaced by a larger PNG.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    static Bitmap get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Bitmap b = null;
            try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                b = BitmapFactory.decodeStream(in);
                if (b != null) {
                    b = b.extractAlpha();
                    b.setHasMipMap(true);
                }
            } catch (IOException ignored) {
            }
            CACHE.put(name, b);
            return b;
        }
    }

    static float width(Context c, String name, float pointSize) {
        Bitmap b = get(c, name);
        return b == null ? 0 : b.getWidth() * scale(name, b, pointSize);
    }

    private static float scale(String name, Bitmap b, float pointSize) {
        return referenceSize(name) * pointSize / PX_PER_POINT / Math.max(b.getWidth(), b.getHeight());
    }

    private static int referenceSize(String name) {
        switch (name) {
            case "apps": case "sync": return 123;
            case "home": return 135;
            case "bitcoin": return 138;
            case "check": return 120;
            case "chevron_right": case "chevron_down": return 89;
            case "play": return 102;
            case "bedtime": case "clear_night": return 148;
            case "snowflake": return 150;
            case "foggy": case "rainy": return 155;
            case "gamepad": case "sparkles": case "partly_cloudy_day": return 156;
            case "partly_cloudy_night": case "bell": return 154;
            case "speaker": case "live_tv": case "tv_settings": return 153;
            case "thunderstorm": return 160;
            case "sunny": case "cloud": return 166;
            case "sunrise": case "sunset": return 151;
            case "search": case "vpn": return 144;
            default: return 152;
        }
    }

    /** Draws centered at (cx, cy) in the given color (lighter, translucent parts of the symbol stay lighter). */
    static void draw(Context c, Canvas canvas, String name, float cx, float cy, float pointSize, int color) {
        Bitmap b = get(c, name);
        if (b == null) return;
        synchronized (PAINT) {   // card loading (another thread) also draws symbols
            drawLocked(canvas, b, cx, cy, scale(name, b, pointSize), color);
        }
    }

    private static void drawLocked(Canvas canvas, Bitmap b, float cx, float cy, float scale, int color) {
        float w = b.getWidth() * scale, h = b.getHeight() * scale;
        PAINT.setColor(color);
        RECT.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        canvas.drawBitmap(b, null, RECT, PAINT);
    }

    /** Weather symbol for an Open-Meteo WMO weather code. */
    static String weather(int code, boolean day) {
        if (code <= 1) return day ? "sunny" : "clear_night";
        if (code == 2) return day ? "partly_cloudy_day" : "partly_cloudy_night";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "foggy";
        if (code >= 51 && code <= 57) return "rainy";
        if (code == 65 || code == 67 || code == 82) return "rainy";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "snowflake";
        if (code >= 95) return "thunderstorm";
        return "rainy";
    }
}
