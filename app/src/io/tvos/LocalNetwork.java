package io.tvos;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.net.Inet4Address;
import java.net.InetAddress;

/** Settings describe the physical LAN, not the default route through a VPN tunnel. */
final class LocalNetwork {
    static final class Info {
        final String address;
        final boolean ethernet;
        Info(String address, boolean ethernet) { this.address = address; this.ethernet = ethernet; }
    }
    private static Info cached;
    private static long checkedAt = -1;

    /** The settings view redraws frequently; limit binder queries to once a second. */
    static Info read(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (checkedAt >= 0 && now >= checkedAt && now - checkedAt < 1000) return cached;
        cached = query((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE));
        checkedAt = now;
        return cached;
    }

    // Enumerate physical networks even when a VPN owns the default route.
    @SuppressWarnings("deprecation")
    static Info query(ConnectivityManager manager) {
        if (manager == null) return null;
        Info best = null;
        int bestScore = -1;
        try {
            Network active = manager.getActiveNetwork();
            Network[] networks = manager.getAllNetworks();
            if (networks == null) return null;
            for (Network network : networks) {
                try {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                    // VPN capabilities can ALSO include Wi-Fi/Ethernet. Reject VPN first.
                    if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                    boolean ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                    if (!ethernet && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                    LinkProperties links = manager.getLinkProperties(network);
                    if (links == null) continue; // Network may disconnect between the two queries.
                    String address = ipv4(links);
                    if (address == null) continue;
                    int score = (network.equals(active) ? 100 : 0)
                            + (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 10 : 0)
                            + (ethernet ? 2 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new Info(address, ethernet);
                    }
                } catch (RuntimeException ignored) { /* A single disappearing network must not hide the rest. */ }
            }
        } catch (RuntimeException ignored) { /* No permission or unavailable connectivity service. */ }
        return best;
    }

    private static String ipv4(LinkProperties links) {
        for (LinkAddress link : links.getLinkAddresses()) {
            InetAddress address = link.getAddress();
            if (address instanceof Inet4Address && !address.isLoopbackAddress()
                    && !address.isAnyLocalAddress() && !address.isLinkLocalAddress()
                    && !address.isMulticastAddress()) return address.getHostAddress();
        }
        return null;
    }
}
