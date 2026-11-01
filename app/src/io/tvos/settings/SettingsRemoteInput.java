package io.tvos.settings;

import android.view.KeyEvent;

/** Remote key protocol only; screen policy, sounds and accessibility belong to the caller. */
public final class SettingsRemoteInput {
    public interface Actions {
        void vertical(int direction, boolean down, boolean first);
        void horizontal(int direction, boolean down, boolean first);
        void confirm();
        void back(boolean activate, boolean root);
    }

    private boolean okDown, backDown;

    public void reset() { okDown = backDown = false; }

    public boolean onKey(KeyEvent event, boolean root, Actions actions) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = event.getRepeatCount() == 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                actions.vertical(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1, down, first);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                actions.horizontal(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, down, first);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (first) okDown = true;
                } else {
                    if (okDown && !event.isCanceled()) actions.confirm();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (root) {
                    actions.back(!down && !event.isCanceled(), true);
                    return false;
                }
                if (down) {
                    if (first) backDown = true;
                } else {
                    boolean activate = backDown && !event.isCanceled();
                    backDown = false;
                    actions.back(activate, false);
                    return true;
                }
                actions.back(false, false);
                return true;
            default:
                return false;
        }
    }
}
