package io.tvos;

/** One-shot ease-out progress. No animator, bitmap, worker, or independent frame loop. */
final class HomeReveal {
    static final long DURATION_MS = 650;
    private long started;
    private float remaining;

    void start(long now, boolean reduced) {
        started = now;
        remaining = reduced ? 0 : 1;
    }
    void cancel() { remaining = 0; }
    boolean active() { return remaining > 0; }
    float remaining() { return remaining; }
    float scale() { return 1 - 0.03f * remaining; }
    boolean tick(long now) {
        if (!active()) return false;
        float t = Math.max(0, Math.min(1, (now - started) / (float) DURATION_MS));
        float rest = 1 - t;
        remaining = rest * rest * rest;
        return true; // Includes the final identity frame.
    }
}
