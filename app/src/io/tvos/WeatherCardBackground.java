package io.tvos;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import io.tvos.weather.WeatherCardStyle;
import io.tvos.weather.WeatherForecast;

/** Static weather/time color gradient and glass rim; no particle animation. */
final class WeatherCardBackground {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF bounds = new RectF(0, 0, 700, 700), inner = new RectF(1.5f, 1.5f, 698.5f, 698.5f);
    private final Path clip = new Path();
    private final Shader veil = new LinearGradient(0, 0, 0, 700, new int[]{0x160A1624,0x380A1624,0x660A1624}, null, Shader.TileMode.CLAMP);
    private final Shader rim = new LinearGradient(0, 0, 700, 700,
            new int[]{0xD9FFFFFF,0x35FFFFFF,0x08FFFFFF,0x58FFFFFF,0x15FFFFFF},
            new float[]{0,.24f,.50f,.78f,1}, Shader.TileMode.CLAMP);
    private Shader sky;
    private int cachedTop, cachedBottom;
    WeatherCardBackground() { clip.addRoundRect(bounds, 58, 58, Path.Direction.CW); }
    void draw(Canvas c, RectF panel, WeatherForecast w, long now, boolean day) {
        int kind = WeatherCardStyle.kind(w.code);
        int top = WeatherCardStyle.top(kind, day, WeatherCardStyle.twilight(w, now));
        int bottom = WeatherCardStyle.bottom(kind, day);
        if (sky == null || top != cachedTop || bottom != cachedBottom) {
            sky = new LinearGradient(0, 0, 0, 700, top, bottom, Shader.TileMode.CLAMP);
            cachedTop = top; cachedBottom = bottom;
        }
        int saved = c.save(); c.translate(panel.left, panel.top); c.scale(panel.width()/700, panel.height()/700);
        c.save(); c.clipPath(clip);
        paint.setStyle(Paint.Style.FILL); paint.setShader(sky); c.drawRect(bounds, paint);
        paint.setShader(veil); c.drawRect(bounds, paint); paint.setShader(null);
        c.restore();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setShader(rim);
        c.drawRoundRect(inner, 57, 57, paint);
        paint.setShader(null); paint.setColor(0x12FFFFFF); paint.setStrokeWidth(.8f);
        c.drawRoundRect(bounds, 58, 58, paint); paint.setStyle(Paint.Style.FILL);
        c.restoreToCount(saved);
    }
}
