package io.tvos;

import android.os.Handler;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;

/** Interactive, bounded TMDB genre lists. Cache access is confined to the request worker. */
final class GenreCatalog {
    static final class Genre {
        final int id;
        final String name;
        Genre(int id, String name) { this.id = id; this.name = name; }
    }
    private static final long TTL = 6 * 3600_000L;
    private static final class Cached<T> {
        final T value;
        final long at = System.currentTimeMillis();
        Cached(T value) { this.value = value; }
        boolean fresh() { long age = System.currentTimeMillis() - at; return age >= 0 && age < TTL; }
    }
    private final TmdbSource source;
    private final ThreadPoolExecutor executor;
    private final Handler main;
    private final LinkedHashMap<String, Cached<List<Genre>>> genres = new LinkedHashMap<>();
    private final LinkedHashMap<String, Cached<List<Recs.Pick>>> movies = new LinkedHashMap<>(8, .75f, true);

    GenreCatalog(TmdbSource source, ThreadPoolExecutor executor, Handler main) {
        this.source = source; this.executor = executor; this.main = main;
    }
    UiRequest genres(AppLanguage language, Consumer<List<Genre>> callback) {
        return genres("movie", language, callback);
    }
    UiRequest genres(String kind, AppLanguage language, Consumer<List<Genre>> callback) {
        return submit(language, request -> {
            String cacheKey = kind + ":" + language.code;
            Cached<List<Genre>> old = genres.get(cacheKey);
            if (old != null && old.fresh()) return old.value;
            JSONArray items = source.get("/genre/" + kind + "/list?language=" + language.apiTag).getJSONArray("genres");
            if (!request.active()) return null;
            List<Genre> result = new ArrayList<>();
            HashSet<Integer> seen = new HashSet<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                int id = item.optInt("id");
                String name = item.optString("name", "").trim();
                if (id > 0 && !name.isEmpty() && seen.add(id)) {
                    name = name.substring(0, 1).toUpperCase(language.locale) + name.substring(1);
                    result.add(new Genre(id, name));
                }
            }
            Collator collator = Collator.getInstance(language.locale);
            result.sort((a, b) -> collator.compare(a.name, b.name));
            if (result.isEmpty()) throw new IOException("No TMDB genres");
            result = Collections.unmodifiableList(new ArrayList<>(result));
            genres.put(cacheKey, new Cached<>(result));
            return result;
        }, callback);
    }
    UiRequest movies(Genre genre, AppLanguage language, Consumer<List<Recs.Pick>> callback) {
        return titles("movie", genre, language, callback);
    }
    UiRequest titles(String kind, Genre genre, AppLanguage language, Consumer<List<Recs.Pick>> callback) {
        return submit(language, request -> {
            if (genre.id <= 0) throw new IOException("Invalid genre");
            String key = kind + ":" + language.code + ":" + genre.id;
            Cached<List<Recs.Pick>> old = movies.get(key);
            if (old != null && old.fresh()) return old.value;
            List<Recs.Pick> result = new ArrayList<>();
            HashSet<Integer> seen = new HashSet<>();
            for (int page = 1; page <= 2; page++) {
                if (!request.active()) return null;
                JSONObject response = source.get("/discover/" + kind + "?language=" + language.apiTag
                        + "&with_genres=" + genre.id + "&sort_by=vote_average.desc&vote_count.gte=200"
                        + "&include_adult=false" + ("movie".equals(kind) ? "&include_video=false" : "") + "&page=" + page);
                if (!request.active()) return null;
                JSONArray items = response.getJSONArray("results");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    JSONArray ids = item.optJSONArray("genre_ids");
                    boolean matches = false;
                    if (ids != null) for (int j = 0; j < ids.length(); j++) matches |= ids.optInt(j) == genre.id;
                    if (!matches || item.optBoolean("adult", false) || item.optInt("vote_count") < 200
                            || !seen.add(item.optInt("id"))) continue;
                    TmdbMetadata.add(result, item, kind);
                }
                if (page >= response.optInt("total_pages", 1)) break;
            }
            result.sort((a, b) -> { int rating = Double.compare(b.rating, a.rating); return rating != 0 ? rating : Integer.compare(a.id, b.id); });
            List<Recs.Pick> ready = Collections.unmodifiableList(new ArrayList<>(result.subList(0, Math.min(25, result.size()))));
            movies.put(key, new Cached<>(ready));
            while (movies.size() > 5) movies.remove(movies.keySet().iterator().next());
            return ready;
        }, callback);
    }
    UiRequest random(String kind, int genre, AppLanguage language, Consumer<Recs.Pick> callback) {
        return submit(language, request -> {
            String query = "/discover/" + kind + "?language=" + language.apiTag
                    + "&sort_by=popularity.desc&vote_average.gte=6.5&vote_count.gte=200&include_adult=false"
                    + ("movie".equals(kind) ? "&include_video=false" : "")
                    + (genre > 0 ? "&with_genres=" + genre : "");
            JSONObject first = source.get(query + "&page=1");
            if (!request.active()) return null;
            int pages = Math.max(1, Math.min(500, first.optInt("total_pages", 1)));
            int page = java.util.concurrent.ThreadLocalRandom.current().nextInt(pages) + 1;
            JSONObject response = page == 1 ? first : source.get(query + "&page=" + page);
            if (!request.active()) return null;
            List<Recs.Pick> eligible = new ArrayList<>();
            for (JSONObject candidate : new JSONObject[]{response, first}) {
                JSONArray items = candidate.getJSONArray("results");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (item.optDouble("vote_average", 0) <= 6.5 || item.optBoolean("adult", false)) continue;
                    boolean matches = genre <= 0;
                    JSONArray ids = item.optJSONArray("genre_ids");
                    if (ids != null) for (int j = 0; j < ids.length(); j++) matches |= ids.optInt(j) == genre;
                    if (matches) TmdbMetadata.add(eligible, item, kind);
                }
                if (!eligible.isEmpty()) break;
            }
            return eligible.isEmpty() ? null : eligible.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(eligible.size()));
        }, callback);
    }
    private interface RequestWork<T> { T fetch(UiRequest request) throws IOException, JSONException; }
    private <T> UiRequest submit(AppLanguage language, RequestWork<T> work, Consumer<T> callback) {
        UiRequest request = new UiRequest(executor);
        request.start(() -> {
            T result = null;
            try { if (request.active() && language == AppLanguage.current()) result = work.fetch(request); }
            catch (IOException | JSONException e) { AppLog.w("TMDB genres: " + e.getClass().getSimpleName()); }
            catch (RuntimeException e) { AppLog.unexpected("TMDB genres", e); }
            final T ready = result;
            if (request.active()) main.post(() -> {
                if (request.active() && language == AppLanguage.current()) callback.accept(ready);
            });
        });
        return request;
    }
}
