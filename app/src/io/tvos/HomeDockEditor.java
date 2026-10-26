package io.tvos;

import android.os.Handler;
import android.view.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/** Long-press input and Dock order transaction. The host owns tile geometry, focus and animation. */
final class HomeDockEditor {
    interface Host {
        boolean eligible();
        List<HomeApps.Item> items();
        int index();
        void begin();
        void move(int from, int to);
        void finish(List<HomeApps.Item> restore);
        void saved();
        void canceled();
    }

    private static final long HOLD_MS = 500L;
    private final Handler handler;
    private final Host host;
    private boolean editing, armed, dropArmed, backDown;
    private List<HomeApps.Item> before;
    private final Runnable hold = this::begin;

    HomeDockEditor(Handler handler, Host host) {
        this.handler = handler;
        this.host = host;
    }

    boolean editing() { return editing; }

    void arm() {
        cancelHold();
        armed = !editing && host.eligible();
        if (armed) handler.postDelayed(hold, HOLD_MS);
    }

    void cancelHold() {
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

    boolean onKey(KeyEvent event) {
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

    void move(int direction) {
        if (!editing) return;
        int from = host.index(), to = from + direction;
        if (to >= 0 && to < host.items().size()) host.move(from, to);
    }

    void finish(boolean save) {
        if (!editing) return;
        editing = false;
        host.finish(save ? null : before);
        if (save) {
            List<String> packages = new ArrayList<>();
            for (HomeApps.Item item : host.items()) {
                if ("tv-settings".equals(item.key)) packages.add("io.tvos.virtual.tvsettings");
                else if (item.pkg != null) packages.add(item.pkg);
            }
            Prefs.setShelf(packages);
            host.saved();
        }
        dropArmed = backDown = false;
        before = null;
    }

    void pause() {
        cancelHold();
        finish(false);
    }
}
