// TVOS_WEATHER_FADE_V1
package io.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver, in the same style as the home screen:
 *  - the recommendations' photos in full screen, one every 30 s, with a slow zoom and pan (the "Ken Burns" effect of
 *    the Apple TV photos) and a crossfade between them; each photo is loaded 10% larger than the screen, so the zoom
 *    never upscales it;
 *  - top right, like the iPhone lock screen: the date with the weather, the big Liquid Glass clock (the photo shows
 *    through the digits, bent at the edges; Shaders.GLASS_TEXT);
 *  - bottom left, the photo's caption: the time-of-day label ("TONIGHT"), the title, and genre · runtime · service.
 * Baked photo gradients and glyph shadows keep overlays readable. Any key exits (as on Apple TV).
 * Without photos (no TMDB key, or before the first fetch): the big clock over the night aurora.
 */
final class DreamView extends View implements Choreographer.FrameCallback, HomeData.Listener {
    private static final long SHOW_MS = 30_000, FADE_MS = 3_000, FIRST_MS = 1_600;
    private static final float ZOOM = 0.1f;
    /** Photos 10% larger than the screen (the zoom goes up to 1.1 without upscaling). */
    private static final int PW = 2112, PH = 1188;
    private static final float MARGIN = 80, RIGHT = 1840;
    private static final float DATE_BASE = 92, CLOCK_TOP = 120, CLOCK_SIZE = 200;
    private static final double DRIFT_PERIOD_S = 600;

    /** One photo on screen: the image, its motion (zooming in or out, toward a corner) and its caption. */
    private static final class Slide {
        final Recs.Pick pick;
        final Bitmap image, frost;
        final BitmapShader shader;    // the frosted version, for the clock's glass
        final float lightCaption, lightClock;
        final boolean zoomIn;
        final float panX, panY;
        long startedAt;

        Slide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock, int n) {
            this.pick = pick;
            this.image = image;
            this.frost = frost;
            shader = new BitmapShader(frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Frost.filter(shader, BitmapShader.FILTER_MODE_LINEAR);
            this.lightCaption = lightCaption;
            this.lightClock = lightClock;
            zoomIn = n % 2 == 0;
            // Toward one of the corners, alternating, without repeating the previous one.
            panX = (n / 2) % 2 == 0 ? 0.8f : -0.8f;
            panY = n % 3 == 0 ? -0.6f : 0.6f;
        }
    }

    /** The glass clock's digits, rendered once a minute in the background. */
    private static final class Glass {
        final String text;
        final Bitmap slope, cover, shadow;
        final int x, y;
        final float inkTop, inkHeight;

        Glass(String text, Bitmap slope, Bitmap cover, Bitmap shadow, int x, int y, float inkTop, float inkHeight) {
            this.text = text;
            this.slope = slope;
            this.cover = cover;
            this.shadow = shadow;
            this.x = x;
            this.y = y;
            this.inkTop = inkTop;
            this.inkHeight = inkHeight;
        }
    }

    private final Scene scene = new Scene(false);
    // Offline fallback: four soft, slowly drifting gradient spheres.
    private final Paint[] orbPaint = new Paint[4];
    private final Paint fallbackBase = new Paint();
    private final Paint fallbackClock = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] orbX = {0.16f, 0.79f, 0.47f, 0.87f};
    private final float[] orbY = {0.70f, 0.22f, 0.49f, 0.85f};
    private final float[] orbRadius = {0.48f, 0.43f, 0.38f, 0.31f};
    private final int[] orbColor = {0x603E65B6, 0x585B3A95, 0x40358D9D, 0x443C4D96};
    private final HomeData data;
    private final GalleryPhotos gallery;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Recording content = Recording.create("clock");     // without photos: the clock over the aurora
    private final Recording chrome = Recording.create("corner");     // date, weather and the clock's shadow
    private final float timeOffset = (float) (Math.random() * 300);
    private final Matrix matrix = new Matrix();
    private final float[] placeA = new float[3], placeB = new float[3];
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint black = new Paint();
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glassPaint = new Paint();
    private RuntimeShader glassShader;
    private boolean running, dirty = true, chromeDirty = true;
    private boolean fallback;       // the aurora with the clock (no photos)
    private boolean decoding;
    private int generation, slides;
    private Slide current, incoming, prepared;
    private Glass glass;
    private String glassFor = "";
    private long startedAt, firstAt, lastFrame, lastSky;
    private int w, h;
    private float k = 1;
    private long minute = -1;
    private String clock = "", date = "";
    private Paint datePaint, clockPaint, weatherPaint, weatherDim;
    private Paint cornerDate, cornerClock;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            gallery.refresh(() -> { if (running) { prepareNext(); invalidate(); } });
            dirty = true;
            chromeDirty = true;
            prepareNext();
            handler.postDelayed(this, 60_000);
        }
    };

    private final ScreensaverWeather weatherBadge;
    private final android.os.Bundle settings;
    private Runnable contentReady;
    private boolean parentPhotoEntrance;

    void setOnContentReadyListener(Runnable listener) { contentReady = listener; }

    private void notifyContentReady() {
        if (!running || contentReady == null) return;
        Runnable ready = contentReady;
        contentReady = null;
        post(() -> { if (running) ready.run(); });
    }

    DreamView(Context c) {
        this(c, ScreensaverSettingsProvider.read(c));
    }

    DreamView(Context c, android.os.Bundle settings) {
        super(c);
        this.settings = settings;
        weatherBadge = new ScreensaverWeather(c);
        data = HomeData.get(c);
        String source = settings.getString("video_dream_source", "google");
        gallery = new GalleryPhotos(c, ("na".equals(source) || "nasa".equals(source)) ? "na" : "google");
        Shaders.dream(scene.palette);
        scene.setMotion(0.4f, 0.32f);
        scene.applyPalette();
        content.setUseCompositingLayer(true, null);
        try {
            glassShader = new RuntimeShader(Shaders.GLASS_TEXT);
            glassPaint.setShader(glassShader);
        } catch (RuntimeException | LinkageError e) {   // Android 9 to 12: no AGSL
            AppLog.w("glass clock unavailable: " + e.getMessage());
            glassShader = null;
        }
        black.setColor(0xFF000000);
        shadowPaint.setColor(0xFF000000);
    }

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        startedAt = clock();
        lastFrame = 0;
        fallback = !gallery.hasImages();
        gallery.refresh(() -> { if (running) { prepareNext(); invalidate(); } });
        data.addListener(this);
        data.refreshIfStale();

        prepareNext();
        handler.postDelayed(tick, 60_000);
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        contentReady = null;
        generation++;
        decoding = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        // The photos stay in GPU memory until the garbage collector runs: release them now (after the last frame).
        final java.util.Set<Bitmap> gone = new java.util.HashSet<>();
        for (Slide s : new Slide[] {current, incoming, prepared}) {
            if (s == null) continue;
            gone.add(s.image);
            gone.add(s.frost);
        }
        if (!gone.isEmpty()) handler.postDelayed(() -> { for (Bitmap b : gone) b.recycle(); }, 1000);
        current = incoming = prepared = null;
        glass = null;
        slides = 0;
        glassFor = "";
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
        gallery.close();
    }

    @Override public void onData() {
        dirty = true;
        chromeDirty = true;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        scene.setSize(w, h);
        for (int i = 0; i < orbPaint.length; i++) {
            float r = Math.max(w, h) * orbRadius[i];
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            p.setShader(new RadialGradient(0, 0, r,
                    new int[] {orbColor[i], orbColor[i] & 0x00FFFFFF, 0x00000000},
                    new float[] {0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
            orbPaint[i] = p;
        }
        fallbackBase.setColor(0xFF080D1D);
        content.setPosition(0, 0, w, h);
        chrome.setPosition(0, 0, w, h);
        // Without photos (the clock over the aurora).
        datePaint = Ui.text(Ui.semibold, 36 * k, 0xD9FFFFFF);
        datePaint.setTextAlign(Paint.Align.CENTER);
        clockPaint = Ui.numbers(Ui.thin, 280 * k, 0xF2FFFFFF);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        clockPaint.setLetterSpacing(-0.01f);
        weatherPaint = Ui.numbers(Ui.medium, 30 * k, 0xC7FFFFFF);
        weatherDim = Ui.numbers(Ui.regular, 28 * k, 0x80FFFFFF);
        // With photos: the corner date and clock.
        cornerDate = Ui.numbers(Ui.semibold, 30 * k, 0xEBFFFFFF);
        cornerClock = Ui.numbers(Ui.displaySemibold, CLOCK_SIZE * k, 0xF2FFFFFF);
        cornerClock.setShader(new LinearGradient(0, CLOCK_TOP * k, 0, (CLOCK_TOP + CLOCK_SIZE) * k, new int[]{0xFFFDF6EC, 0xFFE7E4E0, 0xFF999B9F, 0xFFF0D1B7}, null, Shader.TileMode.CLAMP));
        for (Paint p : new Paint[] {cornerDate, cornerClock}) {
            p.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        }
        glassFor = "";
        dirty = true;
        chromeDirty = true;
    }

    // MARK: - Photos

    /** Loads the next photo in the background (one at a time); once ready, it comes in at the right moment. */
    private void prepareNext() {
        if (!running || decoding || prepared != null) return;
        File file = gallery.next();
        if (file == null) return;
        Recs.Pick pick = new Recs.Pick();
        pick.kind = "gallery";
        pick.id = file.getName().hashCode();
        pick.title = "";
        if (current != null && current.pick.id == pick.id && gallery.hasImages()) {
            prepared = newSlide(pick, current.image, current.frost, current.lightCaption, current.lightClock);
            gallery.release(file);
            return;
        }
        decoding = true;
        final int gen = generation;
        final Recs.Pick chosen = pick;
        worker.execute(() -> {
            Bitmap[] images = null;
            float[] light = new float[2];
            try {
                images = Backdrop.photo(file, PW, PH, light);
                images[0] = Backdrop.gpu(withScrims(images[0], light[0]));
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("screensaver: photo for “" + chosen.title + "”: " + e.getMessage());
            } finally {
                gallery.release(file);
            }
            final Bitmap[] ready = images;
            handler.post(() -> {
                if (gen != generation) {
                    // The screensaver closed while the photo was loading: free the memory right away.
                    if (ready != null) for (Bitmap b : ready) b.recycle();
                    return;
                }
                decoding = false;
                if (ready == null) {
                    AppLog.w("gallery image failed: " + file.getName());
                    handler.postDelayed(this::prepareNext, 1500);
                    return;
                }
                prepared = newSlide(chosen, ready[0], ready[1], light[0], light[1]);
                invalidate();
            });
        });
    }

    private Slide newSlide(Recs.Pick pick, Bitmap image, Bitmap frost, float lightCaption, float lightClock) {
        Slide s = new Slide(pick, image, frost, lightCaption, lightClock, slides++);
        return s;
    }

    /** The prepared photo goes on screen when its turn comes (the first one right away; the others every 30 s). */
    private void advance(long up) {
        if (current == null) {
            if (prepared == null) return;
            current = prepared;
            prepared = null;
            current.startedAt = up;
            firstAt = up;
            bindPhotos();
            prepareNext();
            return;
        }
        if (incoming == null && prepared != null && up >= current.startedAt + SHOW_MS) {
            incoming = prepared;
            prepared = null;
            incoming.startedAt = up;
            bindPhotos();
            return;
        }
        if (incoming != null && up >= incoming.startedAt + FADE_MS) {
            Slide old = current;
            current = incoming;
            incoming = null;
            bindPhotos();
            if (old.image != current.image) {
                // Once the last frame that uses them has been drawn.
                Bitmap gone = old.image, goneFrost = old.frost;
                handler.postDelayed(() -> {
                    gone.recycle();
                    goneFrost.recycle();
                }, 1000);
            }
            prepareNext();
        }
    }

    /** The photos the clock's glass shows (the current one and, during a crossfade, the incoming one). */
    private void bindPhotos() {
        if (glassShader == null || current == null) return;
        glassShader.setInputShader("imageA", current.shader);
        glassShader.setInputShader("imageB", incoming != null ? incoming.shader : current.shader);
    }

    /** Photo position and scale now: zoom from 1 to 1.1 (or back) and a pan toward a corner, never showing an edge. */
    private void place(Slide s, long up, float[] out) {
        float u = Ui.clamp((up - s.startedAt) / (float) (SHOW_MS + FADE_MS));
        float f = s.zoomIn ? u : 1 - u;
        float z = 1 + ZOOM * f;
        float rx = w * (z - 1) / 2, ry = h * (z - 1) / 2;
        out[0] = rx * (s.panX * f - 1);
        out[1] = ry * (s.panY * f - 1);
        out[2] = (float) w / s.image.getWidth() * z;
    }

    private void drawPhoto(Canvas c, Slide s, float[] place, float alpha) {
        if (alpha <= 0.004f) return;
        matrix.setScale(place[2], place[2]);
        matrix.postTranslate(place[0], place[1]);
        photoPaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(s.image, matrix, photoPaint);
    }

    // MARK: - Frames: 20 per second, locked to every 3rd display frame (60 Hz)
    // Motion is slow (under half a pixel per frame). Measured on the TV: ~4 ms of GPU per frame of ours, but drawing
    // every frame, it only reaches ~50 fps (the system also composites the UI on the GPU); at 20 there's room to spare.

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        if (frameTimeNanos - lastFrame >= 45_000_000L) {
            lastFrame = frameTimeNanos;
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** The clock that drives the animations. */
    private long clock() {
        long up = SystemClock.uptimeMillis();
        return up;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long up = clock();
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            clock = Ui.clock(now);
            date = Ui.date(now);
            dirty = true;
            chromeDirty = true;
        }
        if (running) advance(up);
        if (!glassFor.equals(clock)) buildGlass(clock);

        // The parent owns initial fade for every source. Keep later photo crossfades unchanged.
        if (current != null && contentReady != null) parentPhotoEntrance = true;
        float first = current == null ? 0 : parentPhotoEntrance ? 1 : fadeIn(up - firstAt, FIRST_MS);
        if (current == null || first < 1) {
            if (fallback) drawFallback(canvas, up, current == null ? 1 : 1 - first);
            else canvas.drawRect(0, 0, w, h, black);
        }
        if (current == null) {
            if (fallback) notifyContentReady();
            return;
        }
        if (fallback && first >= 1) fallback = false;

        place(current, up, placeA);
        float e = incoming == null ? 0 : fadeIn(up - incoming.startedAt, FADE_MS);
        drawPhoto(canvas, current, placeA, first);
        if (incoming != null) {
            place(incoming, up, placeB);
            drawPhoto(canvas, incoming, placeB, e);
        }

        if (chromeDirty) recordChrome();
        chrome.setAlpha(first);
        chrome.draw(canvas);
        drawGlass(canvas, first, e);
        notifyContentReady();
        // Captions hidden: keep photo, glass clock and weather
    }

    private static float fadeIn(long elapsed, long duration) {
        float t = Ui.clamp(elapsed / (float) duration);
        return t * t * (3 - 2 * t);
    }

    /**
     * Existing bottom and full-width top gradients, baked once in the background.
     * Clock/weather glyphs have their own shadows; no local upper-right clock scrim.
     * Each frame still draws only the prepared photo, without another full-screen layer.
     */
    private static Bitmap withScrims(Bitmap photo, float lightCaption) {
        int w = photo.getWidth(), h = photo.getHeight();
        Canvas c = new Canvas(photo);
        Paint p = new Paint(Paint.DITHER_FLAG);
        float caption = scrimStrength(lightCaption, 0.5f);
        p.setShader(new LinearGradient(0, h, 0, h * 0.42f,
                new int[] {black(0.55f * caption), black(0.33f * caption), black(0.11f * caption), 0},
                new float[] {0, 0.35f, 0.7f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.42f, w, h, p);
        RadialGradient around = new RadialGradient(0, h, w * 0.7f,
                new int[] {black(caption), black(0.55f * caption), black(0.15f * caption), 0},
                new float[] {0, 0.4f, 0.72f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1, 0.62f, 0, h);
        around.setLocalMatrix(m);
        p.setShader(around);
        c.drawRect(0, h - w * 0.7f * 0.62f, w * 0.7f, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.3f, new int[] {black(0.3f), black(0.075f), 0},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.3f, p);
        // The clock is centered now; the former upper-right oval would darken empty photo space.
        return photo;
    }

    private static int black(float a) {
        return Math.round(Math.max(0, Math.min(1, a)) * 255) << 24;
    }

    private static float scrimStrength(float light, float base) {
        return Math.max(base - 0.1f, Math.min(0.78f, base + (light - 0.3f) * 0.9f));
    }

    // MARK: - Corner: date and weather, glass clock

    private boolean showScreensaverClock() {
        return settings.getBoolean("aerial_show_clock", false);
    }

    private void recordChrome() {
        Canvas c = chrome.beginRecording(w, h);
        try {
            float right = (w + cornerClock.measureText(clock)) / 2f;
            float x = RIGHT * k;
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && settings.getBoolean("screensaver_show_weather", false)) {
                weatherBadge.setWeather(Symbols.weather(wx.code, wx.isDay), HomeData.degrees(wx.temp));
                weatherBadge.drawRightAligned(c, x, DATE_BASE * k);
            }
            // Date intentionally hidden on all screensavers.

            Glass g = glass;
            if (showScreensaverClock() && g != null && glassShader != null) {
                // The shadow under the glass: sets the digits apart from a bright photo.
                shadowPaint.setAlpha(140);
                c.drawBitmap(g.shadow, g.x, g.y + 6 * k, shadowPaint);
            } else if (showScreensaverClock()) {
                Rect ink = new Rect();
                cornerClock.getTextBounds(clock, 0, clock.length(), ink);
                c.drawText(clock, right - ink.right, CLOCK_TOP * k - ink.top, cornerClock);
            }

        } finally {
            chrome.endRecording();
        }
        chromeDirty = false;
    }

    private void drawGlass(Canvas c, float alpha, float e) {
        Glass g = glass;
        if (!showScreensaverClock() || g == null || glassShader == null || alpha <= 0.004f) return;
        // The glass samples the frosted version (1/6 of the photo): same position, with the scale 6 times larger.
        float f = Backdrop.PHOTO_FROST;
        glassShader.setFloatUniform("placeA", placeA[0], placeA[1], placeA[2] * f);
        if (incoming != null) glassShader.setFloatUniform("placeB", placeB[0], placeB[1], placeB[2] * f);
        else glassShader.setFloatUniform("placeB", placeA[0], placeA[1], placeA[2] * f);
        glassShader.setFloatUniform("mixB", incoming == null ? 0 : e);
        glassShader.setFloatUniform("origin", g.x, g.y);
        glassShader.setFloatUniform("ink", g.inkTop, g.inkHeight);
        glassShader.setFloatUniform("refraction", 14 * k);
        glassShader.setFloatUniform("specular", 1);
        glassShader.setFloatUniform("alpha", alpha);
        c.drawRect(g.x, g.y, g.x + g.cover.getWidth(), g.y + g.cover.getHeight(), glassPaint);
    }

    /** Builds the glass clock's digits in the background (once a minute) and swaps them in when they're ready. */
    private void buildGlass(String text) {
        glassFor = text;
        if (glassShader == null || text.isEmpty()) {
            chromeDirty = true;
            return;
        }
        final Paint paint = new Paint(cornerClock);
        paint.clearShadowLayer();
        paint.setShader(null);
        paint.setColor(0xFFFFFFFF);
        final float right = (w + paint.measureText(text)) / 2f, top = CLOCK_TOP * k, scale = k;
        final int gen = generation;
        worker.execute(() -> {
            Glass g = makeGlass(text, paint, right, top, scale);
            handler.post(() -> {
                if (gen != generation || !text.equals(glassFor)) return;
                glass = g;
                glassShader.setInputShader("slope", nearest(g.slope));
                glassShader.setInputShader("cover", nearest(g.cover));
                chromeDirty = true;
                invalidate();
            });
        });
    }

    private static BitmapShader nearest(Bitmap b) {
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Frost.filter(s, BitmapShader.FILTER_MODE_NEAREST);
        return s;
    }

    /**
     * The sharp rendering of the digits, the surface height (the blurred rendering: 0.5 at the edge, 1 in the middle of
     * the strokes), its slope (for the lens and the highlight) and the shadow.
     */
    private static Glass makeGlass(String text, Paint paint, float right, float top, float k) {
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        int pad = Math.round(40 * k);
        int w = ink.width() + 2 * pad, h = ink.height() + 2 * pad;
        Bitmap cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(cover).drawText(text, pad - ink.left, pad - ink.top, paint);
        int[] px = new int[w * h];
        cover.getPixels(px, 0, w, 0, 0, w, h);
        int[] alpha = new int[px.length];
        for (int i = 0; i < px.length; i++) alpha[i] = (px[i] >>> 24) * 16;
        int[] height = blur(alpha, w, h, Math.max(2, Math.round(6 * k)), 3);
        int[] soft = blur(alpha, w, h, Math.max(3, Math.round(12 * k)), 3);
        float max = 1;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float gx = height[i + 1] - height[i - 1], gy = height[i + w] - height[i - w];
                max = Math.max(max, (float) Math.sqrt(gx * gx + gy * gy));
            }
        }
        int[] slope = new int[px.length], shadow = new int[px.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float gx = x > 0 && x < w - 1 ? height[i + 1] - height[i - 1] : 0;
                float gy = y > 0 && y < h - 1 ? height[i + w] - height[i - w] : 0;
                int r = 128 + Math.round(127 * gx / max), g = 128 + Math.round(127 * gy / max);
                int b = Math.min(255, height[i] / 16);
                slope[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                shadow[i] = Math.min(255, soft[i] / 16) << 24;
            }
        }
        int x = Math.round(right - ink.width()) - pad, y = Math.round(top) - pad;
        return new Glass(text, Bitmap.createBitmap(slope, w, h, Bitmap.Config.ARGB_8888), cover,
                Bitmap.createBitmap(shadow, w, h, Bitmap.Config.ARGB_8888), x, y, pad, Math.max(1, ink.height()));
    }

    /** Box blur (passes of radius r in both directions); anything outside the image counts as empty. */
    private static int[] blur(int[] src, int w, int h, int r, int passes) {
        int[] a = src.clone(), b = new int[src.length];
        for (int pass = 0; pass < passes; pass++) {
            box(a, b, w, h, r, true);
            box(b, a, w, h, r, false);
        }
        return a;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int n = horizontal ? w : h, lines = horizontal ? h : w, step = horizontal ? 1 : w, lineStep = horizontal ? w : 1;
        int div = 2 * r + 1;
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep, sum = 0;
            for (int i = 0; i <= r && i < n; i++) sum += in[base + i * step];
            for (int i = 0; i < n; i++) {
                out[base + i * step] = (sum + div / 2) / div;
                int add = i + r + 1, sub = i - r;
                if (add < n) sum += in[base + add * step];
                if (sub >= 0) sum -= in[base + sub * step];
            }
        }
    }

    // MARK: - Offline fallback: drifting soft spheres, photo-style clock and corner weather.

    private void drawFallback(Canvas canvas, long up, float alpha) {
        float t = (up - startedAt) / 1000f;
        int save = canvas.saveLayerAlpha(0, 0, w, h, Math.round(255 * Ui.clamp(alpha)));
        canvas.drawColor(0xFF080D1D);
        for (int i = 0; i < orbPaint.length; i++) {
            float phase = t * (0.012f + i * 0.002f) + i * 1.9f + timeOffset;
            float x = w * orbX[i] + w * 0.075f * (float) Math.sin(phase);
            float y = h * orbY[i] + h * 0.10f * (float) Math.cos(phase * 0.77f);
            int layer = canvas.save();
            canvas.translate(x, y);
            float radius = Math.max(w, h) * orbRadius[i];
            canvas.drawCircle(0, 0, radius, orbPaint[i]);
            canvas.restoreToCount(layer);
        }

        if (showScreensaverClock()) {
            // The same clock typeface, size, gradient and placement as the photo screensaver.
            // Without a source photograph the refraction shader cannot sample a photo;
            // use the photo clock's gradient and subtle layered highlight instead.
            float center = w / 2f;
            Rect ink = new Rect();
            cornerClock.getTextBounds(clock, 0, clock.length(), ink);
            float baseline = CLOCK_TOP * k - ink.top;
            fallbackShadow.set(cornerClock);
            fallbackShadow.setShader(null);
            fallbackShadow.setColor(0x99000000);
            canvas.drawText(clock, center - cornerClock.measureText(clock) / 2f + 3 * k,
                    baseline + 7 * k, fallbackShadow);
            canvas.drawText(clock, center - cornerClock.measureText(clock) / 2f,
                    baseline, cornerClock);

        }

        // Identical upper-right weather rendering and shared visibility preference.
        HomeData.Weather wx = data.weather;
        if (!Double.isNaN(wx.temp) && settings.getBoolean("screensaver_show_weather", false)) {
            weatherBadge.setWeather(Symbols.weather(wx.code, wx.isDay), HomeData.degrees(wx.temp));
            weatherBadge.drawRightAligned(canvas, RIGHT * k, DATE_BASE * k);
        }
        canvas.restoreToCount(save);
    }

}
