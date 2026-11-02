package io.tvos.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** City search and coordinate-checked localization; caller selects languages and HTTP transport. */
public final class CityGeocoder {
    @FunctionalInterface
    public interface TextSource { String get(String url) throws Exception; }
    private final TextSource source;

    public CityGeocoder(TextSource source) { this.source = source; }

    /** Search order is used only for legacy locations without a stable ID. */
    public String[] localize(String name, String latitude, String longitude,
                             long id, String language, String[] searchLanguages) throws Exception {
        double lat = Double.parseDouble(latitude), lon = Double.parseDouble(longitude);
        if (id > 0) {
            JSONObject city = new JSONObject(source.get("https://geocoding-api.open-meteo.com/v1/get?id=" + id
                    + "&language=" + language));
            if (city.optString("name", "").isEmpty()
                    || Math.abs(city.getDouble("latitude") - lat) > 0.25
                    || Math.abs(city.getDouble("longitude") - lon) > 0.25) return null;
            return new String[] {city.getString("name"), Long.toString(id)};
        }
        // Existing installations have a name and coordinates but no GeoNames ID yet.
        String[] nearest = null;
        double distance = 0.25 * 0.25;
        // Search indexes may not match a Cyrillic query when English results are requested.
        // Discover the stable ID in either supported language, then translate through /get.
        for (String searchLanguage : searchLanguages) {
            for (String[] city : search(name, searchLanguage)) {
                double dy = Double.parseDouble(city[2]) - lat, dx = Double.parseDouble(city[3]) - lon;
                double candidate = dy * dy + dx * dx;
                if (candidate < distance) { distance = candidate; nearest = city; }
            }
            if (nearest != null) break;
        }
        if (nearest == null) return null;
        long foundId = Long.parseLong(nearest[4]);
        if (foundId > 0) return localize(name, latitude, longitude, foundId, language, searchLanguages);
        return language.equals(nearest[5]) ? new String[] {nearest[1], nearest[4]} : null;
    }

    public List<String[]> search(String name, String lang) throws Exception {
        JSONObject o = new JSONObject(source.get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            appendPart(label, r.optString("admin1", ""), city);
            appendPart(label, r.optString("country", ""), city);
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude")),
                    Long.toString(r.optLong("id", 0)), lang});
        }
        return out;
    }

    private static void appendPart(StringBuilder label, String part, String city) {
        if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
    }
}
