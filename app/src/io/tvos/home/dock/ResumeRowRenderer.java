package io.tvos.home.dock;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;

/** Resume-row drawing only; providers, navigation, image loading and preferences stay with the host. */
public final class ResumeRowRenderer {
    public interface Shadow { void draw(Canvas canvas, RectF bounds, float radius, float blur, Paint paint); }
    public static final class Frame {
        public float scale, bottom, night, opacity;
        public int ink;
        public boolean empty;
        public Typeface titleFont, metaFont, headingFont;
        public String heading, emptyMessage;
    }
    private final Shadow shadows;
    private float k, night, width, height, top;
    private int layer;
    private final Paint headingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint();
    private final TextPaint metaPaint = new TextPaint();
    private final RectF rect = new RectF(), imageRect = new RectF();
    private final Path clip = new Path();
    private final RectF shadowRect = new RectF();
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient focusSheen, focusRim, bottomShade, logoShade;
    private float logoShadeWidth = -1, logoShadeHeight = -1;
    private final Paint logoShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float shadeHeight = -1;
    private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF progressRect = new RectF();
    private float shadowScale = -1, focusWidth = -1, focusHeight = -1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public ResumeRowRenderer(Shadow shadows) { this.shadows = shadows; titlePaint.setAntiAlias(true); metaPaint.setAntiAlias(true); }

    /** False means the empty state was drawn and the canvas is already restored. */
    public boolean begin(Canvas c, Frame frame) {
        k = frame.scale; night = frame.night;
        float bottom = frame.bottom, opacity = frame.opacity;
        layer = opacity < .999f ? c.saveLayerAlpha(0, 0, 1920 * k, bottom, Math.round(255 * opacity)) : c.save();
        titlePaint.setTypeface(frame.titleFont); titlePaint.setTextSize(30 * k); titlePaint.setColor(frame.ink);
        metaPaint.setTypeface(frame.metaFont); metaPaint.setTextSize(24 * k); metaPaint.setColor(frame.ink);
        Paint meta = metaPaint;
        headingPaint.setTypeface(frame.headingFont); headingPaint.setTextSize(44 * k); headingPaint.setColor(alpha(frame.ink, .55f));
        float headingBaseline = Math.max(150 * k, bottom - 600 * k);
        c.drawText(frame.heading, 60 * k, headingBaseline - 10 * k, headingPaint);
        if (frame.empty) { c.drawText(frame.emptyMessage, 60 * k, headingBaseline + 65 * k, meta); c.restoreToCount(layer); return false; }
        if (shadowScale != k) {
            shadowScale = k;
            shadow.setMaskFilter(new BlurMaskFilter(30 * k, BlurMaskFilter.Blur.NORMAL));
        }
        width = 850 * k; height = width * 9 / 16;
        top = headingBaseline + 25 * k;
        c.save(); c.clipRect(0, headingBaseline + 5 * k, 1920 * k, bottom);
        return true;
    }
    public boolean visible(float position) {
        float left = 85 * k + position * (width + 50 * k);
        return left < 1920 * k && left + width > 0;
    }
    public void card(Canvas c, float position, float focusValue, String label, String caption, Bitmap image, int imageAlpha) {
        card(c, position, focusValue, label, caption, image, imageAlpha, -1);
    }
    public void card(Canvas c, float position, float focusValue, String label, String caption, Bitmap image, int imageAlpha, float progress) {
        card(c, position, focusValue, label, caption, image, imageAlpha, progress, null);
    }
    public void card(Canvas c, float position, float focusValue, String label, String caption,
            Bitmap image, int imageAlpha, float progress, Bitmap logo) {
        card(c, position, focusValue, label, caption, image, imageAlpha, progress, logo, imageAlpha);
    }
    public void card(Canvas c, float position, float focusValue, String label, String caption,
            Bitmap image, int imageAlpha, float progress, Bitmap logo, int logoAlpha) {
        Paint title = titlePaint, meta = metaPaint;
        float left = 85 * k + position * (width + 50 * k);
        if (left >= 1920 * k) return;
        if (left + width <= 0) return;
        float scaleFocus = 1 + .05f * focusValue;
        float cx = left + width / 2, cy = top + height / 2 - 6 * k * focusValue;
        rect.set(cx - width * scaleFocus / 2, cy - height * scaleFocus / 2, cx + width * scaleFocus / 2, cy + height * scaleFocus / 2);
        float focus = clamp(focusValue);
        c.save(); c.translate(cx, cy); c.scale(scaleFocus, scaleFocus);
        shadowRect.set(-width / 2, -height / 2, width / 2, height / 2);
        shadowRect.offset(0, (10 + 14 * focus) * k);
        float restAlpha = .14f + (.32f - .14f) * night;
        float focusAlpha = .2f + (.28f - .2f) * night;
        shadow.setColor(alpha(0xFF000000, restAlpha + focusAlpha * focus));
        shadows.draw(c, shadowRect, 28 * k, 30 * k, shadow);
        c.restore();
        paint.setAlpha(255);
        paint.setColor(0xFF343A3D); paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(rect, 28 * k, 28 * k, paint);
        if (image != null) {
            paint.setAlpha(imageAlpha);
            clip.reset(); clip.addRoundRect(rect, 28 * k, 28 * k, Path.Direction.CW);
            c.save(); c.clipPath(clip);
            // A portrait fallback stays intact rather than becoming an enlarged, blurry crop.
            float scale = image.getWidth() < image.getHeight()
                    ? Math.min(rect.width() / image.getWidth(), rect.height() / image.getHeight())
                    : Math.max(rect.width() / image.getWidth(), rect.height() / image.getHeight());
            float w = image.getWidth() * scale, h = image.getHeight() * scale;
            imageRect.set(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2);
            c.drawBitmap(image, null, imageRect, paint); c.restore(); paint.setAlpha(255);
        }
        // Draw in card coordinates so the caption and progress follow the focus animation.
        c.save(); c.translate(cx, cy); c.scale(scaleFocus, scaleFocus);
        shadowRect.set(-width / 2, -height / 2, width / 2, height / 2);
        clip.reset(); clip.addRoundRect(shadowRect, 28 * k, 28 * k, Path.Direction.CW);
        c.clipPath(clip);
        if (logo != null && image != null && image.getWidth() > image.getHeight()) {
            float logoScale = Math.min(288 * k / logo.getWidth(), 108.8f * k / logo.getHeight());
            if (logoShade == null || logoShadeWidth != width || logoShadeHeight != height) {
                logoShadeWidth = width; logoShadeHeight = height;
                logoShade = new LinearGradient(-width / 2, -height / 2,
                        -width / 2 + 430 * k, -height / 2 + 250 * k,
                        new int[]{0xA6000000, 0x55000000, 0x00000000},
                        new float[]{0, .4f, 1}, Shader.TileMode.CLAMP);
            }
            logoShadePaint.setShader(logoShade);
            logoShadePaint.setAlpha(logoAlpha);
            c.drawRect(shadowRect, logoShadePaint);
            float lx = -width / 2 + 32 * k, ly = -height / 2 + 32 * k;
            imageRect.set(lx, ly, lx + logo.getWidth() * logoScale, ly + logo.getHeight() * logoScale);
            paint.setAlpha(logoAlpha);
            c.drawBitmap(logo, null, imageRect, paint);
            paint.setAlpha(255);
        }
        if (!caption.isEmpty() || progress >= 0) {
            if (bottomShade == null || shadeHeight != height) {
                shadeHeight = height;
                bottomShade = new LinearGradient(0, -height * .1f, 0, height / 2,
                        0x00000000, 0xD9000000, Shader.TileMode.CLAMP);
            }
            overlay.setShader(bottomShade);
            c.drawRect(shadowRect, overlay);
            meta.setColor(0xFFFFFFFF);
            String detail = android.text.TextUtils.ellipsize(caption, metaPaint, width - 64 * k,
                    android.text.TextUtils.TruncateAt.END).toString();
            c.drawText(detail, -width / 2 + 32 * k, height / 2 - (progress >= 0 ? 53 : 30) * k, meta);
            if (progress >= 0) {
                float x = -width / 2 + 32 * k, y = height / 2 - 25 * k;
                progressRect.set(x, y, width / 2 - 32 * k, y + 7 * k);
                progressPaint.setColor(0x70FFFFFF);
                c.drawRoundRect(progressRect, 3.5f * k, 3.5f * k, progressPaint);
                progressRect.right = x + (width - 64 * k) * clamp(progress);
                progressPaint.setColor(0xFFFFFFFF);
                c.drawRoundRect(progressRect, 3.5f * k, 3.5f * k, progressPaint);
            }
        }
        c.restore();
        if (focus > .01f) {
            if (focusSheen == null || focusWidth != width || focusHeight != height) {
                focusWidth = width; focusHeight = height;
                focusSheen = new LinearGradient(-width / 2, -height / 2, 0, height / 2,
                        0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                focusRim = new LinearGradient(0, -height / 2, 0, height / 2,
                        0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
            }
            c.save(); c.translate(cx, cy); c.scale(scaleFocus, scaleFocus);
            shadowRect.set(-width / 2, -height / 2, width / 2, height / 2);
            sheen.setShader(focusSheen); sheen.setAlpha(Math.round(255 * focus));
            c.drawRoundRect(shadowRect, 28 * k, 28 * k, sheen);
            rim.setStyle(Paint.Style.STROKE); rim.setShader(focusRim);
            rim.setAlpha(Math.round(255 * focus)); rim.setStrokeWidth(2.5f * k / scaleFocus);
            c.drawRoundRect(shadowRect, 28 * k, 28 * k, rim);
            c.restore();
        }
        String shown = android.text.TextUtils.ellipsize(label, titlePaint, width, android.text.TextUtils.TruncateAt.END).toString();
        title.setAlpha(Math.round(255 * (.65f + .35f * focus)));
        c.drawText(shown, cx - title.measureText(shown) / 2, top + height + 37 * k, title);
    }
    public void end(Canvas canvas) { canvas.restore(); canvas.restoreToCount(layer); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static int alpha(int color, float value) { return (Math.round((color >>> 24) * clamp(value)) << 24) | (color & 0xFFFFFF); }
}
