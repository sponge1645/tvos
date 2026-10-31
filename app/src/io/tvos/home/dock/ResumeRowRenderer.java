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
    private final Paint metaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF(), imageRect = new RectF();
    private final Path clip = new Path();
    private final RectF shadowRect = new RectF();
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient focusSheen, focusRim;
    private float shadowScale = -1, focusWidth = -1, focusHeight = -1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public ResumeRowRenderer(Shadow shadows) { this.shadows = shadows; titlePaint.setAntiAlias(true); }

    /** False means the empty state was drawn and the canvas is already restored. */
    public boolean begin(Canvas c, Frame frame) {
        k = frame.scale; night = frame.night;
        float bottom = frame.bottom, opacity = frame.opacity;
        layer = opacity < .999f ? c.saveLayerAlpha(0, 0, 1920 * k, bottom, Math.round(255 * opacity)) : c.save();
        titlePaint.setTypeface(frame.titleFont); titlePaint.setTextSize(36 * k); titlePaint.setColor(frame.ink);
        metaPaint.setTypeface(frame.metaFont); metaPaint.setTextSize(26 * k); metaPaint.setColor(frame.ink);
        Paint meta = metaPaint;
        headingPaint.setTypeface(frame.headingFont); headingPaint.setTextSize(54 * k); headingPaint.setColor(frame.ink);
        float headingBaseline = Math.max(170 * k, bottom - 550 * k);
        c.drawText(frame.heading, 60 * k, headingBaseline - 20 * k, headingPaint);
        if (frame.empty) { c.drawText(frame.emptyMessage, 60 * k, headingBaseline + 65 * k, meta); c.restoreToCount(layer); return false; }
        if (shadowScale != k) {
            shadowScale = k;
            shadow.setMaskFilter(new BlurMaskFilter(30 * k, BlurMaskFilter.Blur.NORMAL));
        }
        float areaTop = headingBaseline + 40 * k;
        height = Math.min(410 * k, Math.max(180 * k, bottom - areaTop - 100 * k));
        width = height * 16 / 9;
        top = areaTop + (bottom - areaTop - height - 90 * k) / 2;
        c.save(); c.clipRect(40 * k, headingBaseline + 15 * k, 1920 * k, bottom);
        return true;
    }
    public boolean visible(float position) {
        float left = 60 * k + position * (width + 45 * k);
        return left < 1920 * k && left + width > 40 * k;
    }
    public void card(Canvas c, float position, float focusValue, String label, String caption, Bitmap image, int imageAlpha) {
        Paint title = titlePaint, meta = metaPaint;
        float left = 60 * k + position * (width + 45 * k);
        if (left >= 1920 * k) return;
        if (left + width <= 40 * k) return;
        float scaleFocus = 1 + .05f * focusValue;
        float cx = left + width / 2, cy = top + height / 2 - 20 * k - 6 * k * focusValue;
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
            float scale = Math.max(rect.width() / image.getWidth(), rect.height() / image.getHeight());
            float w = image.getWidth() * scale, h = image.getHeight() * scale;
            imageRect.set(rect.centerX() - w / 2, rect.centerY() - h / 2, rect.centerX() + w / 2, rect.centerY() + h / 2);
            c.drawBitmap(image, null, imageRect, paint); c.restore(); paint.setAlpha(255);
        }
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
        c.drawText(shown, left, top + height + 37 * k, title);
        c.drawText(caption, left, top + height + 70 * k, meta);
    }
    public void end(Canvas canvas) { canvas.restore(); canvas.restoreToCount(layer); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static int alpha(int color, float value) { return (Math.round((color >>> 24) * clamp(value)) << 24) | (color & 0xFFFFFF); }
}
