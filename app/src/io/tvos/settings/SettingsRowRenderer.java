package io.tvos.settings;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Reusable settings capsules and chevrons; logical label/focus bounds remain caller-owned. */
public final class SettingsRowRenderer {
    @FunctionalInterface
    public interface Shadow {
        void draw(Canvas canvas, RectF bounds, float radius, float blur, Paint paint);
    }

    private final Shadow shadowDrawer;
    private final RectF bounds = new RectF();
    private final Paint capsule = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();
    private float scale = -1;

    public SettingsRowRenderer(Shadow shadowDrawer) {
        this.shadowDrawer = shadowDrawer;
        white.setColor(0xFFF5F5F7);
        arrowPaint.setColor(0xFFA1A3A1);
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    private void resize(float k) {
        if (scale == k) return;
        scale = k;
        shadow.setMaskFilter(new BlurMaskFilter(40 * k, BlurMaskFilter.Blur.NORMAL));
        arrowPaint.setStrokeWidth(3.4425f * k);
        arrowPath.reset();
        arrowPath.moveTo(-11.475f * k, -13.77f * k);
        arrowPath.lineTo(2.295f * k, 0);
        arrowPath.lineTo(-11.475f * k, 13.77f * k);
    }

    public float draw(Canvas c, RectF row, float k, boolean light, float focus) {
        resize(k);
        float f = Math.max(0, Math.min(1, focus));
        float labelX = row.left + row.width() * 0.10f + 10 * k;
        bounds.set(labelX - 30 * k, row.top - 5 * k, row.right + 16 * k, row.bottom + 5 * k);
        float radius = bounds.height() / 2f;
        capsule.setColor(light ? 0xFFC9E4F7 : 0xFF31444B);
        c.drawRoundRect(bounds, radius, radius, capsule);
        if (f > 0.01f) {
            float grow = 1f + 0.012f * f;
            float cx = bounds.centerX(), cy = bounds.centerY();
            bounds.set(cx - bounds.width() * grow / 2f,
                    cy - bounds.height() * grow / 2f,
                    cx + bounds.width() * grow / 2f,
                    cy + bounds.height() * grow / 2f);
            float r = bounds.height() / 2f;
            bounds.offset(0, 15 * k * f);
            shadow.setColor(Math.round(255 * (0.40f * f)) << 24);
            shadowDrawer.draw(c, bounds, r, 40 * k, shadow);
            bounds.offset(0, -15 * k * f);
            white.setAlpha(Math.round(255 * f));
            c.drawRoundRect(bounds, r, r, white);
        }
        return f;
    }

    public void drawChevron(Canvas c, RectF row, float k) {
        resize(k);
        c.save();
        c.translate(row.right - 13 * k, row.centerY());
        c.drawPath(arrowPath, arrowPaint);
        c.restore();
    }
}
