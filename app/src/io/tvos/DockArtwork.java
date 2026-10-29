package io.tvos;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Locale;

/** Match published artwork to TMDB; never treat another service's numeric IDs as TMDB IDs. */
final class DockArtwork {
    private static final java.util.Map<String, DockArtwork> CACHE = new java.util.LinkedHashMap<>(16, .75f, true);
    String backdrop = "", logo = "";
    private String kind, cacheKey, languageCode;
    private int id;
    private boolean logoLoaded;

    static DockArtwork find(String title, String poster, String year) throws IOException, org.json.JSONException {
        DockArtwork result = lookup(title, poster, year, connection -> {});
        loadLogo(result, connection -> {});
        return result;
    }

    static DockArtwork lookup(String title, String poster, String year,
            java.util.function.Consumer<java.net.HttpURLConnection> opened) throws IOException, org.json.JSONException {
        DockArtwork result = new DockArtwork();
        if (!TmdbClient.hasKey() || title.isEmpty()) return result;
        AppLanguage requestedLanguage = AppLanguage.current();
        String language = requestedLanguage.apiTag;
        String key = language + "\n" + year + "\n" + poster + "\n" + title;
        synchronized (CACHE) { DockArtwork cached = CACHE.get(key); if (cached != null) return cached; }
        JSONArray items = TmdbClient.get("/search/multi?language=" + language + "&query="
                + URLEncoder.encode(title, "UTF-8") + "&include_adult=false", opened).optJSONArray("results");
        JSONObject match = match(items, title, poster, year);
        if (match == null) return result;
        result.backdrop = image(match.optString("backdrop_path", ""), "w1280");
        result.languageCode = requestedLanguage.code;
        result.kind = match.optString("media_type", ""); result.id = match.optInt("id"); result.cacheKey = key;
        return result;
    }

    static void loadLogo(DockArtwork result, java.util.function.Consumer<java.net.HttpURLConnection> opened) {
        if (result.logoLoaded || result.id <= 0) return;
        try {
            JSONObject images = TmdbClient.get("/" + result.kind + "/" + result.id
                    + "/images?include_image_language=" + result.languageCode + ",en,null", opened);
            result.logo = image(logo(images.optJSONArray("logos"), result.languageCode), "w500");
            result.logoLoaded = true;
            synchronized (CACHE) {
                CACHE.put(result.cacheKey, result);
                if (CACHE.size() > 16) CACHE.remove(CACHE.keySet().iterator().next());
            }
        } catch (IOException | org.json.JSONException e) {
            AppLog.w("Dock logo unavailable: " + e.getClass().getSimpleName());
        }
    }

    static JSONObject match(JSONArray items, String title, String poster, String year) {
        if (items == null) return null;
        JSONObject named = null; int names = 0;
        String normalized = normalize(title);
        String filename = filename(poster);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String kind = item.optString("media_type", "");
            if (!"movie".equals(kind) && !"tv".equals(kind)) continue;
            if (!filename.isEmpty() && filename.equals(filename(item.optString("poster_path", "")))) return item;
            String date = item.optString("movie".equals(kind) ? "release_date" : "first_air_date", "");
            if (!year.isEmpty() && !date.startsWith(year)) continue;
            String name = item.optString("movie".equals(kind) ? "title" : "name", "");
            String original = item.optString("movie".equals(kind) ? "original_title" : "original_name", "");
            if (normalized.equals(normalize(name)) || normalized.equals(normalize(original))) { named = item; names++; }
        }
        return names == 1 ? named : null;
    }

    static String logo(JSONArray items, String language) {
        String selected = ""; int best = -1;
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String path = item.optString("file_path", "");
            if (!path.endsWith(".png")) continue;
            String lang = item.optString("iso_639_1", "");
            int score = language.equals(lang) ? 3 : "en".equals(lang) ? 2 : (lang.isEmpty() || "null".equals(lang)) ? 1 : 0;
            if (score > best && score > 0) { best = score; selected = path; }
        }
        return selected;
    }

    static String image(String path, String size) {
        return path.startsWith("/") && !path.contains("..") && !path.contains("?")
                ? "https://image.tmdb.org/t/p/" + size + path : "";
    }
    private static String filename(String url) { int end = url.indexOf('?'); if (end >= 0) url = url.substring(0, end); return url.substring(url.lastIndexOf('/') + 1); }
    private static String normalize(String s) { return s.trim().toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").trim(); }
}
