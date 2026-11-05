package io.tvos;

import io.tvos.network.ConnectionObserver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recover missing publisher years by exact KinoPub ID, never by title ranking. */
final class KinoPubArtworkMetadata {
    private KinoPubArtworkMetadata() { }
    private static final Pattern YEAR = Pattern.compile("\\(([12][0-9]{3})\\)\\s*$");
    private static final Map<String, String> YEARS = new LinkedHashMap<>(64, .75f, true);

    static String year(String key, String title, String original, ConnectionObserver observer)
            throws IOException, JSONException {
        if (key == null || !key.matches("kinopub:[1-9][0-9]{0,9}")) return "";
        synchronized (YEARS) { String cached = YEARS.get(key); if (cached != null) return cached; }
        long id = Long.parseLong(key.substring("kinopub:".length()));
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (original != null && !original.trim().isEmpty()) queries.add(original.trim());
        if (title != null && !title.trim().isEmpty()) queries.add(title.trim());
        for (String query : queries) {
            if (query.length() > 512) continue;
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "https://api.kinopub.link/v1.1/autocomplete?query=" + URLEncoder.encode(query, "UTF-8")).openConnection();
            String found = readYear(connection, id, observer);
            if (found.isEmpty()) continue;
            synchronized (YEARS) {
                YEARS.put(key, found);
                if (YEARS.size() > 64) YEARS.remove(YEARS.keySet().iterator().next());
            }
            return found;
        }
        return "";
    }

    static String readYear(HttpURLConnection connection, long id, ConnectionObserver observer)
            throws IOException, JSONException {
        try {
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            observer.opened(connection);
            if (connection.getResponseCode() != 200) throw new IOException("KinoPub metadata HTTP failure");
            long deadline = System.nanoTime() + 12000000000L;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (System.nanoTime() > deadline || out.size() + count > 131072)
                        throw new IOException("KinoPub metadata response limit");
                    out.write(buffer, 0, count);
                }
                return matchYear(new JSONArray(out.toString("UTF-8")), id);
            }
        } finally {
            try { connection.disconnect(); }
            finally { observer.closed(connection); }
        }
    }

    static String matchYear(JSONArray items, long id) {
        String found = "";
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !item.optString("id", "").equals(Long.toString(id))) continue;
            Matcher year = YEAR.matcher(item.optString("value", ""));
            if (!year.find()) continue;
            if (!found.isEmpty() && !found.equals(year.group(1))) return "";
            found = year.group(1);
        }
        return found;
    }
}
