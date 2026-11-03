package io.tvos.setup;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Smooth native body/circular cutouts and cached button symbols; no pixel-grid contours. */
public final class SetupRemoteRenderer {
    private final Path body = new Path(), symbols = new Path();
    private final Paint symbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    public SetupRemoteRenderer() {
        body.setFillType(Path.FillType.EVEN_ODD);
        body.moveTo(44,10); body.lineTo(143,10); body.cubicTo(164,10,175,22,175,43);
        body.lineTo(175,106); body.lineTo(179,108); body.lineTo(179,177); body.lineTo(176,180);
        body.lineTo(182,610); body.cubicTo(182.5f,637,173,650,151,650); body.lineTo(49,650);
        body.cubicTo(27,650,16,639,16,616); body.lineTo(10,46); body.cubicTo(9.5f,22,21,10,44,10); body.close();
        oval(body,20,70,166,214); oval(body,128,28,156,56);
        bounds.set(86,39,98,45); body.addRoundRect(bounds,3,3,Path.Direction.CW);
        oval(body,30,221,86,277); oval(body,100,221,156,277);
        oval(body,31,291,87,347); oval(body,32,361,88,417);
        body.moveTo(130,290); body.cubicTo(146,290,158,303,158,319); body.lineTo(159,388);
        body.cubicTo(159,404,147,417,131,417); body.cubicTo(115,417,102,404,102,388);
        body.lineTo(101,319); body.cubicTo(101,303,114,290,130,290); body.close();
        oval(symbols,51,100,135,184);
        bounds.set(135,35,149,49); symbols.arcTo(bounds,-45,270,true);
        symbols.moveTo(142,32); symbols.lineTo(142,41);
        symbols.moveTo(65,241); symbols.lineTo(57,249); symbols.lineTo(65,257);
        bounds.set(118,242,138,255); symbols.addRoundRect(bounds,1,1,Path.Direction.CW);
        symbols.moveTo(124,258); symbols.lineTo(132,258);
        symbols.moveTo(48,312); symbols.lineTo(58,319); symbols.lineTo(48,326); symbols.close();
        symbols.moveTo(64,312); symbols.lineTo(64,326); symbols.moveTo(69,312); symbols.lineTo(69,326);
        symbols.moveTo(130,312); symbols.lineTo(130,328); symbols.moveTo(122,320); symbols.lineTo(138,320);
        symbols.moveTo(123,388); symbols.lineTo(139,388);
        symbols.moveTo(51,385); symbols.lineTo(55,385); symbols.lineTo(61,380); symbols.lineTo(61,398);
        symbols.lineTo(55,393); symbols.lineTo(51,393); symbols.close();
        symbols.moveTo(48,378); symbols.lineTo(68,400);
        oval(symbols,92.5f,79.5f,93.5f,80.5f); oval(symbols,92.5f,203.5f,93.5f,204.5f);
        oval(symbols,29.5f,141.5f,30.5f,142.5f); oval(symbols,155.5f,141.5f,156.5f,142.5f);
    }
    private void oval(Path path,float l,float t,float r,float b) {
        bounds.set(l,t,r,b); path.addOval(bounds,Path.Direction.CW);
    }
    public void draw(Canvas canvas,float x,float y,float height,Paint paint) {
        int saved=canvas.save(); canvas.translate(x,y); canvas.scale(height/660,height/660);
        canvas.drawPath(body,paint);
        symbolPaint.set(paint); symbolPaint.setStyle(Paint.Style.STROKE); symbolPaint.setStrokeWidth(2.5f);
        symbolPaint.setStrokeCap(Paint.Cap.ROUND); symbolPaint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(symbols,symbolPaint); canvas.restoreToCount(saved);
    }
}
