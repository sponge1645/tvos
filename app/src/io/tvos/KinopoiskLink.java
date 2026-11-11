package io.tvos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Exact TMDB identity to a Kinopoisk HD title URL, using JustWatch's public catalogue. */
final class KinopoiskLink {
    static final String PACKAGE = "ru.kinopoisk.tv";
    private static final String QUERY = "query($title:String!){popularTitles(country:RU,first:12,filter:{searchQuery:$title})"
            + "{edges{node{objectType content(country:RU,language:ru){externalIds{tmdbId}}"
            + "offers(country:RU,platform:WEB){standardWebURL}}}}}";
    private static final long TTL = 24L * 60 * 60 * 1000;
    private static final LinkedHashMap<String, Cached> CACHE = new LinkedHashMap<>(64, .75f, true);
    private static final class Cached {
        final String url; final long time;
        Cached(String url) { this.url = url; time = System.currentTimeMillis(); }
    }
    private KinopoiskLink() { }

    static String resolve(Recs.Pick pick, BooleanSupplier cancelled) throws IOException, JSONException {
        check(cancelled);
        String key = pick.kind + ":" + pick.id;
        synchronized (CACHE) {
            Cached cached = CACHE.get(key);
            if (cached != null && System.currentTimeMillis() - cached.time < TTL) return cached.url;
        }
        Set<String> titles = new LinkedHashSet<>();
        if (pick.title != null && !pick.title.trim().isEmpty()) titles.add(pick.title.trim());
        if (pick.originalTitle != null && !pick.originalTitle.trim().isEmpty()) titles.add(pick.originalTitle.trim());
        for (String title : titles) {
            check(cancelled);
            String url = select(search(title, cancelled), pick.kind, pick.id);
            if (url.isEmpty()) continue;
            synchronized (CACHE) {
                CACHE.put(key, new Cached(url));
                while (CACHE.size() > 64) CACHE.remove(CACHE.keySet().iterator().next());
            }
            return url;
        }
        return "";
    }

    static String canonical(String url) {
        return url != null && url.matches("https://hd\\.kinopoisk\\.ru/film/[0-9a-f]{32}/?(?:\\?[^#\\s]*)?")
                ? url.substring(0, "https://hd.kinopoisk.ru/film/".length() + 32) : "";
    }

    static String select(JSONObject response, String kind, int tmdbId) {
        String type = "movie".equals(kind) ? "MOVIE" : "tv".equals(kind) ? "SHOW" : "";
        if (type.isEmpty() || tmdbId <= 0) return "";
        JSONObject data = response.optJSONObject("data");
        JSONObject popular = data == null ? null : data.optJSONObject("popularTitles");
        JSONArray edges = popular == null ? null : popular.optJSONArray("edges");
        Set<String> links = new LinkedHashSet<>();
        if (edges != null) for (int i = 0; i < edges.length(); i++) {
            JSONObject edge = edges.optJSONObject(i), node = edge == null ? null : edge.optJSONObject("node");
            if (node == null || !type.equals(node.optString("objectType"))) continue;
            JSONObject content = node.optJSONObject("content");
            JSONObject ids = content == null ? null : content.optJSONObject("externalIds");
            if (ids == null || !String.valueOf(tmdbId).equals(ids.optString("tmdbId"))) continue;
            JSONArray offers = node.optJSONArray("offers");
            if (offers != null) for (int j = 0; j < offers.length(); j++) {
                JSONObject offer = offers.optJSONObject(j);
                String link = offer == null ? "" : canonical(offer.optString("standardWebURL"));
                if (!link.isEmpty()) links.add(link);
            }
        }
        return links.size() == 1 ? links.iterator().next() : "";
    }

    private static void check(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Title lookup cancelled");
    }

    private static JSONObject search(String title, BooleanSupplier cancelled) throws IOException, JSONException {
        byte[] body = new JSONObject().put("query", QUERY)
                .put("variables", new JSONObject().put("title", title)).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL("https://apis.justwatch.com/graphql").openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(body.length);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "tvOS/1.2");
            check(cancelled);
            try (java.io.OutputStream out = c.getOutputStream()) { out.write(body); }
            if (c.getResponseCode() != 200) throw new IOException("Title catalogue unavailable");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    check(cancelled);
                    if (bytes.size() + n > 512 * 1024) throw new IOException("Title response too large");
                    bytes.write(buffer, 0, n);
                }
                JSONObject response = new JSONObject(bytes.toString("UTF-8"));
                JSONArray errors = response.optJSONArray("errors");
                if (errors != null && errors.length() > 0) throw new IOException("Title catalogue query failed");
                return response;
            }
        } finally { c.disconnect(); }
    }
}
