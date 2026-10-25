package io.tvos;

import android.os.Handler;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-thread search scheduling; the view owns presentation, results and focus. */
final class SearchController {
    private final Handler handler;
    private final Recs recs;
    private final BooleanSupplier active;
    private final Consumer<List<Recs.Pick>> deliver;
    private UiRequest request;
    private Runnable pending;
    private int serial;
    private boolean retryOnResume;

    SearchController(Handler handler, Recs recs, BooleanSupplier active,
                     Consumer<List<Recs.Pick>> deliver) {
        this.handler = handler;
        this.recs = recs;
        this.active = active;
        this.deliver = deliver;
    }

    void changed(String query) {
        cancel();
        if (!longEnough(query)) return;
        int generation = serial;
        pending = () -> {
            pending = null;
            submit(query, generation);
        };
        handler.postDelayed(pending, 400);
    }

    void restore(String query, boolean hasResults) {
        if (!hasResults && request == null && pending == null && longEnough(query))
            submit(query, ++serial);
    }

    /** Done/back submits the last text immediately, even before its debounce expires. */
    void flush() {
        if (pending == null) return;
        Runnable submit = pending;
        handler.removeCallbacks(submit);
        pending = null;
        if (active.getAsBoolean()) submit.run();
    }

    void pause(boolean searchMode) {
        boolean retry = searchMode && (request != null || pending != null);
        cancel();
        retryOnResume = retry;
    }

    void resume(String query) {
        if (retryOnResume) submit(query, ++serial);
        retryOnResume = false;
    }

    void cancel() {
        serial++;
        retryOnResume = false;
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
        if (request != null) request.cancel();
        request = null;
    }

    private void submit(String query, int generation) {
        if (!active.getAsBoolean() || generation != serial || !longEnough(query)) return;
        request = recs.search(query, found -> {
            if (!active.getAsBoolean() || generation != serial) return;
            request = null;
            deliver.accept(found);
        });
    }

    private static boolean longEnough(String query) {
        return query.codePointCount(0, query.length()) >= 3;
    }
}
