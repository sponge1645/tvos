package io.tvos.settings;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** Settings typography; owns reusable paints and one wrapped description, without screen state. */
public final class SettingsTextRenderer {
    private final Paint glyphPaint = new Paint();
    private final TextPaint descriptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private StaticLayout descriptionLayout;
    private String descriptionText;
    private int descriptionWidth;
    private float descriptionScale = -1;
    private Typeface descriptionTypeface;
    private String fittedSource, fittedValue;
    private float fittedWidth = -1, fittedSize = -1, fittedTracking;
    private Typeface fittedTypeface;

    public void clear() {
        descriptionLayout = null;
        descriptionText = null;
        fittedSource = fittedValue = null;
        fittedTypeface = null;
    }

    public void drawTracked(Canvas canvas, String text,
                            float x, float y, Paint paint, float tracking) {
        if (text == null || text.isEmpty()) return;

        float spacing = paint.getTextSize() * tracking;
        float width = trackedWidth(text, paint, tracking);

        float cursor = x;
        if (paint.getTextAlign() == Paint.Align.CENTER) {
            cursor -= width / 2f;
        } else if (paint.getTextAlign() == Paint.Align.RIGHT) {
            cursor -= width;
        }

        glyphPaint.set(paint);
        glyphPaint.setTextAlign(Paint.Align.LEFT);

        for (int i = 0; i < text.length();) {
            int next = text.offsetByCodePoints(i, 1);
            canvas.drawText(text, i, next, cursor, y, glyphPaint);
            cursor += glyphPaint.measureText(text, i, next) + spacing;
            i = next;
        }
    }

    public static float trackedWidth(String text, Paint paint, float tracking) {
        return paint.measureText(text) + paint.getTextSize() * tracking
                * Math.max(0, text.codePointCount(0, text.length()) - 1);
    }

    /** Keep one ellipsized value, including font and tracking in the measurement identity. */
    public String fitValue(String value, Paint paint, float available, float tracking) {
        float size = paint.getTextSize();
        Typeface typeface = paint.getTypeface();
        if (value.equals(fittedSource) && available == fittedWidth && size == fittedSize
                && typeface == fittedTypeface && tracking == fittedTracking) return fittedValue;
        String shown = value;
        int end = value.length();
        while (trackedWidth(shown, paint, tracking) > available) {
            if (end == 0) { shown = ""; break; }
            end = value.offsetByCodePoints(end, -1);
            shown = value.substring(0, end) + "…";
        }
        fittedSource = value;
        fittedWidth = available;
        fittedSize = size;
        fittedTypeface = typeface;
        fittedTracking = tracking;
        return fittedValue = shown;
    }

    /** Preserve the established wrapping, spacing and baseline at every display scale. */
    @SuppressWarnings("deprecation")
    public void drawDescription(Canvas c, String info, float k, boolean light, Typeface typeface) {
        if (info == null) return;
        descriptionPaint.setColor(light ? 0xB33A5761 : 0xFF888D90);
        int width = Math.round(740 * k);
        if (descriptionLayout == null || !info.equals(descriptionText) || width != descriptionWidth
                || k != descriptionScale || typeface != descriptionTypeface) {
            descriptionPaint.setTypeface(typeface);
            descriptionPaint.setTextSize(30 * k);
            descriptionLayout = new StaticLayout(info, descriptionPaint, width,
                    Layout.Alignment.ALIGN_CENTER, 1.18f, 0, false);
            descriptionText = info;
            descriptionWidth = width;
            descriptionScale = k;
            descriptionTypeface = typeface;
        }
        c.save();
        c.translate(152 * k, 837 * k);
        descriptionLayout.draw(c);
        c.restore();
    }
}
