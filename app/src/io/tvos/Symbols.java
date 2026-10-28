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
 * Cached monochrome SVG paths in assets/symbols, with PNG fallback. Drawn
 * like text: `pointSize` is the size of the font next to the icon, and the icon is centered on that font's cap height.
 */
final class Symbols {
    private Symbols() {}

    /**
     * Reference pixels per em. Each symbol keeps its original layout size even when replaced by a larger PNG.
     */
    private static final float PX_PER_POINT = 96 * 1.5f;
    private static final Map<String, Icon> CACHE = new HashMap<>();
    private static final class Icon {
        final SvgSymbol svg;
        final Bitmap png;
        final float width, height;
        Icon(SvgSymbol svg) { this.svg = svg; png = null; width = svg.width; height = svg.height; }
        Icon(Bitmap png) { this.png = png; svg = null; width = png.getWidth(); height = png.getHeight(); }
    }
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF RECT = new RectF();

    private static Icon get(Context c, String name) {
        synchronized (CACHE) {
            if (CACHE.containsKey(name)) return CACHE.get(name);
            Icon icon = null;
            try (InputStream in = c.getAssets().open("symbols/" + name + ".svg")) {
                icon = new Icon(SvgSymbol.read(in));
            } catch (IOException e) {
                // Existing PNGs remain a fallback if an asset is missing or unsupported.
            }
            if (icon == null) {
                try (InputStream in = c.getAssets().open("symbols/" + name + ".png")) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    if (b != null) { b = b.extractAlpha(); b.setHasMipMap(true); icon = new Icon(b); }
                } catch (IOException ignored) { }
            }
            CACHE.put(name, icon);
            return icon;
        }
    }

    static float width(Context c, String name, float pointSize) {
        Icon icon = get(c, name);
        return icon == null ? 0 : icon.width * scale(name, icon, pointSize);
    }

    static float height(Context c, String name, float pointSize) {
        Icon icon = get(c, name);
        return icon == null ? 0 : icon.height * scale(name, icon, pointSize);
    }

    static float aspect(Context c, String name) {
        Icon icon = get(c, name);
        return icon == null ? 0 : icon.width / icon.height;
    }

    private static float scale(String name, Icon icon, float pointSize) {
        return referenceSize(name) * pointSize / PX_PER_POINT / Math.max(icon.width, icon.height);
    }

    private static int referenceSize(String name) {
        switch (name) {
            case "apps": case "sync": return 123;
            case "home": return 135;
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
        Icon icon = get(c, name);
        if (icon == null) return;
        float scale = scale(name, icon, pointSize);
        synchronized (PAINT) { // Card loading also draws symbols on a background thread.
            float w = icon.width * scale, h = icon.height * scale;
            drawLocked(canvas, icon, cx - w / 2, cy - h / 2, w, h, color);
        }
    }

    /** Rasterize at the actual output size only for the cached, glyph-shadow screensaver badge. */
    static Bitmap mask(Context c, String name, int width, int height) {
        Icon icon = get(c, name);
        if (icon == null || width <= 0 || height <= 0) return null;
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(result);
        synchronized (PAINT) { drawLocked(canvas, icon, 0, 0, width, height, 0xFFFFFFFF); }
        return result;
    }

    private static void drawLocked(Canvas canvas, Icon icon, float left, float top, float width, float height, int color) {
        if (icon.svg != null) icon.svg.draw(canvas, left, top, width, height, color, PAINT);
        else {
            PAINT.setColor(color);
            RECT.set(left, top, left + width, top + height);
            canvas.drawBitmap(icon.png, null, RECT, PAINT);
        }
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
