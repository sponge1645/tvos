package io.tvos;

/** One-shot ease-out progress. No animator, bitmap, worker, or independent frame loop. */
final class HomeReveal {
    static final long DURATION_MS = 845;
    static final long DELAY_MS = 2000;
    private long started;
    private float remaining;
    private boolean prepared;

    void prepare(boolean reduced) {
        prepared = !reduced;
        remaining = reduced ? 0 : 1;
    }
    boolean prepared() { return prepared; }
    void start(long now, boolean reduced) {
        prepared = false;
        started = now + DELAY_MS;
        remaining = reduced ? 0 : 1;
    }
    void cancel() { prepared = false; remaining = 0; }
    boolean active() { return !prepared && remaining > 0; }
    float remaining() { return remaining; }
    float scale() { return 1 - 0.10f * remaining; }
    long delay(long now) { return active() && now < started ? started - now : Long.MAX_VALUE; }
    boolean tick(long now) {
        if (!active() || now < started) return false;
        float t = Math.max(0, Math.min(1, (now - started) / (float) DURATION_MS));
        float rest = 1 - t;
        remaining = rest * rest * rest;
        return true; // Includes the final identity frame.
    }
}
