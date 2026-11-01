package io.tvos.settings;

import android.graphics.Canvas;
import android.graphics.Paint;
import java.util.function.IntUnaryOperator;

/** Menu/title animation state; navigation, focus springs and frame scheduling stay with the caller. */
public final class SettingsTransitions {
    public interface ScreenPainter { void draw(Canvas canvas, int screen); }
    private static final long DURATION_MS = 280;
    private int drawnScreen = -1, transitionFrom = -1;
    private long transitionStarted, focusReadyAt;
    private String pageTitleShown, pageTitlePrevious;
    private long pageTitleStarted;

    /** Returns true only when the caller must reset inherited row focus. */
    public boolean sync(int screen, long now, boolean reduceMotion, boolean modal,
            IntUnaryOperator parent) {
        if (drawnScreen == screen) return false;
        boolean previous = drawnScreen >= 0;
        boolean forward = parent.applyAsInt(screen) == drawnScreen;
        transitionFrom = previous && !reduceMotion && !modal
                && (forward || parent.applyAsInt(drawnScreen) == screen) ? drawnScreen : -1;
        transitionStarted = now;
        focusReadyAt = now + (previous && !reduceMotion ? DURATION_MS : 0);
        drawnScreen = screen;
        return true;
    }

    public boolean focusReady(long now) { return now >= focusReadyAt; }

    public boolean drawTitle(Canvas c, String next, long now, boolean reduceMotion,
            float x, float y, Paint paint, SettingsTextRenderer textRenderer) {
        if (pageTitleShown == null) pageTitleShown = next;
        if (!next.equals(pageTitleShown)) {
            pageTitlePrevious = pageTitleShown;
            pageTitleShown = next;
            pageTitleStarted = now;
        }
        float t = reduceMotion ? 1f : Math.min(1f,
                (now - pageTitleStarted) / (float) DURATION_MS);
        if (pageTitlePrevious == null || t >= 1f) {
            pageTitlePrevious = null;
            textRenderer.drawTracked(c, pageTitleShown, x, y, paint, 0.02f);
            return false;
        }
        float eased = t * t * (3f - 2f * t);
        int alpha = paint.getAlpha();
        paint.setAlpha(Math.round(alpha * (1f - eased)));
        textRenderer.drawTracked(c, pageTitlePrevious, x, y, paint, 0.02f);
        paint.setAlpha(Math.round(alpha * eased));
        textRenderer.drawTracked(c, pageTitleShown, x, y, paint, 0.02f);
        paint.setAlpha(alpha);
        return true;
    }

    public boolean drawList(Canvas c, int screen, long now, float k, int w, int h,
            IntUnaryOperator parent, ScreenPainter painter) {
        if (transitionFrom == -1) {
            painter.draw(c, screen);
            return false;
        }
        float t = Math.min(1f, (now - transitionStarted)
                / (float) DURATION_MS);
        if (t >= 1f) {
            transitionFrom = -1;
            painter.draw(c, screen);
            return false;
        }
        float eased = t * t * (3f - 2f * t);
        boolean forward = parent.applyAsInt(screen) == transitionFrom;
        float direction = forward ? -1f : 1f;
        int previous = transitionFrom;
        // Clip to list area to avoid painting across the fixed tvOS logo.
        c.save();
        c.clipRect(960 * k, 140 * k, w, h);
        c.save();
        c.translate(direction * 105f * k * eased, 0);
        int oldLayer = c.saveLayerAlpha(960 * k, 140 * k, w, h,
                Math.max(0, Math.min(255, Math.round(255 * (1f - eased)))));
        painter.draw(c, previous);
        c.restoreToCount(oldLayer);
        c.restore();
        c.save();
        c.translate(-direction * 90f * k * (1f - eased), 0);
        int newLayer = c.saveLayerAlpha(960 * k, 140 * k, w, h,
                Math.max(0, Math.min(255, Math.round(255 * eased))));
        painter.draw(c, screen);
        c.restoreToCount(newLayer);
        c.restore();
        c.restore();
        return true;
    }
}
