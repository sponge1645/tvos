package io.tvos.settings;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;

/** Isolated list layer: clip content and fade it into the background at both edges. */
public final class SettingsListViewport {
    public interface Content {
        void draw(Canvas canvas);
    }

    private final Paint mask = new Paint();
    private float left, top;
    private int width, height;

    public SettingsListViewport() {
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    public void resize(int width, int height, float scale) {
        this.width = width;
        this.height = height;
        left = 960 * scale;
        top = 148 * scale;
        float span = Math.max(1f, height - top);
        float topStop = Math.min(0.25f, 38 * scale / span);
        float bottomStop = Math.max(topStop, 1f - 64 * scale / span);
        mask.setShader(new LinearGradient(0, top, 0, height,
                new int[] {0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[] {0f, topStop, bottomStop, 1f}, Shader.TileMode.CLAMP));
    }

    public void draw(Canvas canvas, Content content) {
        int layer = canvas.saveLayer(left, top, width, height, null);
        try {
            canvas.clipRect(left, top, width, height);
            content.draw(canvas);
            canvas.drawRect(left, top, width, height, mask);
        } finally {
            canvas.restoreToCount(layer);
        }
    }
}
