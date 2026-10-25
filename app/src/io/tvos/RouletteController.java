package io.tvos;

import android.os.Handler;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

/** Main-thread random-movie state, loading and poster window; no view or focus ownership. */
final class RouletteController {
    private final Handler handler;
    private final Recs recs;
    private final BooleanSupplier running, enabled, selected;
    private final Runnable changed, completed;
    private final Random random = new Random();
    private final List<Recs.Pick> catalog = new ArrayList<>();
    private final List<Recs.Pick> picks = Collections.unmodifiableList(catalog);
    private UiRequest request;
    private int generation, winner, posterSlot = Integer.MIN_VALUE;
    private boolean loading, spinning, finished;
    private long started;
    private final Runnable complete = this::completeSpin;

    private void completeSpin() {
        if (!running.getAsBoolean() || !selected.getAsBoolean() || !spinning) return;
        spinning = false;
        finished = true;
        completed.run();
    }

    RouletteController(Handler handler, Recs recs, BooleanSupplier running,
                       BooleanSupplier enabled, BooleanSupplier selected,
                       Runnable changed, Runnable completed) {
        this.handler = handler;
        this.recs = recs;
        this.running = running;
        this.enabled = enabled;
        this.selected = selected;
        this.changed = changed;
        this.completed = completed;
    }

    List<Recs.Pick> picks() { return picks; }
    boolean loading() { return loading; }
    boolean spinning() { return spinning; }
    boolean finished() { return finished; }
    int winner() { return winner; }
    long started() { return started; }

    void cancel() {
        generation++;
        if (request != null) request.cancel();
        request = null;
        loading = false;
        handler.removeCallbacks(complete);
        if (spinning) {
            spinning = false;
            finished = false;
            started = 0L;
        }
    }

    /** Changing categories also discards the completed-spin presentation. */
    void reset() {
        cancel();
        finished = false;
        started = 0L;
    }

    void load() {
        if (!running.getAsBoolean() || !enabled.getAsBoolean() || loading) return;
        loading = true;
        int serial = ++generation;
        request = recs.loadAcclaimed(results -> {
            if (!running.getAsBoolean() || !selected.getAsBoolean() || serial != generation) return;
            request = null;
            loading = false;
            catalog.clear();
            catalog.addAll(results);
            changed.run();
        });
    }

    boolean spin() {
        if (spinning) return false;
        if (catalog.isEmpty()) {
            load();
            return false;
        }
        winner = random.nextInt(catalog.size());
        invalidatePosters();
        finished = false;
        spinning = true;
        started = SystemClock.uptimeMillis();
        preparePosters(57);
        handler.removeCallbacks(complete);
        handler.postDelayed(complete, 5000);
        return true;
    }

    void invalidatePosters() { posterSlot = Integer.MIN_VALUE; }

    /** Keep the winner plus the same bounded window around the moving strip. */
    void preparePosters(int slot) {
        if (!running.getAsBoolean() || catalog.isEmpty() || slot == posterSlot) return;
        posterSlot = slot;
        List<Recs.Pick> window = new ArrayList<>(14);
        window.add(catalog.get(winner));
        int center = winner - slot, count = catalog.size();
        for (int distance = 0; distance <= 6; distance++) {
            window.add(catalog.get(Math.floorMod(center + distance, count)));
            if (distance != 0) window.add(catalog.get(Math.floorMod(center - distance, count)));
        }
        recs.setPosterWindow(window);
        for (Recs.Pick pick : window) recs.poster(pick);
    }
}
