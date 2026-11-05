package io.tvos.home;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One Up press: short navigation on release, or one exact recommendation after a hold. */
public final class RecommendationHold {
    public static final long HOLD_MS = 600;
    public interface Scheduler {
        void postDelayed(Runnable task, long delay);
        void remove(Runnable task);
    }
    private final Scheduler scheduler;
    private final LongSupplier clock;
    private Runnable timer, shortPress, longPress;
    private BooleanSupplier valid;
    private boolean active, fired;
    private long started;
    private int generation;

    public RecommendationHold(Scheduler scheduler, LongSupplier clock) {
        this.scheduler = scheduler; this.clock = clock;
    }
    public boolean active() { return active; }
    public void begin(BooleanSupplier valid, Runnable shortPress, Runnable longPress) {
        cancel();
        this.valid = valid; this.shortPress = shortPress; this.longPress = longPress;
        active = true; started = clock.getAsLong();
        final int token = generation;
        timer = () -> {
            if (!active || token != generation) return;
            if (!this.valid.getAsBoolean()) { cancel(); return; }
            if (!fired) { fired = true; this.longPress.run(); }
        };
        scheduler.postDelayed(timer, HOLD_MS);
    }
    public void release(boolean cancelled) {
        if (!active) return;
        Runnable action = null;
        if (!cancelled && !fired && valid.getAsBoolean())
            action = clock.getAsLong() - started >= HOLD_MS ? longPress : shortPress;
        cancel();
        if (action != null) action.run();
    }
    public void cancel() {
        generation++;
        if (timer != null) scheduler.remove(timer);
        active = fired = false;
        timer = shortPress = longPress = null; valid = null;
    }
}
