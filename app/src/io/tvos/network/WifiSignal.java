package io.tvos.network;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

/** Current connection RSSI only; no scans, SSID collection, timers or location requests. */
public final class WifiSignal {
    private final Context context;
    private final WifiManager manager;
    private final Runnable changed;
    private boolean registered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { if (registered) changed.run(); }
    };
    public WifiSignal(Context context, Runnable changed) {
        this.context = context.getApplicationContext();
        manager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.changed = changed;
    }
    public void start() {
        if (registered || manager == null) return;
        try {
            IntentFilter filter = new IntentFilter(WifiManager.RSSI_CHANGED_ACTION);
            // Protected system broadcast; data is re-read from WifiManager, not from intent extras.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
            registered = true;
        } catch (RuntimeException ignored) { /* A normal connectivity refresh still reads RSSI. */ }
    }
    public void stop() {
        if (!registered) return;
        registered = false;
        try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
    }
    @SuppressWarnings("deprecation")
    public int level() {
        try {
            WifiInfo info = manager == null ? null : manager.getConnectionInfo();
            return info == null ? -1 : levelForRssi(info.getRssi());
        } catch (RuntimeException ignored) { return -1; }
    }
    public static int levelForRssi(int rssi) {
        if (rssi <= -127 || rssi >= 0) return -1;
        if (rssi < -85) return 0;
        if (rssi < -70) return 1;
        if (rssi < -55) return 2;
        return 3;
    }
}
