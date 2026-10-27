package io.tvos;

import android.os.Handler;

/** UI-only update rehearsal. Owns no network, files, package manager or installer. */
final class FakeUpdate {
    interface Listener { void update(int kind, int percent); }
    private final Handler handler;
    private final Listener listener;
    private Runnable pending;
    private int generation, state = SelfUpdate.IDLE;
    FakeUpdate(Handler handler, Listener listener) { this.handler = handler; this.listener = listener; }
    void cancel() {
        generation++;
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
        state = SelfUpdate.IDLE;
    }
    private void emit(int kind, int percent) { state = kind; listener.update(kind, percent); }
    void check() {
        cancel();
        int token = generation;
        emit(SelfUpdate.CHECKING, -1);
        pending = () -> { if (token == generation) emit(SelfUpdate.AVAILABLE, -1); };
        handler.postDelayed(pending, 1500);
    }
    void download() {
        if (state != SelfUpdate.AVAILABLE) return;
        int token = generation;
        emit(SelfUpdate.DOWNLOADING, 0);
        step(token, 0);
    }
    private void step(int token, int percent) {
        pending = () -> {
            if (token != generation) return;
            int next = percent + 5;
            if (next > 100) {
                pending = null;
                emit(SelfUpdate.ERROR, 100); // Deliberate end of rehearsal; never READY/installable.
            } else {
                emit(SelfUpdate.DOWNLOADING, next);
                step(token, next);
            }
        };
        handler.postDelayed(pending, 400);
    }
}
