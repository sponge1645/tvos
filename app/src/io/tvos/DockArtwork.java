package io.tvos;

import org.json.JSONArray;
import io.tvos.network.ConnectionObserver;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Locale;

/** Match published artwork to TMDB; never treat another service's numeric IDs as TMDB IDs. */
final class DockArtwork {
    private static final java.util.Map<String, DockArtwork> CACHE = new java.util.LinkedHashMap<>(16, .75f, true);
    String backdrop = "";
    volatile String logo = "";
    volatile String logoStatus = "not-requested";
    private String kind, languageCode, originalLanguage = "";
    private int id;
    private volatile boolean logoLoaded;

    static DockArtwork lookup(String title, String poster, String year,
            ConnectionObserver observer) throws IOException, org.json.JSONException {
        return lookup(title, poster, year, "", observer);
    }

    static DockArtwork lookup(String title, String poster, String year, String expectedKind,
            ConnectionObserver observer) throws IOException, org.json.JSONException {
        DockArtwork result = new DockArtwork();
        if (!TmdbClient.hasKey() || title.isEmpty()) return result;
        AppLanguage requestedLanguage = AppLanguage.current();
        String language = requestedLanguage.apiTag;
        String key = expectedKind + "\n" + language + "\n" + year + "\n" + poster + "\n" + title;
        synchronized (CACHE) { DockArtwork cached = CACHE.get(key); if (cached != null) return cached; }
        JSONObject match = search(title, poster, year, expectedKind, language, observer);
        // Providers may keep Russian titles/posters even when the launcher requests English metadata.
        if (match == null && !"ru".equals(requestedLanguage.code) && hasCyrillic(title))
            match = search(title, poster, year, expectedKind, "ru-RU", observer);
        // An English provider title can differ from both the localized Russian
        // name and the original Korean/Japanese title. Resolve in English too.
        if (match == null && !"en".equals(requestedLanguage.code) && !hasCyrillic(title))
            match = search(title, poster, year, expectedKind, "en-US", observer);
        if (match == null) return result;
        result.backdrop = image(match.optString("backdrop_path", ""), "w1280");
        result.languageCode = requestedLanguage.code;
        String original = match.optString("original_language", "");
        if (original.matches("[a-z]{2,3}")) result.originalLanguage = original;
        result.kind = match.optString("media_type", ""); result.id = match.optInt("id");
        synchronized (CACHE) {
            CACHE.put(key, result);
            if (CACHE.size() > 16) CACHE.remove(CACHE.keySet().iterator().next());
        }
        return result;
    }

    private static boolean hasCyrillic(String title) {
        for (int i = 0; i < title.length(); i++)
            if (Character.UnicodeBlock.of(title.charAt(i)) == Character.UnicodeBlock.CYRILLIC) return true;
        return false;
    }

    private static JSONObject search(String title, String poster, String year, String expectedKind, String language,
            ConnectionObserver observer) throws IOException, org.json.JSONException {
        String[] variants = titles(title);
        for (int i = variants.length - 1; i >= 0; i--) {
            JSONArray items = TmdbClient.get("/search/multi?language=" + language + "&query="
                    + URLEncoder.encode(variants[i], "UTF-8") + "&include_adult=false", observer).optJSONArray("results");
            JSONObject matched = match(items, title, poster, year, expectedKind);
            // Provider type is a hint: an unambiguous title/year remains usable
            // when a publisher has mislabeled a series as a movie.
            if (matched == null && !expectedKind.isEmpty()) matched = match(items, title, poster, year, "");
            StringBuilder trace = new StringBuilder("Artwork search: title=").append(title.replace('\n', ' '))
                    .append(", year=").append(year).append(", kind=").append(expectedKind)
                    .append(", language=").append(language).append(", selected=")
                    .append(matched == null ? 0 : matched.optInt("id")).append(", candidates=");
            if (items != null) for (int n = 0; n < Math.min(10, items.length()); n++) {
                JSONObject candidate = items.optJSONObject(n);
                if (candidate == null) continue;
                trace.append('[').append(candidate.optInt("id")).append('|')
                        .append(candidate.optString("media_type", "")).append('|')
                        .append(candidate.optString("title", candidate.optString("name", "")).replace('\n', ' ')).append('|')
                        .append(candidate.optString("original_title", candidate.optString("original_name", "")).replace('\n', ' ')).append('|')
                        .append(candidate.optString("release_date", candidate.optString("first_air_date", ""))).append(']');
            }
            AppLog.w(trace.toString());
            if (matched != null) return matched;
        }
        return null;
    }

    static void loadLogo(DockArtwork result, ConnectionObserver observer) {
        if (result.logoLoaded) return;
        if (result.id <= 0) { result.logoStatus = "no-metadata-match"; return; }
        try {
            JSONObject images = TmdbClient.get("/" + result.kind + "/" + result.id
                    + "/images?include_image_language=" + result.languageCode + ",en,null"
                    + (result.originalLanguage.isEmpty() ? "" : "," + result.originalLanguage), observer);
            result.logo = image(logo(images.optJSONArray("logos"), result.languageCode, result.originalLanguage), "w500");
            JSONArray logos = images.optJSONArray("logos");
            result.logoStatus = !result.logo.isEmpty() ? "selected"
                    : logos == null || logos.length() == 0 ? "no-logos" : "no-supported-language-or-format";
            result.logoLoaded = true;
        } catch (IOException | org.json.JSONException e) {
            result.logoStatus = "request-failed";
            AppLog.w("Dock logo unavailable: " + e.getClass().getSimpleName());
        }
    }

    static JSONObject match(JSONArray items, String title, String poster, String year) {
        return match(items, title, poster, year, "");
    }

    static JSONObject match(JSONArray items, String title, String poster, String year, String expectedKind) {
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
            if (!expectedKind.isEmpty() && !expectedKind.equals(kind)) continue;
            String date = item.optString("movie".equals(kind) ? "release_date" : "first_air_date", "");
            if (!year.isEmpty() && !date.startsWith(year)) continue;
            String name = item.optString("movie".equals(kind) ? "title" : "name", "");
            String original = item.optString("movie".equals(kind) ? "original_title" : "original_name", "");
            for (String variant : normalized) {
                if (!variant.isEmpty() && (variant.equals(normalize(name)) || variant.equals(normalize(original)))) {
                    named = item; names++; break;
                }
            }
        }
        return names == 1 ? named : null;
    }

    static String logo(JSONArray items, String language) {
        return logo(items, language, "");
    }

    static String logo(JSONArray items, String language, String originalLanguage) {
        String selected = ""; int best = -1;
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String path = item.optString("file_path", "");
            if (!path.endsWith(".png")) continue;
            String lang = item.optString("iso_639_1", "");
            int score = language.equals(lang) ? 4 : "en".equals(lang) ? 3 : (!originalLanguage.isEmpty() && originalLanguage.equals(lang)) ? 2 : (lang.isEmpty() || "null".equals(lang)) ? 1 : 0;
            if (score > best && score > 0) { best = score; selected = path; }
        }
        return selected;
    }

    static String image(String path, String size) {
        return path.startsWith("/") && !path.contains("..") && !path.contains("?")
                ? "https://image.tmdb.org/t/p/" + size + path : "";
    }
    private static String[] titles(String title) {
        String clean = title.trim();
        String[] parts = clean.split("\\s*/\\s*", 2);
        if (parts.length == 2 && (parts[0].length() < 2 || parts[1].length() < 2)) parts = new String[]{clean};
        for (int i = 0; i < parts.length; i++) {
            // A provider's display label may end with a parenthesized year.
            parts[i] = parts[i].replaceFirst("\\s*[\\(\\[]([12][0-9]{3})[\\)\\]]\\s*$", "").trim();
        }
        return parts;
    }

    private static String filename(String url) { int end = url.indexOf('?'); if (end >= 0) url = url.substring(0, end); return url.substring(url.lastIndexOf('/') + 1); }
    private static String normalize(String s) { return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC).trim().toLowerCase(Locale.ROOT).replace('\u0451', '\u0435').replaceAll("[\\p{Punct}\\s]+", " ").trim(); }
}
