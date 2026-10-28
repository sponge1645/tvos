package io.tvos;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;

/** Menu Bar status. Listen only while Home is visible; never probe servers or poll per frame. */
final class NetworkStatus {
    static final int OFFLINE = 0, WIFI = 1, ETHERNET = 2;
    private final ConnectivityManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private boolean running, allRegistered, defaultRegistered;
    private int state = OFFLINE;
    private final Runnable refresh = this::refreshNow;

    private void refreshNow() {
        if (!running) return;
        int next = detect(manager);
        if (next != state) { state = next; changed.run(); }
    }

    NetworkStatus(Context context, Runnable changed) {
        manager = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.changed = changed;
    }

    int state() { return state; }

    private ConnectivityManager.NetworkCallback callback() {
        return new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { schedule(); }
            @Override public void onLost(Network n) { schedule(); }
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { schedule(); }
            @Override public void onLinkPropertiesChanged(Network n, LinkProperties p) { schedule(); }
        };
    }
    private final ConnectivityManager.NetworkCallback all = callback(), defaults = callback();

    private void schedule() {
        if (!running) return;
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 100);
    }

    void start() {
        if (running) return;
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
        refresh.run();
    }

    void stop() {
        running = false;
        handler.removeCallbacks(refresh);
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
