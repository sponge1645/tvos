package io.tvos;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import io.tvos.version.SoftwareBuild;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    private static final String TRACE_TAG = "tvOS-Volume";

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (Log.isLoggable(TRACE_TAG, Log.DEBUG)) {
            android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
            Log.d(TRACE_TAG, "CONNECTED build=" + SoftwareBuild.SEQUENCE
                    + " flags=" + (info == null ? -1 : info.flags));
        }
    }

    @Override public boolean onUnbind(Intent intent) {
        traceLifecycle("UNBIND");
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        traceLifecycle("DESTROY");
        super.onDestroy();
    }

    private static void traceLifecycle(String state) {
        if (Log.isLoggable(TRACE_TAG, Log.DEBUG)) Log.d(TRACE_TAG, state);
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        boolean handled = RemoteKeys.onKey(this, event);
        // Opt-in diagnostics for every key: firmware may use unexpected volume codes.
        // Observe only: retain the actual app-key policy result and original event.
        if (Log.isLoggable(TRACE_TAG, Log.DEBUG)) {
            Log.d(TRACE_TAG, "key=" + event.getKeyCode() + " action=" + event.getAction()
                    + " repeat=" + event.getRepeatCount() + " down=" + event.getDownTime()
                    + " time=" + event.getEventTime() + " device=" + event.getDeviceId()
                    + " scan=" + event.getScanCode() + " source=" + event.getSource()
                    + " flags=" + event.getFlags()
                    + " handled=" + handled);
        }
        return handled;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        ChiqBootGuard.onWindowEvent(this, event);
    }

    @Override public void onInterrupt() { traceLifecycle("INTERRUPT"); }
}
