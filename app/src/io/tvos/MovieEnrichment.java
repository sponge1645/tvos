package io.tvos;

import io.tvos.Recs.Pick;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Shared movie runtime/country enrichment; background fetch with guarded UI-thread publication. */
final class MovieEnrichment {
    private final Executor durationIo;
    private final Consumer<Runnable> dispatch;
    private final TmdbSource source;
    private final Runnable changed;
    MovieEnrichment(Executor worker, Consumer<Runnable> dispatch, TmdbSource source, Runnable changed) {
        this.durationIo = worker; this.dispatch = dispatch; this.source = source; this.changed = changed;
    }
    void resetAttempts() { synchronized (durationRequested) { durationLastAttempt.clear(); } }
    private static String key(Pick p) { return Recs.key(p); }
    private final java.util.Set<String> durationRequested = new java.util.HashSet<>();
    private final java.util.Map<String, Long> durationLastAttempt = new java.util.HashMap<>();

    private final java.util.Map<String, List<Pick>> durationWaiters = new java.util.HashMap<>();
    private final java.util.Map<String, JSONObject> durationResults = new java.util.LinkedHashMap<String, JSONObject>(16, .75f, true) {
        private static final long serialVersionUID = 1L;
        @Override protected boolean removeEldestEntry(Map.Entry<String, JSONObject> entry) {
            return size() > 128;
        }
    };

    void request(Pick p) {
        if (p == null || !"movie".equals(p.kind)) return;
        final String language = Ui.language;
        final String id = language + ":" + key(p);
        synchronized (durationRequested) {
            JSONObject cached = durationResults.get(id);
            if (cached != null) applyMovieMetadata(p, cached);
            if (p.duration != null && !p.duration.isEmpty()
                    && p.movieOrigin != null && p.movieOrigin.contains(" · ")) return;
            if (durationRequested.contains(id)) {
                List<Pick> waiting = durationWaiters.get(id);
                if (waiting != null && !waiting.contains(p)) waiting.add(p);
                return;
            }
            Long last = durationLastAttempt.get(id);
            if (last != null && System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return;
            durationRequested.add(id);
            List<Pick> waiting = new ArrayList<>();
            waiting.add(p);
            durationWaiters.put(id, waiting);
            durationLastAttempt.put(id, System.currentTimeMillis());
        }
        durationIo.execute(() -> {
            JSONObject result = null;
            boolean fallbackFailed = false;
            try {
                result = source.get("/movie/" + p.id + "?language=" + AppLanguage.from(language).apiTag);
                if (result.optInt("runtime", 0) <= 0 || result.optJSONArray("production_countries") == null
                        || result.optJSONArray("production_countries").length() == 0) {
                    try {
                        JSONObject fallback = source.get("/movie/" + p.id + "?language=en-US");
                        if (result.optInt("runtime", 0) <= 0) result.put("runtime", fallback.optInt("runtime", 0));
                        if (result.optJSONArray("production_countries") == null
                                || result.optJSONArray("production_countries").length() == 0)
                            result.put("production_countries", fallback.optJSONArray("production_countries"));
                    } catch (IOException | org.json.JSONException e) {
                        // Keep usable fields from the first response if the optional fallback fails.
                        fallbackFailed = true;
                        AppLog.w("movie metadata fallback: " + e.getMessage());
                    }
                }
            } catch (IOException | org.json.JSONException e) {
                AppLog.w("movie metadata: " + e.getMessage());
            } catch (RuntimeException e) {
                AppLog.unexpected("movie metadata", e);
            }
            final JSONObject details = result;
            final boolean retrySoon = fallbackFailed;
            dispatch.accept(() -> {
                synchronized (durationRequested) {
                    List<Pick> waiting = durationWaiters.remove(id);
                    durationRequested.remove(id);
                    if (details == null || retrySoon) durationLastAttempt.put(id,
                            System.currentTimeMillis() - 24 * 60 * 60 * 1000L + 30_000L);
                    if (!language.equals(Ui.language) || details == null) return;
                    durationResults.put(id, details);
                    if (waiting != null) for (Pick pick : waiting) applyMovieMetadata(pick, details);
                }
                changed.run();
            });
        });
    }
    private static void applyMovieMetadata(Pick p, JSONObject details) {
        int minutes = details.optInt("runtime", 0);
        if (minutes > 0) p.duration = String.format(Ui.locale(), AppStrings.get(R.string.runtime_minutes), minutes);
        String origin = TmdbMetadata.movieOrigin(details, p.movieOrigin);
        if (!origin.isEmpty()) p.movieOrigin = origin;
    }
}
