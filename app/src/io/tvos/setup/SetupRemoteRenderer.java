package io.tvos.setup;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** White-part silhouette from remote-white.svg; buttons are real transparent holes. */
public final class SetupRemoteRenderer {
    private final Path body = new Path();
    private final RectF rect = new RectF();
    public SetupRemoteRenderer() {
        body.setFillType(Path.FillType.EVEN_ODD);
        body.moveTo(44, 10); body.lineTo(143, 10); body.cubicTo(164, 10, 175, 22, 175, 43);
        body.lineTo(175, 106); body.lineTo(179, 108); body.lineTo(179, 177); body.lineTo(176, 180);
        body.lineTo(182, 610); body.cubicTo(182.5f, 637, 173, 650, 151, 650);
        body.lineTo(49, 650); body.cubicTo(27, 650, 16, 639, 16, 616);
        body.lineTo(10, 46); body.cubicTo(9.5f, 22, 21, 10, 44, 10); body.close();
        oval(20, 70, 166, 214); oval(128, 28, 156, 56);
        round(86, 39, 98, 45, 3);
        oval(30, 221, 86, 277); oval(100, 221, 156, 277);
        oval(31, 291, 87, 347); oval(32, 361, 88, 417);
        round(101, 290, 159, 417, 28);
    }
    private void oval(float l, float t, float r, float b) {
        rect.set(l, t, r, b); body.addOval(rect, Path.Direction.CW);
    }
    private void round(float l, float t, float r, float b, float radius) {
        rect.set(l, t, r, b); body.addRoundRect(rect, radius, radius, Path.Direction.CW);
    }
    public void draw(Canvas canvas, float x, float y, float height, Paint paint) {
        int save = canvas.save(); canvas.translate(x, y); canvas.scale(height / 660, height / 660);
        canvas.drawPath(body, paint); canvas.restoreToCount(save);
    }
}
