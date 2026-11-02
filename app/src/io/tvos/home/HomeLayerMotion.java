package io.tvos.home;

import android.view.View;

/** Applies drawer, sheet and dream-return transforms to existing cached layers. No frame loop or UI state. */
public final class HomeLayerMotion {
    private static final int VISIBLE = View.VISIBLE, INVISIBLE = View.INVISIBLE;
    private final View revealBackdrop, sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer;

    public HomeLayerMotion(View revealBackdrop, View sky, View heroLayer, View status,
            View platter, View tiles, View drawerLayer, View sheetLayer) {
        this.revealBackdrop = revealBackdrop; this.sky = sky; this.heroLayer = heroLayer; this.status = status;
        this.platter = platter; this.tiles = tiles; this.drawerLayer = drawerLayer; this.sheetLayer = sheetLayer;
    }

    public void apply(float drawer, float sheet, boolean sheetOpen, HomeReveal reveal,
            float k, int height, float dockTop, float drawerExtraHeight) {
        float d = drawer, dc = clamp(d), s = clamp(sheet);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = 560 * k;
        float lift = -d * shift;
        show(heroLayer, top * reveal.opacity(), lift);
        show(status, top, lift);
        float dockOffset = (height - dockTop + 20 * k) * reveal.remaining();
        show(platter, home, lift - d * drawerExtraHeight + dockOffset);
        show(tiles, home, lift - d * drawerExtraHeight + dockOffset);
        revealBackdrop.setVisibility(reveal.remaining() > 0 ? VISIBLE : INVISIBLE);
        sky.setAlpha(reveal.opacity());
        float contentScale = reveal.scale();
        scale(sky, contentScale);
        scale(heroLayer, contentScale);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void scale(View v, float value) { v.setScaleX(value); v.setScaleY(value); }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }
}
