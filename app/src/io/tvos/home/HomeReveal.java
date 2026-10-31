package io.tvos.home;

/** One-shot ease-out progress. No animator, bitmap, worker, or independent frame loop. */
public final class HomeReveal {
    public static final long DURATION_MS = 845;
    private long started;
    private float remaining;
    private boolean prepared;

    public void prepare(boolean reduced) {
        prepared = !reduced;
        remaining = reduced ? 0 : 1;
    }
    public boolean prepared() { return prepared; }
    public void start(long now, boolean reduced) {
        prepared = false;
        started = now;
        remaining = reduced ? 0 : 1;
    }
    public void cancel() { prepared = false; remaining = 0; }
    public boolean active() { return !prepared && remaining > 0; }
    public float remaining() { return remaining; }
    public float opacity() { return 1 - remaining; }
    public float scale() { return 1 - 0.10f * remaining; }
    public boolean tick(long now) {
        if (!active() || now < started) return false;
        float t = Math.max(0, Math.min(1, (now - started) / (float) DURATION_MS));
        float rest = 1 - t;
        remaining = rest * rest * rest;
        return true; // Includes the final identity frame.
    }
}
