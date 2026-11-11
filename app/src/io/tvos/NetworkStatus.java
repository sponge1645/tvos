package io.tvos;

import android.content.Context;
import io.tvos.network.WifiSignal;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;

/** Menu Bar status. Listen only while Home is visible; never probe servers or poll per frame. */
final class NetworkStatus {
    static final int OFFLINE = 0, WIFI = 1, ETHERNET = 2;
    private final ConnectivityManager manager;
    private final WifiSignal signal;
    private int wifiLevel = -1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private boolean running, allRegistered, defaultRegistered, vpn;
    private int state = OFFLINE;
    // Callback facts override snapshots that some TV firmware updates after onLost/onCapabilitiesChanged.
    // Owned by the main handler and discarded when Home stops.
    private final Map<Network, Boolean> vpnEvents = new HashMap<>();
    private boolean refreshPending;
    private final Runnable refresh = () -> { refreshPending = false; refreshNow(); };
    private int checks;
    private final Runnable convergence = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refreshNow();
            if (--checks > 0) handler.postDelayed(this, 500);
        }
    };
    void checkSoon() { if (!running) return; checks = 20; handler.removeCallbacks(convergence); handler.postDelayed(convergence, 0); }
    void rescan() { refreshNow(); }

    private void refreshNow() {
        if (!running) return;
        int next = detect(manager);
        boolean nextVpn = detectVpn(manager, vpnEvents);
        int nextLevel = next == WIFI ? signal.level() : -1;
        if (next != state || nextVpn != vpn || nextLevel != wifiLevel) {
            state = next; vpn = nextVpn; wifiLevel = nextLevel; changed.run();
        }
    }

    NetworkStatus(Context context, Runnable changed) {
        manager = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.changed = changed;
        signal = new WifiSignal(context, this::schedule);
    }

    int state() { return state; }
    int wifiLevel() { return wifiLevel; }
    boolean vpn() { return vpn; }

    @SuppressWarnings("deprecation")
    static boolean detectVpn(ConnectivityManager manager) {
        return detectVpn(manager, Collections.emptyMap());
    }

    @SuppressWarnings("deprecation")
    private static boolean detectVpn(ConnectivityManager manager, Map<Network, Boolean> events) {
        if (events.containsValue(Boolean.TRUE)) return true;
        if (manager == null) return false;
        try {
            for (Network network : manager.getAllNetworks()) {
                if (Boolean.FALSE.equals(events.get(network))) continue;
                try {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
                } catch (RuntimeException ignored) { /* One disappearing network must not hide another VPN. */ }
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    private ConnectivityManager.NetworkCallback callback(boolean trackVpn) {
        return new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                if (!running) return;
                if (trackVpn) vpnEvents.remove(n);
                schedule();
            }
            @Override public void onLost(Network n) {
                if (!running) return;
                if (trackVpn) vpnEvents.put(n, Boolean.FALSE);
                schedule();
            }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                if (!running) return;
                if (trackVpn) vpnEvents.put(n, c.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                schedule();
            }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties p) { schedule(); }
        };
    }
    private final ConnectivityManager.NetworkCallback all = callback(true), defaults = callback(false);

    private void schedule() {
        if (!running || refreshPending) return;
        refreshPending = true;
        handler.postDelayed(refresh, 0);
    }

    void start() {
        if (running) return;
        vpnEvents.clear();
        running = true;
        if (manager != null) {
            try {
                // Include VPN callbacks too: its validation can change while the cable stays connected.
                manager.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().build(), all, handler);
                allRegistered = true;
            } catch (RuntimeException ignored) {}
            try {
                manager.registerDefaultNetworkCallback(defaults, handler);
                defaultRegistered = true;
            } catch (RuntimeException ignored) {}
        }
        signal.start();
        refresh.run();
    }

    void stop() {
        if (!running) return;
        running = false;
        vpnEvents.clear();
        refreshPending = false;
        signal.stop();
        handler.removeCallbacks(refresh); handler.removeCallbacks(convergence);
        if (allRegistered) {
            try { manager.unregisterNetworkCallback(all); } catch (RuntimeException ignored) {}
            allRegistered = false;
        }
        if (defaultRegistered) {
            try { manager.unregisterNetworkCallback(defaults); } catch (RuntimeException ignored) {}
            defaultRegistered = false;
        }
    }

    // Inspect validated physical networks behind a VPN; the default route alone is insufficient.
    @SuppressWarnings("deprecation")
    static int detect(ConnectivityManager manager) {
        if (manager == null) return OFFLINE;
        try {
            Network active = manager.getActiveNetwork();
            NetworkCapabilities activeCaps = manager.getNetworkCapabilities(active);
            // INTERNET alone also describes a router with a broken uplink/captive portal.
            if (activeCaps == null || !activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                return OFFLINE;
            int best = OFFLINE, bestScore = -1;
            for (Network n : manager.getAllNetworks()) {
                try {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(n);
                    if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    boolean ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    if (!ethernet && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                    int score = (n.equals(active) ? 100 : 0)
                            + (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 10 : 0)
                            + (ethernet ? 2 : 0);
                    if (score > bestScore) { bestScore = score; best = ethernet ? ETHERNET : WIFI; }
                } catch (RuntimeException ignored) {}
            }
            return best;
        } catch (RuntimeException ignored) { return OFFLINE; }
    }
}
