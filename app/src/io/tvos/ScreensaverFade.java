package io.tvos;

import android.os.Build;
import android.os.SystemClock;
import android.view.View;

/** A short, cancellable fade. Old TV firmware cannot be relied on to run property animators. */
final class ScreensaverFade {
    interface Alpha { void set(float value); }
    private final View host;
    private final Alpha alpha;
    private final boolean frameDriven;
    private Runnable frame;
    private int generation;
    private float value;

    ScreensaverFade(View view) {
        this(view, view::setAlpha, view.getAlpha(), Build.VERSION.SDK_INT <= 29);
        viewTarget = true;
    }

    /** Window alpha also covers the separate SurfaceView video layer on pre-DreamActivity Android. */
    ScreensaverFade(View host, Alpha alpha, float initial) {
        this(host, alpha, initial, true);
    }

    private ScreensaverFade(View host, Alpha alpha, float initial, boolean frameDriven) {
        this.host = host;
        this.alpha = alpha;
        this.value = initial;
        this.frameDriven = frameDriven;
    }

    void cancel() {
        generation++;
        if (frame != null) host.removeCallbacks(frame);
        frame = null;
        if (!frameDriven) host.animate().cancel();
    }

    void reset(float target) {
        cancel();
        value = target;
        alpha.set(target);
    }

    void start(float target, long duration, Runnable finished) {
        cancel();
        final int token = generation;
        if (!frameDriven) {
            host.animate().alpha(target).setDuration(duration)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        if (generation != token) return;
                        value = target;
                        if (finished != null) finished.run();
                    }).start();
            return;
        }
        // Read a view's current alpha when reversing a partially completed transition.
        final float from = viewTarget ? host.getAlpha() : value;
        final long started = SystemClock.uptimeMillis();
        frame = new Runnable() {
            @Override public void run() {
                if (generation != token) return;
                float t = duration <= 0 ? 1f : Math.min(1f,
                        Math.max(0f, (SystemClock.uptimeMillis() - started) / (float) duration));
                float eased = (float) (0.5 - 0.5 * Math.cos(Math.PI * t));
                value = t == 1f ? target : from + (target - from) * eased;
                alpha.set(value);
                if (t < 1f) host.postOnAnimation(this);
                else {
                    frame = null;
                    if (finished != null) finished.run();
                }
            }
        };
        frame.run();
    }

    private boolean viewTarget;
}
