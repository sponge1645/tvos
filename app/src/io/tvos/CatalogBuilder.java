package io.tvos;

import io.tvos.Recs.Pick;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/** Build category lists without owning the published catalog or refresh lifecycle. */
final class CatalogBuilder {
    static final class Result {
        final List<Pick> popular, fresh, detectives;
        Result(List<Pick> popular, List<Pick> fresh, List<Pick> detectives) {
            this.popular = popular; this.fresh = fresh; this.detectives = detectives;
        }
    }
    private final TmdbSource source;
    private final IntFunction<String> date;
    CatalogBuilder(TmdbSource source) { this(source, CatalogBuilder::day); }
    CatalogBuilder(TmdbSource source, IntFunction<String> date) { this.source = source; this.date = date; }
    private static String key(Pick p) { return Recs.key(p); }
    private static void add(List<Pick> list, JSONObject o, String kind) { TmdbMetadata.add(list, o, kind); }
    Result fetch(String language, String listLanguage, List<Pick> detectivePicks) throws IOException, org.json.JSONException {
        String apiLanguage = AppLanguage.from(language).apiTag;
        // Popularity is TMDB's global popularity score, filtered by Russian release context.
        // It is NOT a measurement of viewing figures in Russia.
        List<Pick> popular = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {"movie", "tv"}) {
            for (int page = 1; page <= 2; page++) {
                String path = "/discover/" + kind + "?language=" + apiLanguage + "&region=RU"
                        + "&sort_by=popularity.desc&include_adult=false&page=" + page;
                if (!language.equals(Ui.language)) return null;
                JSONArray results = source.get(path).getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    List<Pick> one = new ArrayList<>();
                    add(one, results.getJSONObject(i), kind);
                    if (!one.isEmpty() && seen.add(key(one.get(0)))) popular.add(one.get(0));
                }
            }
        }
        if (popular.isEmpty()) throw new IOException("TMDB returned no popular titles");

        // Recent releases: last 180 days, at least 200 votes, ranked by popularity.
        // Separate movie and TV queries are merged into one ranked showcase.
        List<Pick> fresh = new ArrayList<>();
        java.util.Set<String> freshSeen = new java.util.HashSet<>();
        String from = date.apply(-180), to = date.apply(0);
        for (String kind : new String[] {"movie", "tv"}) {
            String dateField = "movie".equals(kind) ? "primary_release_date" : "first_air_date";
            for (int page = 1; page <= 4; page++) {
                String path = "/discover/" + kind + "?language=" + apiLanguage + "&region=RU"
                        + "&sort_by=popularity.desc&vote_count.gte=200&include_adult=false"
                        + "&" + dateField + ".gte=" + from + "&" + dateField + ".lte=" + to
                        + "&page=" + page;
                if (!language.equals(Ui.language)) return null;
                JSONArray results = source.get(path).getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject o = results.getJSONObject(i);
                    String date = o.optString("movie".equals(kind) ? "release_date" : "first_air_date", "");
                    if (date.compareTo(from) < 0 || date.compareTo(to) > 0) continue;
                    if (o.optDouble("vote_average", 0) < 6.0 || o.optInt("vote_count", 0) < 200) continue;
                    List<Pick> one = new ArrayList<>();
                    add(one, o, kind);
                    if (!one.isEmpty() && freshSeen.add(key(one.get(0)))) fresh.add(one.get(0));
                }
            }
        }
        // The discover API returns each type ordered by TMDB popularity.
        // Interleave movies and series so one type cannot monopolize New Releases.
        List<Pick> freshMovies = new ArrayList<>();
        List<Pick> freshShows = new ArrayList<>();
        for (Pick item : fresh) {
            if ("movie".equals(item.kind)) freshMovies.add(item);
            else freshShows.add(item);
        }
        List<Pick> mixed = new ArrayList<>();
        alternate(mixed, new java.util.HashSet<String>(), freshMovies, freshShows, 80);
        fresh = mixed;
        // Do not leave the landing tab blank if recent results are temporarily scarce.
        if (fresh.isEmpty()) {
            for (Pick pick : popular) if (fresh.size() < 12) fresh.add(pick);
        }
        // Mystery (TMDB genre 9648), movies only, rating strictly above 7.5.
        // An unavailable discover endpoint must not prevent the regular catalog refreshing.
        List<Pick> detectives = new ArrayList<>();
        try {
            java.util.Set<Integer> detectiveIds = new java.util.HashSet<>();
            for (int page = 1; page <= 5; page++) {
                if (!language.equals(Ui.language)) return null;
                JSONArray results = source.get("/discover/movie?language=" + apiLanguage
                        + "&with_genres=9648&vote_average.gte=7.5&vote_count.gte=200"
                        + "&sort_by=vote_average.desc&include_adult=false&page=" + page)
                        .getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.getJSONObject(i);
                    if (item.optDouble("vote_average", 0) <= 7.5
                            || item.optInt("vote_count", 0) < 200) continue;
                    JSONArray genres = item.optJSONArray("genre_ids");
                    boolean mystery = false;
                    if (genres != null) for (int j = 0; j < genres.length(); j++)
                        if (genres.optInt(j) == 9648) mystery = true;
                    if (!mystery || !detectiveIds.add(item.optInt("id"))) continue;
                    add(detectives, item, "movie");
                }
            }
        } catch (IOException | org.json.JSONException e) {
            if (language.equals(listLanguage)) detectives = new ArrayList<>(detectivePicks);
            AppLog.w("detective recommendations: " + e.getMessage());
        } catch (RuntimeException e) {
            AppLog.unexpected("detective recommendations", e);
            if (language.equals(listLanguage)) detectives = new ArrayList<>(detectivePicks);
        }
        if (!language.equals(Ui.language)) return null;
        return new Result(popular, fresh, detectives);
    }
    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }
}
