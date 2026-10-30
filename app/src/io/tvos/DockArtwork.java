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
        JSONObject match = search(title, poster, year, language, opened);
        // Providers may keep Russian titles/posters even when the launcher requests English metadata.
        if (match == null && !"ru".equals(requestedLanguage.code) && hasCyrillic(title))
            match = search(title, poster, year, "ru-RU", opened);
        if (match == null) return result;
        result.backdrop = image(match.optString("backdrop_path", ""), "w1280");
        result.languageCode = requestedLanguage.code;
        result.kind = match.optString("media_type", ""); result.id = match.optInt("id"); result.cacheKey = key;
        return result;
    }

    private static boolean hasCyrillic(String title) {
        for (int i = 0; i < title.length(); i++)
            if (Character.UnicodeBlock.of(title.charAt(i)) == Character.UnicodeBlock.CYRILLIC) return true;
        return false;
    }

    private static JSONObject search(String title, String poster, String year, String language,
            java.util.function.Consumer<java.net.HttpURLConnection> opened) throws IOException, org.json.JSONException {
        String[] variants = titles(title);
        for (int i = variants.length - 1; i >= 0; i--) {
            JSONArray items = TmdbClient.get("/search/multi?language=" + language + "&query="
                    + URLEncoder.encode(variants[i], "UTF-8") + "&include_adult=false", opened).optJSONArray("results");
            JSONObject matched = match(items, title, poster, year);
            if (matched != null) return matched;
        }
        return null;
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
        String[] normalized = titles(title);
        for (int i = 0; i < normalized.length; i++) normalized[i] = normalize(normalized[i]);
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
            for (String variant : normalized) {
                if (variant.equals(normalize(name)) || variant.equals(normalize(original))) { named = item; names++; break; }
            }
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
    private static String[] titles(String title) {
        String[] parts = title.trim().split("\\s+/\\s+", 2);
        if (parts.length == 2 && (parts[0].length() < 2 || parts[1].length() < 2)) return new String[]{title};
        return parts;
    }

    private static String filename(String url) { int end = url.indexOf('?'); if (end >= 0) url = url.substring(0, end); return url.substring(url.lastIndexOf('/') + 1); }
    private static String normalize(String s) { return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).trim().toLowerCase(Locale.ROOT).replace('\u0451', '\u0435').replaceAll("[\\p{Punct}\\s]+", " ").trim(); }
}
