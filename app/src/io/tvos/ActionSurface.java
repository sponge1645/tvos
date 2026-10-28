package io.tvos;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** One glass/focus treatment for home actions and profile buttons. No per-frame allocations. */
final class ActionSurface {
    final RectF bounds = new RectF();
    private final RectF shadowBounds = new RectF();
    private final Scene scene;
    private final Shadows shadows;
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastScale;

    ActionSurface(Scene scene, Shadows shadows) {
        this.scene = scene;
        this.shadows = shadows;
    }

    float draw(Canvas c, RectF rest, float focus, float night, float k) {
        focus = Ui.clamp(focus);
        night = Ui.clamp(night);
        if (lastScale != k) {
            shadow.setMaskFilter(new BlurMaskFilter(26 * k, BlurMaskFilter.Blur.NORMAL));
            lastScale = k;
        }
        float scale = 1 + 0.1f * focus;
        float halfWidth = rest.width() * scale / 2f, radius = rest.height() * scale / 2f;
        bounds.set(rest.centerX() - halfWidth, rest.centerY() - radius,
                rest.centerX() + halfWidth, rest.centerY() + radius);
        shadowBounds.set(bounds);
        shadowBounds.offset(0, (6 + 8 * focus) * k);
        shadow.setColor(Ui.alpha(0xFF000000, (0.1f + 0.2f * night) + (0.14f + 0.06f * night) * focus));
        shadows.draw(c, shadowBounds, radius, 26 * k, shadow);
        scene.liquid(c, bounds, radius, blend(0xFFFFFFFF, 0xFF111113, night),
                0.55f - 0.05f * night, 0.02f - 0.01f * night, 12 * k, 8 * k,
                0.6f - 0.15f * night, 1 - focus);
        if (focus > 0.01f) {
            fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
            c.drawRoundRect(bounds, radius, radius, fill);
        }
        return scale;
    }

    static int ink(float night, float focus) {
        return blend(blend(0xFF1D1D1F, 0xEBFFFFFF, Ui.clamp(night)), 0xFF1D1D1F, Ui.clamp(focus));
    }

    static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        return Math.round(aa + ((b >>> 24) - aa) * f) << 24
                | Math.round(ar + ((b >> 16 & 255) - ar) * f) << 16
                | Math.round(ag + ((b >> 8 & 255) - ag) * f) << 8
                | Math.round(ab + ((b & 255) - ab) * f);
    }
}
