package io.tvos;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import io.tvos.weather.WeatherCardStyle;
import io.tvos.weather.WeatherForecast;

/** Bounded native weather scene; no network assets, bitmaps or per-frame shader allocations. */
final class WeatherCardBackground {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF bounds = new RectF(0, 0, 700, 700), inner = new RectF(1.5f, 1.5f, 698.5f, 698.5f);
    private final Path clip = new Path();
    private final Shader cloud = new RadialGradient(0, 0, 1, new int[]{0x4AEBF3FF,0x19DBE6F3,0}, new float[]{0,.5f,1}, Shader.TileMode.CLAMP);
    private final Shader sun = new RadialGradient(0, 0, 1, new int[]{0x9FFFF5D3,0x38FFF1CD,0}, new float[]{0,.2f,1}, Shader.TileMode.CLAMP);
    private final Shader veil = new LinearGradient(0, 0, 0, 700, new int[]{0x160A1624,0x380A1624,0x660A1624}, null, Shader.TileMode.CLAMP);
    private final Shader rim = new LinearGradient(0, 0, 700, 700,
            new int[]{0xD9FFFFFF,0x35FFFFFF,0x08FFFFFF,0x58FFFFFF,0x15FFFFFF},
            new float[]{0,.24f,.50f,.78f,1}, Shader.TileMode.CLAMP);
    private Shader sky;
    private int cachedTop, cachedBottom;
    WeatherCardBackground() { clip.addRoundRect(bounds, 58, 58, Path.Direction.CW); }
    boolean draw(Canvas c, RectF panel, WeatherForecast w, long now, boolean day, boolean reduced) {
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
        if (kind == WeatherCardStyle.CLEAR && day) glow(c, sun, 20, 0, 440, 440);
        if (!day && (kind == WeatherCardStyle.CLEAR || kind == WeatherCardStyle.CLOUD)) {
            paint.setShader(null);
            for (int i = 0; i < (kind == WeatherCardStyle.CLEAR ? 75 : 24); i++) {
                paint.setColor((i%3 == 0 ? 0x88FFFFFF : 0x45FFFFFF));
                c.drawCircle((i*179+31)%700, (i*263+17)%700, i%5 == 0 ? 1.2f : .65f, paint);
            }
        }
        if (kind != WeatherCardStyle.CLEAR && kind != WeatherCardStyle.UNKNOWN || w.code == 1) {
            for (int i = 0; i < 7; i++) glow(c, cloud, (i*137-80)%780, i*93-40, 300, 115);
        }
        boolean rain = kind == WeatherCardStyle.RAIN || kind == WeatherCardStyle.STORM;
        boolean snow = kind == WeatherCardStyle.SNOW;
        if (rain || snow) {
            paint.setShader(null); paint.setStrokeWidth(1.2f); paint.setStrokeCap(Paint.Cap.ROUND);
            int count = snow ? 35 : w.code == 51 || w.code == 53 ? 25 : 60;
            float seconds = reduced ? 0 : (now % 120_000L)/1000f;
            for (int i = 0; i < count; i++) {
                float y = ((i*157+seconds*(snow ? 18+i%9 : 170+i%61))%780)-40;
                float x = (i*193+37)%700;
                paint.setColor(snow ? 0x70F4FAFF : 0x459DCAE2);
                if (snow) c.drawCircle(x+(float)Math.sin(seconds*.4f+i)*12, y, 1.3f+i%3*.4f, paint);
                else c.drawLine(x,y,x-2,y+9+i%12,paint);
            }
        }
        paint.setShader(veil); c.drawRect(bounds, paint); paint.setShader(null);
        c.restore();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.8f); paint.setShader(rim);
        c.drawRoundRect(inner, 57, 57, paint);
        paint.setShader(null); paint.setColor(0x12FFFFFF); paint.setStrokeWidth(.8f);
        c.drawRoundRect(bounds, 58, 58, paint); paint.setStyle(Paint.Style.FILL);
        c.restoreToCount(saved);
        return !reduced && (rain || snow);
    }
    private void glow(Canvas c, Shader shader, float x, float y, float width, float height) {
        int saved = c.save(); c.translate(x,y); c.scale(width,height);
        paint.setShader(shader); c.drawCircle(0,0,1,paint); paint.setShader(null); c.restoreToCount(saved);
    }
}
