package io.tvos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** One disposable random slot per current catalog; pending requests cannot outlive its context. */
final class RandomPoster {
    private String context = "";
    private Recs.Pick placeholder, chosen;
    private UiRequest request;
    private int generation;
    void clear() {
        generation++;
        if (request != null) request.cancel();
        request = null; placeholder = chosen = null; context = "";
    }
    Recs.Pick tail(String key, String kind) {
        if (!key.equals(context)) { clear(); context = key; }
        if (placeholder == null) {
            placeholder = new Recs.Pick();
            placeholder.id = -1; placeholder.kind = kind;
        }
        placeholder.title = AppStrings.get(request != null ? R.string.home_genre_loading
                : "tv".equals(kind) ? R.string.home_random_series : R.string.home_random_movie);
        return chosen == null ? placeholder : chosen;
    }
    boolean placeholder(Recs.Pick pick) { return pick != null && pick == placeholder; }
    boolean resetAway(int position, int tail) {
        if ((chosen != null || request != null) && position <= tail - 5) {
            generation++;
            if (request != null) request.cancel();
            chosen = null; request = null;
            return true;
        }
        return false;
    }
    void load(GenreCatalog catalog, String kind, int genre, List<Recs.Pick> recent,
            Runnable changed, Runnable failed) {
        if (request != null) return;
        final int token = ++generation;
        if (recent != null) {
            List<Recs.Pick> eligible = new ArrayList<>();
            for (Recs.Pick p : recent) if (p.id > 0 && p.rating > 6.5) eligible.add(p);
            if (eligible.isEmpty()) { failed.run(); return; }
            chosen = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
            changed.run(); return;
        }
        request = catalog.random(kind, genre, AppLanguage.current(), result -> {
            if (token != generation) return;
            request = null;
            if (result == null) failed.run();
            else { chosen = result; changed.run(); }
        });
        changed.run();
    }
}
