package io.tvos;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Shared SF Pro Display fonts and text helpers for the home screen, screensavers and settings.
 * If an asset cannot be loaded, use the system font at a similar weight.
 */
final class Ui {
    private Ui() {}

    /** Android 9 to 11: older TVs and boxes, whose GPUs need the cheaper drawing paths (Shadows, rounded images). */
    static final boolean LEGACY = Build.VERSION.SDK_INT < 31;
    static final Locale RU = new Locale("ru", "RU");
    /** Legacy flags kept for compatibility with existing views; only Russian and English are selectable. */
    static volatile String language = language();
    static volatile boolean en = "en".equals(language), ru = "ru".equals(language), es = false, de = false;
    static Typeface thin, light, displayMedium, displaySemibold, displayBold, displayRegular, regular, medium, semibold;
    private static boolean loaded;
    private static final Rect BOUNDS = new Rect();
    private static final RectF OVAL = new RectF();

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        AssetManager a = c.getAssets();
        thin = sf(a, "fonts/sf_display_light.otf", 300);
        light = thin;
        displayMedium = sf(a, "fonts/sf_display_medium.otf", 500);
        displaySemibold = sf(a, "fonts/sf_display_semibold.otf", 600);
        displayBold = sf(a, "fonts/sf_display_bold.otf", 700);
        displayRegular = sf(a, "fonts/sf_display_regular.otf", 400);
        regular = displayRegular;
        medium = displayMedium;
        semibold = displaySemibold;
    }

    private static Typeface sf(AssetManager a, String path, int weight) {
        try { return Typeface.createFromAsset(a, path); }
        catch (RuntimeException e) { return Typeface.create(Typeface.DEFAULT, weight, false); }
    }

    private static String language() { return Prefs.language(); }

    static void applyLanguage() {
        language = language();
        en = "en".equals(language);
        ru = "ru".equals(language);
        es = false;
        de = false;
    }

    static Paint text(Typeface face, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Fixed-width digits (the clock and the prices don't jitter when a digit changes). */
    static Paint numbers(Typeface face, float size, int color) {
        Paint p = text(face, size, color);
        p.setFontFeatureSettings("'tnum'");
        return p;
    }

    /** Height of capital letters (and digits) above the baseline. */
    static float cap(Paint p) {
        synchronized (BOUNDS) {
            p.getTextBounds("H8", 0, 2, BOUNDS);
            return -BOUNDS.top;
        }
    }

    /** Half the cap height: added to the center line, it gives the baseline that centers the text. */
    static float halfCap(Paint p) {
        return cap(p) / 2f;
    }

    /** Left bearing of the first character (large, thin fonts start a little inward). */
    static float leftBearing(Paint p, String s) {
        if (s.isEmpty()) return 0;
        synchronized (BOUNDS) {
            p.getTextBounds(s, 0, 1, BOUNDS);
            return BOUNDS.left;
        }
    }

    static int alpha(int color, float a) {
        int base = (color >>> 24) & 255;
        return (Math.round(base * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }

    static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    static String tr(String unused, String english) { return ru ? Russian.text(english) : english; }

    static String tr(String unused, String english, String unusedSpanish) {
        return ru ? Russian.text(english) : english;
    }

    static Locale locale() { return ru ? RU : Locale.US; }

    /** Clock format chosen in tvOS settings, independent of the system setting. */
    static String clock(long now) {
        return new SimpleDateFormat(Prefs.hour24() ? "HH:mm" : "h:mm a", locale()).format(new Date(now));
    }

    /** Weather sunrise/sunset use the same launcher-only clock preference. */
    static String time(long at) {
        return new SimpleDateFormat(Prefs.hour24() ? "HH:mm" : "h:mm a", locale()).format(new Date(at));
    }

    private static final HashMap<String, String> FORMATTED = new HashMap<>();

    /**
     * A formatted value, made once per language: the bottom drawer is redrawn on every frame of its focus animation,
     * and SimpleDateFormat and String.format there cost ~10 ms a frame on a Mi Box 3.
     */
    static String once(String kind, double value, Supplier<String> format) {
        String key = kind + '|' + language + '|' + value;
        synchronized (FORMATTED) {
            String s = FORMATTED.get(key);
            if (s == null) {
                if (FORMATTED.size() > 200) FORMATTED.clear();
                s = format.get();
                FORMATTED.put(key, s);
            }
            return s;
        }
    }

    /** "domingo, 18 de outubro" (lowercase, like the iPhone in Portuguese); "Sunday, October 18" in English; "Sonntag, 18. Oktober". */
    static String date(long now) {
        return ru ? new SimpleDateFormat("EEEE, d MMMM", locale()).format(new Date(now))
                : en ? new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date(now))
                : new SimpleDateFormat(de ? "EEEE, d. MMMM" : "EEEE, d 'de' MMMM", locale()).format(new Date(now));
    }

    /** Spinning arc (work in progress). */
    static void spinner(Canvas c, float cx, float cy, float r, float seconds, Paint stroke) {
        OVAL.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(OVAL, (seconds * 360f) % 360f, 270f, false, stroke);
    }
}
