package io.tvos.artwork;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

/** Bitmap drawing boundary shared by rounded images and scene renderers. */
@FunctionalInterface
public interface ImagePainter {
    void draw(Canvas canvas, Bitmap bitmap, RectF bounds, float radius);
}
