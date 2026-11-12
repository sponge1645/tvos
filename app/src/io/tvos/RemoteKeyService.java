package io.tvos;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import io.tvos.remote.CaptureLease;
import io.tvos.remote.RemoteBindings;
import java.util.HashSet;
import java.util.Set;

/** Optional early hardware-key filter; preserves native navigation, volume, power and Home OK. */
public class RemoteKeyService extends AccessibilityService {
    private RemoteShortcuts shortcuts;
    private VolumeKeyRepeat volume;
    private final CaptureLease capture = new CaptureLease();
    private final Set<String> captured = new HashSet<>();
    private boolean registered, home;
    private final BroadcastReceiver commands = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                if (volume != null) volume.cancel();
                return;
            }
            capture.update(i.getStringExtra("token"), i.getLongExtra("until", 0), SystemClock.elapsedRealtime());
            if (shortcuts != null) shortcuts.cancel();
        }
    };
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        capture.clear(); captured.clear();
        if (shortcuts != null) shortcuts.close();
        shortcuts = new RemoteShortcuts(this, false, null);
        if (volume != null) volume.cancel();
        volume = new VolumeKeyRepeat(this);
        if (!registered) {
            IntentFilter filter = new IntentFilter(RemoteKeyCapture.COMMAND);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(commands, filter, RemoteKeyCapture.PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(commands, filter, RemoteKeyCapture.PERMISSION, null);
            registered = true;
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        AppLog.w("Remote key filter connected");
    }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (volume != null && volume.onKey(event)) return false;
        int code = event.getKeyCode();
        if (!RemoteBindings.allowed(code) || RemoteBindings.ok(code)
                || RemoteBindings.gameController(event.getSource(), code)) return false;
        if (event.getAction() != KeyEvent.ACTION_DOWN && event.getAction() != KeyEvent.ACTION_UP) return false;
        String identity = event.getDeviceId() + ":" + code + ":" + event.getScanCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        // A capture can end between DOWN and UP. Never leak that trailing UP to another target.
        boolean trailing = !down && captured.remove(identity);
        String token = capture.token(SystemClock.elapsedRealtime());
        if (!token.isEmpty()) {
            if (down && event.getRepeatCount() == 0) {
                if (captured.size() >= RemoteBindings.MAX) return false;
                captured.add(identity);
            }
            sendBroadcast(new Intent(RemoteKeyCapture.EVENT).setPackage(getPackageName())
                    .putExtra("token", token).putExtra("key", new KeyEvent(event)), RemoteKeyCapture.PERMISSION);
            if (!down || event.getRepeatCount() == 0) AppLog.w("Remote capture key code=" + code
                    + " scan=" + event.getScanCode() + " source=" + event.getSource() + " action=" + event.getAction());
            return true;
        }
        if (trailing) return true;
        if (shortcuts == null || !home && shortcuts.homeBinding(event)) return false;
        return shortcuts.onKey(event);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        ChiqBootGuard.onWindowEvent(this, event);
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        boolean own = getPackageName().contentEquals(event.getPackageName() == null ? "" : event.getPackageName());
        home = own && "io.tvos.HomeActivity".contentEquals(event.getClassName() == null ? "" : event.getClassName());
        if (!own) capture.clear();
        if (shortcuts != null) shortcuts.cancel();
    }
    @Override public void onInterrupt() { if (volume != null) volume.cancel(); capture.clear(); if (shortcuts != null) shortcuts.cancel(); }
    @Override public void onDestroy() {
        if (volume != null) { volume.cancel(); volume = null; }
        capture.clear(); captured.clear();
        if (registered) { unregisterReceiver(commands); registered = false; }
        if (shortcuts != null) { shortcuts.close(); shortcuts = null; }
        super.onDestroy();
    }
}
