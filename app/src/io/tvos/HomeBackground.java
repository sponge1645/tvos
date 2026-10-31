package io.tvos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;

/** Background images, glass pixels, crossfade and day/night transitions. No view or focus ownership. */
final class HomeBackground {
    private static final long FADE_MS = 400, PAN_MS = 400, NIGHT_MS = 2000;
    private final Scene scene;
    private final Runnable changed;
    private boolean nightTarget, nightPair;
    private float night, nightFrom;
    private long nightAt, bgFadeAt;
    private Backdrop bgFrom, bgTo;
    private int panDirection;
    private float fromPan;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];
    private Shader nightGradient, lightGradient;
    private final RectF screen = new RectF(), imageBounds = new RectF();
    private int h;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;

    HomeBackground(Scene scene, Runnable changed) { this.scene = scene; this.changed = changed; }
    float night() { return night; }
    void resize(int width, int height) { h = height; screen.set(0, 0, width, height); lightGradient = nightGradient = null; }
    private static int blend(int a, int b, float f) { return ActionSurface.blend(a, b, f); }
    private float tone(float a, float b) { return a + (b - a) * night; }
    private static float fadeEase(long start, long up) {
        float t = Ui.clamp((up - start) / (float) FADE_MS);
        return t * t * (3 - 2 * t);
    }

    private static float panRemaining(long start, long up) {
        float remaining = 1 - Ui.clamp((up - start) / (float) PAN_MS);
        return remaining * remaining * remaining;
    }

    void show(Backdrop b, boolean animate, int direction) {
        if (b == bgTo) return; // Caption enrichment must not restart the image transition.
        show(b, animate);
        panDirection = animate ? Integer.signum(direction) : 0;
    }

    void show(Backdrop b, boolean animate) {
        show(b);
        if (!animate) { bgFrom = null; bgFadeAt = 0; nightChanged(); }
    }

    void show(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();

        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) {
            bgFrom = bgTo;
            float remaining = bgFadeAt == 0 ? 0 : panRemaining(bgFadeAt, up);
            fromPan = panDirection * remaining;
        }
        panDirection = 0;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    void checkNight(boolean want, boolean animate) {
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / (float) NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        changed.run();
    }

    boolean tickFade(long up, boolean needsFrost) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (needsFrost) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    void draw(Canvas c, float drawer) {
        long up = SystemClock.uptimeMillis();

        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1, fromPan);
        float remaining = fading && panDirection != 0 ? panRemaining(bgFadeAt, up) : 0;
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1, panDirection * remaining);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha, float pan) {
        if (b.image != null && b.lightImage == null) {
            if (alpha <= .004f) return;
            float cover = Math.max(screen.width() / b.image.getWidth(), screen.height() / b.image.getHeight());
            float width = b.image.getWidth() * cover, height = b.image.getHeight() * cover;
            float drift = screen.width() * (32f / 1920);
            // Keep a fixed crop throughout the transition and at rest; never animate scale.
            boolean panoramic = (width - screen.width()) / 2 >= drift;
            float scale = panoramic ? 1 : 1.04f;
            width *= scale; height *= scale;
            float margin = Math.max(0, (width - screen.width()) / 2);
            float dx = (panoramic ? margin * .5f : drift) * pan;
            imageBounds.set(screen.centerX() - width / 2 + dx, screen.centerY() - height / 2,
                    screen.centerX() + width / 2 + dx, screen.centerY() + height / 2);
            imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
            c.drawBitmap(b.image, null, imageBounds, imagePaint);
            return;
        }
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    int[] profilePixels() {
        if (bgTo == null) return null;
        if (night >= 0.5f && bgTo.nightFrost != null) return bgTo.nightFrost;
        return bgTo.light != null ? bgTo.light : frostOf(bgTo);
    }
}
