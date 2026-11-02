package io.tvos.remote;

/** One remote sequence; scheduler and effects are injected, no Android or retained events. */
public final class TapSequence {
    public interface Scheduler { void later(Runnable task, long delay); void cancel(Runnable task); }
    public interface Sink { void action(String action); void original(boolean down, boolean cancelled); }
    private final Scheduler scheduler;
    private final Sink sink;
    private final String[] actions;
    private final boolean original;
    private boolean held, nativeHold, longHeld;
    private int taps;
    private final Runnable finish = this::finish;
    private final Runnable hold = this::hold;
    public TapSequence(Scheduler scheduler, Sink sink, String[] actions, boolean original) {
        if (actions.length != 3) throw new IllegalArgumentException("Three tap actions required");
        this.scheduler = scheduler; this.sink = sink; this.actions = actions.clone(); this.original = original;
    }
    public void down(int repeat) {
        if (held || repeat != 0) return;
        scheduler.cancel(finish); held = true; longHeld = false;
        scheduler.later(hold, 500);
    }
    public void up(boolean cancelled) {
        if (!held) return;
        held = false; scheduler.cancel(hold);
        if (longHeld) { if (nativeHold) sink.original(false, cancelled); nativeHold = false; taps = 0; return; }
        if (cancelled) { cancel(); return; }
        taps++;
        int max = !actions[2].isEmpty() ? 3 : !actions[1].isEmpty() ? 2 : 1;
        if (taps >= max) finish(); else scheduler.later(finish, 300);
    }
    private void hold() {
        if (!held) return;
        scheduler.cancel(finish); taps = 0; longHeld = true;
        // Global bindings never inject native keys. Home can route a held original key locally.
        nativeHold = original && "default".equals(actions[0]);
        if (nativeHold) sink.original(true, false);
    }
    private void finish() {
        scheduler.cancel(finish);
        int count = taps; taps = 0;
        if (count == 0 || held) return;
        String action = actions[Math.min(count, 3) - 1];
        if (action.isEmpty()) action = actions[0];
        if ("default".equals(action) && original) { sink.original(true, false); sink.original(false, false); }
        else if (!action.isEmpty() && !"none".equals(action) && !"default".equals(action)) sink.action(action);
    }
    public void cancel() {
        scheduler.cancel(finish); scheduler.cancel(hold);
        // Cancellation must not synthesize an ordinary click on a new screen.
        if (nativeHold) sink.original(false, true);
        held = nativeHold = longHeld = false; taps = 0;
    }
}
