package io.tvos.catalog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Interactive TMDB query policy; scheduling, cancellation ownership and failure reporting stay with the caller. */
public final class TmdbQueries<T> {
    @FunctionalInterface
    public interface Source { JSONObject get(String path) throws IOException, JSONException; }
    public interface Mapper<T> {
        T search(JSONObject item);
        void addMovie(List<T> out, JSONObject item);
        String key(T item);
    }

    private static final Pattern WORDS = Pattern.compile("[\\p{L} ]+");
    private final Source source;
    private final Supplier<String> language;
    private final Mapper<T> mapper;
    private final Supplier<Random> random;

    public TmdbQueries(Source source, Supplier<String> language, Mapper<T> mapper) {
        this(source, language, mapper, Random::new);
    }

    public TmdbQueries(Source source, Supplier<String> language, Mapper<T> mapper, Supplier<Random> random) {
        this.source = source;
        this.language = language;
        this.mapper = mapper;
        this.random = random;
    }

    /** Rated >= 7 and >= 1000 votes; stop after at most five discover requests. */
    public void acclaimed(List<T> result, BooleanSupplier active) throws IOException, JSONException {
        Random pages = random.get();
        Set<Integer> seen = new HashSet<>();
        for (int attempt = 0; attempt < 5 && result.size() < 36; attempt++) {
            if (!active.getAsBoolean()) return;
            int page = 1 + pages.nextInt(15);
            JSONObject response = source.get("/discover/movie?language=" + language.get()
                    + "&sort_by=vote_average.desc&vote_count.gte=1000"
                    + "&vote_average.gte=7&include_adult=false&page=" + page);
            if (!active.getAsBoolean()) return;
            JSONArray items = response.optJSONArray("results");
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || !seen.add(item.optInt("id"))) continue;
                mapper.addMovie(result, item);
            }
        }
        Collections.shuffle(result);
    }

    /** Up to two pages per candidate; suffix variants run only if previous candidates found no cards. */
    public void search(String query, List<T> result, BooleanSupplier active) throws IOException, JSONException {
        LinkedHashMap<String, T> found = new LinkedHashMap<>();
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String q = query.trim();
        queries.add(q);
        if (q.length() >= 4 && WORDS.matcher(q).matches()) {
            queries.add(q + "ы");
            queries.add(q + "и");
            queries.add(q + "а");
            queries.add(q + "ов");
        }
        try {
            for (String candidate : queries) {
                if (!active.getAsBoolean()) return;
                if (!found.isEmpty()) break;
                String encoded = URLEncoder.encode(candidate, "UTF-8");
                for (int page = 1; page <= 2; page++) {
                    if (!active.getAsBoolean()) return;
                    JSONObject response = source.get("/search/multi?language=" + language.get()
                            + "&include_adult=false&query=" + encoded + "&page=" + page);
                    if (!active.getAsBoolean()) return;
                    JSONArray items = response.optJSONArray("results");
                    if (items == null) break;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) continue;
                        T card = mapper.search(item);
                        if (card != null) found.put(mapper.key(card), card);
                    }
                    if (page >= response.optInt("total_pages", 1)) break;
                }
            }
        } finally {
            // Preserve already decoded cards on transport/metadata failures; cancelled work is never delivered.
            if (active.getAsBoolean()) result.addAll(found.values());
        }
    }
}
