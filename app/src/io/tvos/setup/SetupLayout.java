package io.tvos.setup;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

/** Geometry in the setup's 1920x1080 design space, using visible glyph/path bounds. */
public final class SetupLayout {
    public static final float GAP = 50, CENTER_Y = 540, BUTTON_HEIGHT = 70 * 1.012f, BUTTON_STEP = 105;
    private final Rect glyph = new Rect();
    private final RectF artwork = new RectF();
    private int top, bottom;

    private void measure(Paint paint, String[] lines, float size, float width) {
        top = 0; bottom = 0;
        for (String line : lines) {
            fit(paint, line, size, width);
            paint.getTextBounds(line, 0, line.length(), glyph);
            top = Math.min(top, glyph.top); bottom = Math.max(bottom, glyph.bottom);
        }
    }
    private void fit(Paint paint, String line, float size, float width) {
        paint.setTextSize(size);
        float measured = paint.measureText(line);
        if (measured > width) paint.setTextSize(size * width / measured);
    }
    public float height(Paint paint, String[] lines, float size, float width, float step) {
        measure(paint, lines, size, width);
        return bottom - top + (lines.length - 1) * step;
    }
    public void draw(Canvas canvas, Paint paint, String[] lines, float x, float y,
                     float size, float width, float step) {
        measure(paint, lines, size, width);
        float baseline = y - top;
        for (int i = 0; i < lines.length; i++) {
            fit(paint, lines[i], size, width);
            canvas.drawText(lines[i], x, baseline + i * step, paint);
        }
    }
    public void artwork(Canvas canvas, Path path, Paint paint, float x, float height, float width) {
        path.computeBounds(artwork, true);
        float scale = Math.min(height / artwork.height(), width / artwork.width());
        int saved = canvas.save();
        canvas.translate(x, CENTER_Y); canvas.scale(scale, scale);
        canvas.translate(-artwork.centerX(), -artwork.centerY());
        canvas.drawPath(path, paint); canvas.restoreToCount(saved);
    }
}
