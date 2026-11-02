package io.tvos;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        boolean handled = RemoteKeys.onKey(this, event);
        int code = event.getKeyCode();
        // Opt-in diagnostics at the Android key-filter boundary, not raw Linux input.
        // Observe only: volume must still follow the system's original path.
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)
                && Log.isLoggable("tvOS-Volume", Log.DEBUG)) {
            Log.d("tvOS-Volume", "key=" + code + " action=" + event.getAction()
                    + " repeat=" + event.getRepeatCount() + " down=" + event.getDownTime()
                    + " time=" + event.getEventTime() + " device=" + event.getDeviceId()
                    + " scan=" + event.getScanCode() + " flags=" + event.getFlags()
                    + " handled=" + handled);
        }
        return handled;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        ChiqBootGuard.onWindowEvent(this, event);
    }

    @Override public void onInterrupt() {}
}
