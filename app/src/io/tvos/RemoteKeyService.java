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
    private AdbVolumeKeys volume;
    private int volumeLearn,volumeGeneration;
    private final CaptureLease capture = new CaptureLease();
    private final Set<String> captured = new HashSet<>();
    private boolean registered, home;
    private final BroadcastReceiver commands = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                volumeGeneration++;if (volume != null) { volume.close(); volume=null; }
                setKeyFilter(false);
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) { startVolume(); return; }
            capture.update(i.getStringExtra("token"), i.getLongExtra("until", 0), SystemClock.elapsedRealtime());
            volumeLearn=i.getIntExtra("volume",0);
            if (volume != null) volume.learn(capture.token(SystemClock.elapsedRealtime()),volumeLearn,i.getLongExtra("until",0));
            if (shortcuts != null) shortcuts.cancel();
        }
    };
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        capture.clear(); captured.clear();
        if (shortcuts != null) shortcuts.close();
        shortcuts = new RemoteShortcuts(this, false, null);
        setKeyFilter(false);startVolume();
        if (!registered) {
            IntentFilter filter = new IntentFilter(RemoteKeyCapture.COMMAND);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(commands, filter, RemoteKeyCapture.PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(commands, filter, RemoteKeyCapture.PERMISSION, null);
            registered = true;
        }
        AppLog.w("Remote key filter connected");
    }
    private void setKeyFilter(boolean enabled) {
        AccessibilityServiceInfo info=getServiceInfo();if(info==null)return;
        int flags=enabled?info.flags|AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                :info.flags&~AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        if(flags!=info.flags){info.flags=flags;setServiceInfo(info);}
        if(!enabled){captured.clear();if(shortcuts!=null)shortcuts.cancel();}
    }
    private void startVolume() {
        final int generation=++volumeGeneration;
        if (volume != null) volume.close();
        volume=new AdbVolumeKeys(this,(token,mode,device,scan)->{
            if(volume==null||mode!=volumeLearn||!token.equals(capture.token(SystemClock.elapsedRealtime())))return;
            volume.save(mode,device,scan);
            sendBroadcast(new Intent(RemoteKeyCapture.EVENT).setPackage(getPackageName())
                    .putExtra("token",token).putExtra("volumeDevice",device).putExtra("volumeScan",scan),RemoteKeyCapture.PERMISSION);
        },token->{
            if(volumeLearn!=0&&token.equals(capture.token(SystemClock.elapsedRealtime())))
                sendBroadcast(new Intent(RemoteKeyCapture.EVENT).setPackage(getPackageName())
                        .putExtra("token",token).putExtra("volumeReady",true),RemoteKeyCapture.PERMISSION);
        },connected->{if(generation==volumeGeneration)setKeyFilter(connected);});
    }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (volumeLearn != 0 && !capture.token(SystemClock.elapsedRealtime()).isEmpty()) return false;
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
        // The native volume panel can belong to System UI during raw volume learning.
        // The editor still owns the short lease and stops it on pause/exit.
        if (!own && volumeLearn == 0) capture.clear();
        if (shortcuts != null) shortcuts.cancel();
    }
    @Override public void onInterrupt() { volumeGeneration++;setKeyFilter(false);if (volume != null) volume.close(); capture.clear(); if (shortcuts != null) shortcuts.cancel(); }
    @Override public void onDestroy() {
        volumeGeneration++;setKeyFilter(false);
        if (volume != null) { volume.close(); volume = null; }
        capture.clear(); captured.clear();
        if (registered) { unregisterReceiver(commands); registered = false; }
        if (shortcuts != null) { shortcuts.close(); shortcuts = null; }
        super.onDestroy();
    }
}
