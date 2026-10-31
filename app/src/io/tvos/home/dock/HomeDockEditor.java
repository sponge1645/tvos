package io.tvos.home.dock;

import android.os.Handler;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Long-press input and Dock order transaction. The host owns tile geometry, focus and animation. */
public final class HomeDockEditor<T> {
    public interface Host<T> {
        boolean eligible();
        List<T> items();
        int index();
        void begin();
        void move(int from, int to);
        void finish(List<T> restore);
        void saved();
        void canceled();
    }

    public static final long HOLD_MS = 500L;
    private final Handler handler;
    private final Host<T> host;
    private final Consumer<List<T>> persist;
    private boolean editing, armed, dropArmed, backDown;
    private List<T> before;
    private final Runnable hold = this::begin;

    public HomeDockEditor(Handler handler, Host<T> host, Consumer<List<T>> persist) {
        this.handler = handler;
        this.host = host;
        this.persist = persist;
    }

    public boolean editing() { return editing; }

    public void arm() {
        cancelHold();
        armed = !editing && host.eligible();
        if (armed) handler.postDelayed(hold, HOLD_MS);
    }

    public void cancelHold() {
        handler.removeCallbacks(hold);
        armed = false;
    }

    private void begin() {
        if (!armed || !host.eligible()) return;
        armed = false;
        editing = true;
        dropArmed = backDown = false;
        before = new ArrayList<>(host.items());
        host.begin();
    }

    public boolean onKey(KeyEvent event) {
        if (!editing) return false;
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) dropArmed = true; // Release of the initial hold never saves.
                else if (dropArmed && event.getRepeatCount() == 0) finish(true);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (event.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !event.isCanceled()) {
                        finish(false);
                        host.canceled();
                    }
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    public void move(int direction) {
        if (!editing) return;
        int from = host.index(), to = from + direction;
        if (to >= 0 && to < host.items().size()) host.move(from, to);
    }

    public void finish(boolean save) {
        if (!editing) return;
        editing = false;
        host.finish(save ? null : before);
        if (save) {
            persist.accept(host.items());
            host.saved();
        }
        dropArmed = backDown = false;
        before = null;
    }

    public void pause() {
        cancelHold();
        finish(false);
    }
}
