package io.tvos;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Wallpaper-aware input backing: geometry, drawing resources and dismissal cleanup. */
final class HomeSearchGlass {
    private final View home, layer;
    private final Scene scene;
    private final Shadows shadows;
    private final RectF bounds = new RectF(), shadowBounds = new RectF();
    private final int[] panelPosition = new int[2], homePosition = new int[2];
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean visible;
    private float scale;

    HomeSearchGlass(View home, View layer, Scene scene, Shadows shadows) {
        this.home = home; this.layer = layer; this.scene = scene; this.shadows = shadows;
    }

    void resize(float k) {
        if (scale == k) return;
        shadow.setMaskFilter(new BlurMaskFilter(26 * k, BlurMaskFilter.Blur.NORMAL));
        scale = k;
    }

    boolean visible() { return visible && !bounds.isEmpty(); }

    void sync(View panel) {
        if (panel.getWidth() == 0 || panel.getHeight() == 0) return;
        panel.getLocationOnScreen(panelPosition);
        home.getLocationOnScreen(homePosition);
        bounds.set(panelPosition[0] - homePosition[0], panelPosition[1] - homePosition[1],
                panelPosition[0] - homePosition[0] + panel.getWidth(),
                panelPosition[1] - homePosition[1] + panel.getHeight());
        visible = true;
        layer.setVisibility(View.VISIBLE);
        layer.invalidate();
    }

    void hide() {
        visible = false;
        layer.animate().cancel();
        layer.setAlpha(1f);
        layer.setScaleX(1f); layer.setScaleY(1f);
        layer.setTranslationY(0f);
        layer.setVisibility(View.INVISIBLE);
    }

    void draw(Canvas c, float k, float night) {
        if (!visible()) return;
        float radius = bounds.height() / 2f;
        shadowBounds.set(bounds);
        shadowBounds.offset(0, 9 * k);
        shadow.setColor(ActionSurface.blend(0x1F000000, 0x52000000, night));
        shadows.draw(c, shadowBounds, radius, 26 * k, shadow);
        scene.liquid(c, bounds, radius, ActionSurface.blend(0xFFFFFFFF, 0xFF111113, night),
                mix(0.42f, 0.12f, night), mix(0.03f, 0.08f, night),
                mix(30, 34, night) * k, mix(22, 28, night) * k, mix(0.65f, 0.95f, night), 1);
    }

    private static float mix(float from, float to, float amount) { return from + (to - from) * amount; }
}
