package io.tvos;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cached IP location, using only an explicitly selected physical network. Never falls back to the VPN route. */
final class AutoRegion {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long checkedAt, checkedGeneration = -1, attemptedAt, attemptedNetwork = -1;
    private static boolean busy;
    private AutoRegion() {}

    static synchronized void enter(Context supplied) {
        checkedGeneration = -1;
        ensure(supplied);
    }

    static synchronized void ensure(Context supplied) {
        if (!Prefs.automaticRegion() || busy) return;
        long now = System.currentTimeMillis(), generation = Prefs.regionGeneration();
        boolean changed = generation != checkedGeneration;
        if (!changed && now >= checkedAt && now - checkedAt < 30000) return;
        checkedGeneration = generation; checkedAt = now;
        Context context = supplied.getApplicationContext();
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = physical(manager);
        if (network == null) return;
        if (!changed && now >= attemptedAt && now - attemptedAt < 5 * 60000
                && attemptedNetwork == network.getNetworkHandle()) return;
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        long previous = prefs.getLong("region_auto_success", 0);
        if (!changed && now >= previous && now - previous < 24 * 60 * 60000L
                && prefs.getLong("region_auto_network", -1) == network.getNetworkHandle()
                && prefs.getLong("region_auto_generation", -1) == generation) return;
        busy = true; attemptedAt = now; attemptedNetwork = network.getNetworkHandle();
        String language = Prefs.language();
        IO.execute(() -> {
            Result result = null;
            try (RegionVpnPause pause = RegionVpnPause.open(context)) {
                result = lookup(network, language, pause);
                if (pause.remainingMillis() == 0) throw new IOException("Region lookup timed out");
            }
            catch (IOException | org.json.JSONException | RuntimeException e) {
                AppLog.w("Automatic region unavailable: " + e.getClass().getSimpleName()
                        + ": " + String.valueOf(e.getMessage()));
            }
            final Result found = result;
            MAIN.post(() -> {
                try {
                    // Mode changes invalidate queued/in-flight results; saved manual coordinates stay untouched.
                    if (found != null && network.equals(physical(manager)) && Prefs.applyAutomaticCity(found.city, found.latitude, found.longitude, language, generation)) {
                        prefs.edit().putLong("region_auto_success", System.currentTimeMillis())
                                .putLong("region_auto_network", network.getNetworkHandle())
                                .putLong("region_auto_generation", generation).apply();
                        HomeData.get(context).refreshIfStale();
                    }
                } finally { synchronized (AutoRegion.class) { busy = false; } }
            });
        });
    }

    @SuppressWarnings("deprecation")
    static Network physical(ConnectivityManager manager) {
        if (manager == null) return null;
        Network best = null;
        int score = -1;
        try {
            Network active = manager.getActiveNetwork();
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue;
                boolean ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
                if (!ethernet && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                int candidate = (network.equals(active) ? 4 : 0) + (ethernet ? 2 : 1);
                if (candidate > score) { best = network; score = candidate; }
            }
        } catch (RuntimeException ignored) { return null; }
        return best;
    }

    static final class Result {
        final String city, latitude, longitude;
        Result(String city, double latitude, double longitude) {
            this.city = city; this.latitude = String.valueOf(latitude); this.longitude = String.valueOf(longitude);
        }
    }
    static Result parse(String body) throws org.json.JSONException, IOException {
        JSONObject json = new JSONObject(body);
        String city = json.optString("city", "").trim();
        double lat = json.optDouble("latitude", Double.NaN), lon = json.optDouble("longitude", Double.NaN);
        if (!json.optBoolean("success", false) || city.isEmpty() || city.length() > 200
                || !Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180)
            throw new IOException("Invalid region response");
        return new Result(city, lat, lon);
    }
    private static Result lookup(Network network, String language, RegionVpnPause pause) throws IOException, org.json.JSONException {
        URL url = new URL("https://ipwho.is/?fields=success,city,latitude,longitude&lang=" + ("ru".equals(language) ? "ru" : "en"));
        HttpURLConnection connection = (HttpURLConnection) network.openConnection(url, Proxy.NO_PROXY);
        try {
            int timeout = (int) Math.max(1, Math.min(8000, pause.remainingMillis() / 3));
            connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() != 200) throw new IOException("Region lookup failed");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (pause.remainingMillis() == 0) throw new IOException("Region lookup timed out");
                    if (body.size() + count > 65536) throw new IOException("Oversized region response");
                    body.write(buffer, 0, count);
                }
                return parse(body.toString(StandardCharsets.UTF_8.name()));
            }
        } finally { connection.disconnect(); }
    }
}
