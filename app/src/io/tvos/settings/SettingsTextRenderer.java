package io.tvos.settings;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

/** Settings typography; owns reusable paints and one wrapped description, without screen state. */
public final class SettingsTextRenderer {
    private final TextPaint shelfMeasurePaint = new TextPaint();
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
    private boolean fittedKeepEllipsis;

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
        return fit(value, paint, available, tracking, false);
    }

    /** Picker labels retain their historical ellipsis even when the ellipsis cannot fit. */
    public String fitPickerLabel(String value, Paint paint, float available, float tracking) {
        return fit(value, paint, available, tracking, true);
    }

    private String fit(String value, Paint paint, float available, float tracking, boolean keepEllipsis) {
        float size = paint.getTextSize();
        Typeface typeface = paint.getTypeface();
        if (value.equals(fittedSource) && available == fittedWidth && size == fittedSize
                && typeface == fittedTypeface && tracking == fittedTracking && keepEllipsis == fittedKeepEllipsis) return fittedValue;
        String shown = value;
        int end = value.length();
        while (trackedWidth(shown, paint, tracking) > available) {
            if (end == 0) { shown = ""; break; }
            end = value.offsetByCodePoints(end, -1);
            shown = value.substring(0, end) + "…";
            if (keepEllipsis && end == 0) break;
        }
        fittedSource = value;
        fittedWidth = available;
        fittedSize = size;
        fittedTypeface = typeface;
        fittedTracking = tracking;
        fittedKeepEllipsis = keepEllipsis;
        return fittedValue = shown;
    }

    public void drawRowLabel(Canvas c, String label, RectF row, float scale, float baseline, Paint paint) {
        drawTracked(c, label, row.left + row.width() * .10f + 10 * scale, baseline, paint, .02f);
    }

    public void drawRowValue(Canvas c, String value, RectF row, float scale, float baseline,
            float rightInset, Paint paint) {
        drawTracked(c, value, row.right - rightInset * scale, baseline, paint, .02f);
    }

    public String fitRowValue(String label, String value, RectF row, float scale, Paint labelPaint, Paint valuePaint) {
        float labelX = row.left + row.width() * .10f + 10 * scale;
        float available = Math.max(0, row.right - 61 * scale - labelX
                - trackedWidth(label, labelPaint, .02f) - 32 * scale);
        return fitValue(value, valuePaint, available, .02f);
    }

    public void drawPickerLabel(Canvas c, String label, RectF row, float scale,
            float baseline, boolean selected, Paint paint) {
        float x = row.left + row.width() * .10f + 10 * scale;
        float right = row.right - 24 * scale;
        if (selected) right -= 38 * scale;
        drawTracked(c, fitPickerLabel(label, paint, Math.max(0f, right - x), .02f), x, baseline, paint, .02f);
    }

    public void beginShelf(Paint labelPaint) { shelfMeasurePaint.set(labelPaint); }

    public void drawShelfLabel(Canvas c, String label, RectF row, float scale, float baseline, Paint paint) {
        drawRowLabel(c, TextUtils.ellipsize(label, shelfMeasurePaint, 520 * scale,
                TextUtils.TruncateAt.END).toString(), row, scale, baseline, paint);
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
