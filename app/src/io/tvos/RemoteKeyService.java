package io.tvos;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Optional firmware boot guard. Remote assignments use RemoteInputMethod. */
public class RemoteKeyService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        ChiqBootGuard.onWindowEvent(this, event);
    }
    @Override public void onInterrupt() { }
}
