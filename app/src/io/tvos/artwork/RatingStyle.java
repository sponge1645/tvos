package io.tvos.artwork;

import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import java.util.Arrays;

/** UI-thread preparation shared by poster rating badges; one gradient per visible card slot. */
public final class RatingStyle {
    private final LinearGradient[] gradients;
    private final float[] left, top, bottom;
    private final int[] colors;
    private Typeface source, bold;

    public RatingStyle(int slots) {
        gradients = new LinearGradient[slots];
        left = new float[slots];
        top = new float[slots];
        bottom = new float[slots];
        colors = new int[slots];
    }

    public Typeface bold(Typeface base) {
        if (bold == null || source != base) {
            source = base;
            bold = Typeface.create(base, Typeface.BOLD);
        }
        return bold;
    }

    public LinearGradient sheen(int slot, float x, float y0, float y1, int color) {
        if (gradients[slot] == null || left[slot] != x || top[slot] != y0
                || bottom[slot] != y1 || colors[slot] != color) {
            gradients[slot] = new LinearGradient(x, y0, x, y1, color, 0x00FFFFFF, Shader.TileMode.CLAMP);
            left[slot] = x;
            top[slot] = y0;
            bottom[slot] = y1;
            colors[slot] = color;
        }
        return gradients[slot];
    }

    public void clear() {
        Arrays.fill(gradients, null);
        source = null;
        bold = null;
    }
}
