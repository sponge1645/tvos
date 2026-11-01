package io.tvos.settings;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Cached tile geometry with a wordmark cutout revealing the settings background. */
public final class SettingsLogoRenderer {
    private final RectF logoBounds = new RectF();
    private final Paint.FontMetrics logoMetrics = new Paint.FontMetrics();
    private final Matrix logoMatrix = new Matrix();
    private final Path logoCutout = new Path();
    private final Paint logoInk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoTile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shader darkLogoGradient = new LinearGradient(0, 1, 1, 0,
            0xFF51595B, 0xFF55646B, Shader.TileMode.CLAMP);
    private float logoScale = -1;
    private Typeface logoTypeface;

    public void draw(Canvas c, float k, boolean light, Typeface typeface) {
        if (logoScale != k || logoTypeface != typeface) {
            logoScale = k;
            logoTypeface = typeface;
            logoInk.setTypeface(typeface);
            logoInk.setTextAlign(Paint.Align.CENTER);
            logoBounds.set(254 * k, 236 * k, 790 * k, 772 * k);
            logoInk.setTextSize(58 * k);
            float width = logoInk.measureText("tvOS");
            if (width > 0f) logoInk.setTextSize(logoInk.getTextSize() * logoBounds.width() / (2f * width));
            logoInk.getFontMetrics(logoMetrics);
            float baseline = logoBounds.centerY() - (logoMetrics.ascent + logoMetrics.descent) / 2f;
            logoCutout.reset();
            logoInk.getTextPath("tvOS", 0, 4, logoBounds.centerX(), baseline, logoCutout);
            logoMatrix.setScale(logoBounds.width(), logoBounds.height());
            logoMatrix.postTranslate(logoBounds.left, logoBounds.top);
            darkLogoGradient.setLocalMatrix(logoMatrix);
        }
        logoTile.setColor(0xFF8897AC);
        logoTile.setShader(light ? null : darkLogoGradient);
        c.save();
        c.clipOutPath(logoCutout);
        c.drawRoundRect(logoBounds, 116 * k, 116 * k, logoTile);
        c.restore();
    }
}
