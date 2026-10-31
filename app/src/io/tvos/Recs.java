package io.tvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, originalTitle = "", backdrop, poster, meta = "", duration = "", tvOrigin = "", movieOrigin = "", app, pkg;
        String watchingKey, resumeUri;
        String watchingSeason = "", watchingEpisode = "";
        long watchingRemaining = -1, watchingUpdated;
        double rating;
        boolean detailed;
    }

    interface Listener { void onRecs(); }

    private static final long LIST_EVERY = 6 * 3600_000L;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final RecsStore store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Interactive queries must not wait behind catalog refresh and backdrop construction.
    private final java.util.concurrent.ThreadPoolExecutor requestIo = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final BackdropCache backdropCache;
    private final CatalogBuilder catalogBuilder;
    private final GenreCatalog genreCatalog;
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile List<Pick> newPicks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile long failedAt, keyStamp;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    static void languageChanged() {
        Recs current = instance;
        if (current != null) current.resetLanguage();
    }

    private synchronized void resetLanguage() {
        picks = new ArrayList<>();
        newPicks = new ArrayList<>();
        listLanguage = "";
        listAt = failedAt = keyStamp = 0;
        error = "";
        movieEnrichment.resetAttempts();
        notifyListeners();
        refreshIfStale();
    }

    private Recs(Context c) {
        context = c;
        posterCache = new PosterCache(c, this::notifyListeners);
        backdropCache = new BackdropCache(c, io, this::notifyListeners, () -> newPicks);
        catalogBuilder = new CatalogBuilder(this::get);
        genreCatalog = new GenreCatalog(this::get, requestIo, main);
        movieEnrichment = new MovieEnrichment(Executors.newSingleThreadExecutor(), main::post,
                this::get, () -> { save(); notifyListeners(); });
        store = new RecsStore(c.getSharedPreferences("recs", Context.MODE_PRIVATE));
        RecsStore.Snapshot saved = store.load();
        picks = saved.picks;
        newPicks = saved.newPicks;
        period = saved.period;
        listAt = saved.listAt;
        listLanguage = saved.listLanguage;
        keyStamp = saved.keyStamp;
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return TmdbClient.hasKey();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (IOException | org.json.JSONException e) {
            AppLog.w("recommendations status read: " + e.getClass().getSimpleName());
            return new JSONObject();
        } catch (RuntimeException e) {
            AppLog.unexpected("recommendations status read", e);
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException | org.json.JSONException e) {
            AppLog.w("recommendations status write: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            AppLog.unexpected("recommendations status write", e);
        }
    }

    String error() { return error; }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        for (Pick p : picks) {
            if (kind == null || kind.equals(p.kind)) list.add(p);
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return TmdbMetadata.serviceName(p);
    }

    private final PosterCache posterCache;
    private final MovieEnrichment movieEnrichment;

    GenreCatalog genres() { return genreCatalog; }

    void requestDuration(Pick p) { movieEnrichment.request(p); }

    void setPosterWindow(List<Pick> window) {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Pick p : window) if (p != null && p.poster != null && !p.poster.isEmpty()) wanted.add(p.poster);
        posterCache.setWindow(wanted);
    }

    Bitmap poster(Pick p) { return p == null ? null : posterCache.poster(p.poster); }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) { return backdropCache.backdrop(p); }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        final String language = Ui.language;
        long stamp = sourceStamp();   // a new key, country or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language) || newPicks.isEmpty();
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !backdropCache.hasImage(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (!language.equals(Ui.language)) return;
                if (stale && !fetchList(p, language)) return;
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    if (!language.equals(Ui.language)) return;
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.backdrop != null) backdropCache.download(pick);
                    } catch (IOException | org.json.JSONException e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    } catch (RuntimeException e) {
                        AppLog.unexpected("recommendation title", e);
                        failures++;
                        last = e.getClass().getSimpleName();
                    }
                }
                if (!language.equals(Ui.language)) return;
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (IOException | org.json.JSONException e) {
                if (language.equals(Ui.language)) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations: " + error);
                }
            } catch (RuntimeException e) {
                AppLog.unexpected("recommendation refresh", e);
                if (language.equals(Ui.language)) {
                    error = e.getClass().getSimpleName();
                    failedAt = System.currentTimeMillis();
                }
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
                if (!language.equals(Ui.language)) main.post(this::refreshIfStale);
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) { backdropCache.setLight(value); }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (backdropCache.hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        for (Pick p : picks) {
            if (p.detailed && backdropCache.hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return backdropCache.hasImage(p) ? backdropCache.imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || !backdropCache.hasImage(p)) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.backdrop != null) backdropCache.download(p);
                    } catch (IOException | org.json.JSONException e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    } catch (RuntimeException e) {
                        AppLog.unexpected("recommendation title", e);
                        failures++;
                        last = e.getClass().getSimpleName();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private boolean fetchList(int p, String language) throws IOException, org.json.JSONException {
        CatalogBuilder.Result result = catalogBuilder.fetch(language);
        if (result == null) return false;
        synchronized (this) {
            if (!language.equals(Ui.language)) return false;
            picks = result.popular;
            newPicks = result.fresh;
            period = p;
            listAt = System.currentTimeMillis();
            listLanguage = language;
        }
        return true;
    }

    /** Critically acclaimed roulette candidates. TMDB discover approximation: rated >= 7,
     * at least 1000 votes, sorted by vote average. Not an official preset API endpoint. */
    UiRequest loadAcclaimed(java.util.function.Consumer<List<Pick>> callback) {
        UiRequest request = new UiRequest(requestIo);
        request.start(() -> {
            List<Pick> result = new ArrayList<>();
            try {
                java.util.Random random = new java.util.Random();
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                for (int attempt = 0; attempt < 5 && result.size() < 36; attempt++) {
                    if (!request.active()) return;
                    int page = 1 + random.nextInt(15);
                    JSONObject response = get("/discover/movie?language=" + lang()
                            + "&sort_by=vote_average.desc&vote_count.gte=1000"
                            + "&vote_average.gte=7&include_adult=false&page=" + page);
                    if (!request.active()) return;
                    JSONArray items = response.optJSONArray("results");
                    if (items == null) continue;
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null || !seen.add(item.optInt("id"))) continue;
                        add(result, item, "movie");
                    }
                }
                java.util.Collections.shuffle(result);
            } catch (IOException | org.json.JSONException e) {
                AppLog.w("random recommendations: " + e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                AppLog.unexpected("random recommendations", e);
            }
            if (request.active()) main.post(() -> {
                if (request.active()) callback.accept(result);
            });
        });
        return request;
    }

    /** TMDB multi-search, with a limited suffix fallback for incomplete Russian words. */
    UiRequest search(String query, java.util.function.Consumer<List<Pick>> callback) {
        UiRequest request = new UiRequest(requestIo);
        request.start(() -> {
            java.util.LinkedHashMap<String, Pick> found = new java.util.LinkedHashMap<>();
            java.util.LinkedHashSet<String> queries = new java.util.LinkedHashSet<>();
            queries.add(query.trim());
            // TMDB matches whole words rather than arbitrary Russian prefixes.
            // Only use suffix variants when the original query returns no results.
            String q = query.trim();
            if (q.length() >= 4 && q.matches("[\\p{L} ]+")) {
                queries.add(q + "ы");
                queries.add(q + "и");
                queries.add(q + "а");
                queries.add(q + "ов");
            }
            try {
                for (String candidate : queries) {
                    if (!request.active()) return;
                    if (!found.isEmpty()) break;
                    String encoded = URLEncoder.encode(candidate, "UTF-8");
                    for (int page = 1; page <= 2; page++) {
                        if (!request.active()) return;
                        JSONObject response = get("/search/multi?language=" + lang()
                                + "&include_adult=false&query=" + encoded + "&page=" + page);
                        if (!request.active()) return;
                        JSONArray items = response.optJSONArray("results");
                        if (items == null) break;
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.optJSONObject(i);
                            if (item == null) continue;
                            Pick p = TmdbMetadata.search(item);
                            if (p == null) continue;
                            found.put(p.kind + ":" + p.id, p);
                        }
                        if (page >= response.optInt("total_pages", 1)) break;
                    }
                }
            } catch (IOException | org.json.JSONException e) {
                android.util.Log.w("tvOS", "TMDB search failed", e);
            } catch (RuntimeException e) {
                AppLog.unexpected("TMDB search", e);
            }
            if (request.active()) main.post(() -> {
                if (request.active()) callback.accept(new ArrayList<>(found.values()));
            });
        });
        return request;
    }

    /** Ordered catalog for light home screen; unlike now(), not limited to three. */
    List<Pick> catalog(String kind) {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        for (Pick p : kind == null ? newPicks : picks) {
            if (kind == null || kind.equals(p.kind)) {
                out.add(p);
                if (out.size() >= 25) break;
            }
        }
        return out;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() { return AppLanguage.current().apiTag; }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        TmdbMetadata.add(list, o, kind);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws IOException, org.json.JSONException {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language + ",en");
        TmdbMetadata.applyDetails(p, o, REGION, this::installed);
    }

    /** Whether a provider has a launchable app on this TV. */
    private boolean installed(String pkg) {
        try {
            return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
        } catch (SecurityException e) {
            AppLog.w("recommendations provider lookup: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private long sourceStamp() {
        return (Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + Ui.language.hashCode();
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        return TmdbClient.get(path);
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        backdropCache.retain(keep);
    }

    // MARK: - List cache

    private void save() {
        store.save(picks, newPicks, period, listAt, listLanguage, keyStamp);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
