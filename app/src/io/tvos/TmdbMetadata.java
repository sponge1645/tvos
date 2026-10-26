package io.tvos;

import io.tvos.Recs.Pick;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** TMDB response mapping and display metadata; no network, executors, files or cache ownership. */
final class TmdbMetadata {
    private TmdbMetadata() { }

    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
    }
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** TV genres that TMDB returns in English even in pt-BR. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_ES = {
        {"Action & Adventure", "Acción y Aventura"}, {"Sci-Fi & Fantasy", "Ciencia ficción y Fantasía"},
        {"War & Politics", "Guerra y Política"}, {"Kids", "Infantil"}, {"News", "Noticias"},
        {"Soap", "Telenovela"}, {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_DE = {
        {"Action & Adventure", "Action & Abenteuer"}, {"War & Politics", "Krieg & Politik"}, {"Kids", "Kinder"},
        {"News", "Nachrichten"}, {"Soap", "Seifenoper"}, {"Talk", "Talkshow"},
    };

    static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.poster = o.optString("poster_path", "");
        p.rating = o.optDouble("vote_average", 0);
        if ("movie".equals(kind)) {
            String date = o.optString("release_date", "");
            if (date.length() >= 4) p.movieOrigin = date.substring(0, 4);
        }
        if ("tv".equals(kind)) {
            String firstAir = o.optString("first_air_date", "");
            String year = firstAir.length() >= 4 ? firstAir.substring(0, 4) : "";
            JSONArray countries = o.optJSONArray("origin_country");
            String country = "";
            if (countries != null && countries.length() > 0) {
                String code = countries.optString(0, "");
                if (code.length() == 2) {
                    country = countryName(code);
                }
            }
            p.tvOrigin = country.isEmpty() ? (year.isEmpty() ? "" : year) : year.isEmpty() ? country : year + " · " + country;
        }
        list.add(p);
    }

    static String countryName(String code) {
        if (code == null || code.length() != 2) return "";
        code = code.toUpperCase(Locale.ROOT);
        if (Ui.ru) {
            if ("US".equals(code)) return "США";
            if ("GB".equals(code)) return "Великобритания";
            if ("ZA".equals(code)) return "ЮАР";
            if ("KR".equals(code)) return "Южная Корея";
        } else {
            if ("US".equals(code)) return "USA";
            if ("GB".equals(code)) return "UK";
        }
        String name = new Locale("", code).getDisplayCountry(Ui.ru ? new Locale("ru") : Locale.ENGLISH);
        return name.equalsIgnoreCase(code) ? "" : name;
    }

    static String movieOrigin(JSONObject details, String fallback) {
        String release = details.optString("release_date", "");
        String year = release.length() >= 4 ? release.substring(0, 4) : "";
        if (year.isEmpty() && fallback != null) {
            // Cached fallback may use either "Country, YYYY г." or "YYYY г., Country".
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}").matcher(fallback);
            if (m.find()) year = m.group();
        }
        String country = "";
        JSONArray countries = details.optJSONArray("production_countries");
        if (countries != null && countries.length() > 0) {
            String code = countries.optJSONObject(0) == null ? "" : countries.optJSONObject(0).optString("iso_3166_1", "");
            if (code.length() == 2) {
                country = countryName(code);
            }
        }
        return country.isEmpty() ? year : year.isEmpty() ? country : year + " · " + country;
    }

    private static String genre(String name) {
        if (Ui.ru) return Russian.text(name);
        for (String[] g : Ui.es ? GENRES_ES : Ui.de ? GENRES_DE : GENRES_PT) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    private static String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] s : SERVICES) {
            if (s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0])) return s;
        }
        return null;
    }

    static void applyDetails(Pick p, JSONObject o, String region,
                             java.util.function.Predicate<String> installed) throws org.json.JSONException {
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        p.rating = o.optDouble("vote_average", p.rating);
        String poster = o.optString("poster_path", "");
        if (!poster.isEmpty() && !"null".equals(poster)) p.poster = poster;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            p.movieOrigin = movieOrigin(o, p.movieOrigin);
            int minutes = o.optInt("runtime");
            p.duration = minutes > 0 ? (Ui.ru ? minutes + " мин" : minutes + " min") : "";
            if (minutes > 0 && Ui.ru) parts.add(minutes >= 60 ? (minutes / 60) + " ч. " + (minutes % 60) + " мин" : minutes + " мин");
            else if (minutes > 0 && Ui.en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            // Discover metadata can be incomplete; details provide the original country and premiere year.
            String air = o.optString("first_air_date", "");
            String year = air.length() >= 4 ? air.substring(0, 4) : "";
            JSONArray origins = o.optJSONArray("origin_country");
            String country = "";
            if (origins != null && origins.length() > 0) {
                String code = origins.optString(0, "");
                if (code.length() == 2) {
                    country = countryName(code);
                }
            }
            if (!year.isEmpty() || !country.isEmpty())
                p.tvOrigin = country.isEmpty() ? year : year.isEmpty() ? country : year + " · " + country;
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && Ui.ru) parts.add(Russian.count(seasons, "сезон", "сезона", "сезонов"));
            else if (seasons > 0 && Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0 && Ui.de) parts.add(seasons == 1 ? "1 Staffel" : seasons + " Staffeln");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(region);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed.test(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    static Pick search(JSONObject item) {
        String kind = item.optString("media_type");
        if (!"movie".equals(kind) && !"tv".equals(kind)) return null;
        if (item.optString("poster_path", "").isEmpty()) return null;
        Pick p = new Pick();
        p.id = item.optInt("id");
        p.kind = kind;
        p.title = item.optString("movie".equals(kind) ? "title" : "name");
        p.originalTitle = item.optString("movie".equals(kind) ? "original_title" : "original_name", "");
        p.poster = item.optString("poster_path", "");
        p.backdrop = item.optString("backdrop_path", "");
        p.rating = item.optDouble("vote_average", 0);
        String date = item.optString("movie".equals(kind) ? "release_date" : "first_air_date", "");
        String year = date.length() >= 4 ? date.substring(0, 4) : "";
        if ("movie".equals(kind)) p.movieOrigin = year;
        else p.tvOrigin = year;
        return p;
    }
}
