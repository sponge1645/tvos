package io.tvos.home.dock;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Exclusive outgoing/incoming caption state with a delayed text fallback and no frame loop. */
public final class CaptionTransition<T> {
    private static final long OUT_MS = 260, IN_MS = 420, TITLE_DELAY_MS = 700;
    private final BiPredicate<T,T> same;
    private final Predicate<T> delayed;
    private T current, target;
    private long fadeAt = -1, appearAt;
    private float fadeFrom;
    private boolean outgoing;

    public CaptionTransition(BiPredicate<T,T> same, Predicate<T> delayed) { this.same = same; this.delayed = delayed; }

    public void show(T value, boolean motion, long now) {
        advance(now);
        if (same.test(target, value)) return;
        target = value;
        if (!motion) { current = target; fadeAt = -1; outgoing = false; return; }
        float opacity = opacity(now);
        if (current != null && opacity > 0) {
            fadeFrom = opacity; fadeAt = now; outgoing = true;
            appearAt = now + OUT_MS;
        } else {
            current = null; fadeAt = -1; outgoing = false;
            appearAt = now;
        }
        if (target != null && delayed.test(target)) appearAt += TITLE_DELAY_MS;
    }

    private void advance(long up) {
        if (outgoing && up >= fadeAt + OUT_MS) { current = null; fadeAt = -1; outgoing = false; }
        if (current == null && target != null && up >= appearAt) { current = target; fadeAt = appearAt; fadeFrom = 0; }
        if (!outgoing && fadeAt >= 0 && up >= fadeAt + IN_MS) fadeAt = -1;
    }
    public T current(long now) { advance(now); return current; }
    public float opacity(long up) {
        if (fadeAt < 0) return current == null ? 0 : 1;
        float p = Math.max(0, Math.min(1, (up - fadeAt) / (float) (outgoing ? OUT_MS : IN_MS)));
        float eased = p * p * (3 - 2 * p);
        return outgoing ? fadeFrom * (1 - eased) : eased;
    }
    public boolean tick(long up) {
        boolean changed = fadeAt >= 0;
        T before = current;
        advance(up);
        return changed || before != current || fadeAt >= 0;
    }
    public long nextDelay(long up) { return current == null && target != null ? Math.max(0, appearAt - up) : Long.MAX_VALUE; }
}
