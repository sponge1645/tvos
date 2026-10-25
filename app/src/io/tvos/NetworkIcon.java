package io.tvos;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Small vector status glyphs, using the same focus color and scale as the other menu actions. */
final class NetworkIcon {
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    void draw(Canvas canvas, int state, float cx, float cy, float size, int color) {
        int saved = canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(size / 30f, size / 30f);
        ink.setColor(color);
        ink.setStrokeWidth(2.2f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setStyle(Paint.Style.STROKE);
        if (state == NetworkStatus.ETHERNET) {
            arc.set(-11, -10, 11, 5);
            canvas.drawRoundRect(arc, 2, 2, ink);
            for (int x = -6; x <= 6; x += 4) canvas.drawLine(x, -9, x, -4, ink);
            canvas.drawLine(0, 5, 0, 11, ink);
            canvas.drawLine(-7, 11, 7, 11, ink);
        } else {
            for (int radius = 7; radius <= 17; radius += 5) {
                arc.set(-radius, 8 - radius, radius, 8 + radius);
                canvas.drawArc(arc, 225, 90, false, ink);
            }
            ink.setStyle(Paint.Style.FILL);
            canvas.drawCircle(0, 8, 1.7f, ink);
            if (state == NetworkStatus.OFFLINE) {
                ink.setStyle(Paint.Style.STROKE);
                canvas.drawLine(-12, -11, 12, 12, ink);
            }
        }
        canvas.restoreToCount(saved);
    }
}
