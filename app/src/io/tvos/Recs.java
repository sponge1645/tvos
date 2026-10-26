package io.tvos;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Hulu, Paramount+, Peacock,
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
        double rating;
        boolean detailed;
    }

    interface Listener { void onRecs(); }

    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 16; // Discard catalogs previously labeled English but fetched in Russian
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_RU = {"ЭТИМ УТРОМ", "СЕГОДНЯ ДНЁМ", "СЕГОДНЯ ВЕЧЕРОМ"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    static final String[] PERIOD_LABEL_DE = {"HEUTE MORGEN", "HEUTE NACHMITTAG", "HEUTE ABEND"};
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // Interactive queries must not wait behind catalog refresh and backdrop construction.
    private final java.util.concurrent.ThreadPoolExecutor requestIo = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>());
    // Poster disk reads must not wait behind full-resolution backdrop downloads.
    private final ExecutorService posterIo = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);   // access-order LRU: visible artwork survives
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile List<Pick> newPicks = new ArrayList<>();
    private volatile List<Pick> detectivePicks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    static void languageChanged() {
        Recs current = instance;
        if (current != null) current.resetLanguage();
    }

    private synchronized void resetLanguage() {
        picks = new ArrayList<>();
        newPicks = new ArrayList<>();
        detectivePicks = new ArrayList<>();
        listLanguage = "";
        listAt = failedAt = keyStamp = 0;
        error = "";
        synchronized (durationRequested) { durationLastAttempt.clear(); }
        notifyListeners();
        refreshIfStale();
    }

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            String savedNew = cache.getString("newPicks", null);
            if (savedNew != null) newPicks = parse(new JSONArray(savedNew));
            String savedDetectives = cache.getString("detectivePicks", null);
            if (savedDetectives != null) detectivePicks = parse(new JSONArray(savedDetectives));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (org.json.JSONException | ClassCastException e) {
            AppLog.w("recommendations cache read: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            AppLog.unexpected("recommendations cache read", e);
        }
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

    static String periodLabel() {
        return (Ui.ru ? PERIOD_LABEL_RU : Ui.es ? PERIOD_LABEL_ES : Ui.de ? PERIOD_LABEL_DE : Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)
                [period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
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

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
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

    private final Map<String, Bitmap> posters = new LinkedHashMap<>(48, 0.75f, true);
    private static final long POSTER_CACHE_MAX_BYTES = 200L * 1024 * 1024;
    private static final long POSTER_RAM_MAX_BYTES = 12L * 1024 * 1024;
    private final java.util.Set<String> posterPending = new java.util.HashSet<>();
    // One worker plus queued requests; enough for the current page and its prefetch window.
    private static final int POSTER_PENDING_MAX = 24;
    private static final long POSTER_RETRY_MS = 30_000L;
    private static final long POSTER_FILE_MAX_BYTES = 8L * 1024 * 1024;
    // Guarded by posters, including access-order mutations.
    private final Map<String, Long> posterFailures = new LinkedHashMap<>(32, 0.75f, true);
    private long posterRamBytes;
    // Null until a screen supplies a window. An empty window stops loading while Home is hidden.
    private java.util.Set<String> posterWindow;
    // Used only by the single poster worker.
    private boolean posterCacheChecked;
    private final ExecutorService durationIo = Executors.newSingleThreadExecutor();
    private final java.util.Set<String> durationRequested = new java.util.HashSet<>();
    private final java.util.Map<String, Long> durationLastAttempt = new java.util.HashMap<>();

    /** Discover omits runtime and movie production countries. Enrich visible cards asynchronously. */
    void requestDuration(Pick p) {
        if (p == null || !"movie".equals(p.kind)
                || ((p.duration != null && !p.duration.isEmpty())
                && (p.movieOrigin != null && p.movieOrigin.contains(" · ")))) return;
        final String language = Ui.language;
        final String id = language + ":" + key(p);
        synchronized (durationRequested) {
            if (durationRequested.contains(id)) return;
            Long last = durationLastAttempt.get(id);
            // TMDB may not yet have runtime/country for upcoming releases; retry at most daily.
            if (last != null && System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return;
            durationRequested.add(id);
            durationLastAttempt.put(id, System.currentTimeMillis());
        }
        durationIo.execute(() -> {
            try {
                JSONObject details = get("/movie/" + p.id + "?language=" + lang());
                int minutes = details.optInt("runtime", 0);
                String origin = movieOrigin(details, p.movieOrigin);
                // A localized response can have incomplete fields; try the default record.
                if (minutes <= 0 || !origin.contains(" · ")) {
                    JSONObject fallback = get("/movie/" + p.id + "?language=en-US");
                    if (minutes <= 0) minutes = fallback.optInt("runtime", 0);
                    if (!origin.contains(" · ")) origin = movieOrigin(fallback, origin);
                }
                if (!language.equals(Ui.language)) return;
                if (minutes > 0) p.duration = Ui.ru ? minutes + " мин" : minutes + " min";
                if (!origin.isEmpty()) p.movieOrigin = origin;
                save();
                notifyListeners();
            } catch (IOException | org.json.JSONException e) {
                AppLog.w("movie metadata: " + e.getMessage());
                synchronized (durationRequested) { durationLastAttempt.remove(id); }
            } catch (RuntimeException e) {
                AppLog.unexpected("movie metadata", e);
                synchronized (durationRequested) { durationLastAttempt.remove(id); }
            } finally {
                synchronized (durationRequested) { durationRequested.remove(id); }
            }
        });
    }


    /** Retain only the visible/prefetched window; disk copies remain available when browsing back. */
    void setPosterWindow(List<Pick> window) {
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Pick p : window) if (p != null && p.poster != null && !p.poster.isEmpty()) wanted.add(p.poster);
        synchronized (posters) {
            if (wanted.equals(posterWindow)) return;
            posterWindow = wanted;
            java.util.Iterator<Map.Entry<String, Bitmap>> entries = posters.entrySet().iterator();
            while (entries.hasNext()) {
                Map.Entry<String, Bitmap> entry = entries.next();
                if (wanted.contains(entry.getKey())) continue;
                posterRamBytes -= entry.getValue().getAllocationByteCount();
                entries.remove(); // No recycle: the last submitted frame may still reference this bitmap.
            }
        }
    }

    private boolean posterWanted(String path) {
        synchronized (posters) { return posterWindow == null || posterWindow.contains(path); }
    }

    /** Small RAM LRU for immediate draws; downloaded posters persist on disk up to 200 MiB. */
    Bitmap poster(Pick p) {
        if (p == null || p.poster == null || p.poster.isEmpty()) return null;
        final String path = p.poster;
        synchronized (posters) {
            Bitmap ready = posters.get(path);
            if (ready != null) return ready;
            if (posterWindow != null && !posterWindow.contains(path)) return null;
            Long failed = posterFailures.get(path);
            if (failed != null && SystemClock.elapsedRealtime() - failed < POSTER_RETRY_MS) return null;
            if (posterPending.size() >= POSTER_PENDING_MAX || !posterPending.add(path)) return null;
        }
        posterIo.execute(() -> {
            File tmp = null;
            boolean loaded = false;
            boolean abandoned = false;
            try {
                if (!posterWanted(path)) { abandoned = true; return; }
                File dir = new File(context.getCacheDir(), "tmdb-posters");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create poster cache");
                File f = new File(dir, path.replace("/", ""));
                boolean downloaded = false;
                if (!f.isFile() || f.length() == 0) {
                    tmp = new File(f.getPath() + ".part");
                    HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/w500" + path).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    try {
                        long expected = c.getContentLengthLong();
                        if (expected > POSTER_FILE_MAX_BYTES) throw new IOException("Poster exceeds size limit");
                        long received = 0;
                        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[16384];
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                if (!posterWanted(path)) { abandoned = true; return; }
                                received += n;
                                if (received > POSTER_FILE_MAX_BYTES) throw new IOException("Poster exceeds size limit");
                                out.write(buf, 0, n);
                            }
                        }
                        if (expected >= 0 && received != expected) throw new IOException("Incomplete poster download");
                    } finally { c.disconnect(); }
                    if (!tmp.renameTo(f)) throw new IOException("Poster cache write failed");
                    downloaded = true;
                }
                if (!posterWanted(path)) { abandoned = true; return; }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
                Bitmap bmp = BitmapFactory.decodeFile(f.getPath(), opts);
                if (bmp == null) {
                    f.delete(); // Re-download a damaged image next time.
                } else {
                    f.setLastModified(System.currentTimeMillis()); // LRU: recently used images survive.
                    // Disk hits cannot grow the directory; do not scan 200 MiB of cache after every decode.
                    if (downloaded || !posterCacheChecked) {
                        prunePosterCache(dir);
                        posterCacheChecked = true;
                    }
                    synchronized (posters) {
                        if (posterWindow != null && !posterWindow.contains(path)) { abandoned = true; return; }
                        Bitmap previous = posters.put(path, bmp);
                        if (previous != null) posterRamBytes -= previous.getAllocationByteCount();
                        posterRamBytes += bmp.getAllocationByteCount();
                        while (posterRamBytes > POSTER_RAM_MAX_BYTES && posters.size() > 1) {
                            String eldest = posters.keySet().iterator().next();
                            Bitmap removed = posters.remove(eldest);
                            // Do not recycle: a frame may still be using an evicted bitmap.
                            if (removed != null) posterRamBytes -= removed.getAllocationByteCount();
                        }
                        posterFailures.remove(path);
                    }
                    loaded = true;
                    notifyListeners();
                }
            } catch (IOException | IllegalArgumentException | SecurityException | OutOfMemoryError e) {
                AppLog.w("poster: " + e.getMessage());
            } finally {
                if (tmp != null && tmp.exists()) tmp.delete();
                synchronized (posters) {
                    if (!loaded && !abandoned && (posterWindow == null || posterWindow.contains(path))) {
                        posterFailures.put(path, SystemClock.elapsedRealtime());
                        // A long browsing session must not accumulate an unbounded failure history.
                        while (posterFailures.size() > 128) {
                            posterFailures.remove(posterFailures.keySet().iterator().next());
                        }
                    }
                    posterPending.remove(path);
                }
            }
        });
        return null;
    }

    /** Evict least recently used poster files only after the disk cache exceeds 200 MiB. */
    private static void prunePosterCache(File dir) {
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null) return;
        long total = 0;
        for (File f : files) if (f.isFile()) total += f.length();
        if (total <= POSTER_CACHE_MAX_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= POSTER_CACHE_MAX_BYTES) break;
            long size = f.length();
            if (f.isFile() && f.delete()) total -= size;
        }
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

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
                || !listLanguage.equals(Ui.language) || newPicks.isEmpty() || detectivePicks.isEmpty();
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick);
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
                        if (pick.backdrop != null) download(pick);
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
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        for (Pick p : picks) {
            if (p.detailed && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
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
        for (Pick p : picks) if (!p.detailed || !hasImage(p)) todo.add(p);
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
                        if (p.backdrop != null) download(p);
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
        String apiLanguage = "ru".equals(language) ? "ru-RU" : "en-US";
        // Popularity is TMDB's global popularity score, filtered by Russian release context.
        // It is NOT a measurement of viewing figures in Russia.
        List<Pick> popular = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {"movie", "tv"}) {
            for (int page = 1; page <= 2; page++) {
                String path = "/discover/" + kind + "?language=" + apiLanguage + "&region=RU"
                        + "&sort_by=popularity.desc&include_adult=false&page=" + page;
                if (!language.equals(Ui.language)) return false;
                JSONArray results = get(path).getJSONArray("results");
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
        String from = day(-180), to = day(0);
        for (String kind : new String[] {"movie", "tv"}) {
            String dateField = "movie".equals(kind) ? "primary_release_date" : "first_air_date";
            for (int page = 1; page <= 4; page++) {
                String path = "/discover/" + kind + "?language=" + apiLanguage + "&region=RU"
                        + "&sort_by=popularity.desc&vote_count.gte=200&include_adult=false"
                        + "&" + dateField + ".gte=" + from + "&" + dateField + ".lte=" + to
                        + "&page=" + page;
                if (!language.equals(Ui.language)) return false;
                JSONArray results = get(path).getJSONArray("results");
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
                if (!language.equals(Ui.language)) return false;
                JSONArray results = get("/discover/movie?language=" + apiLanguage
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
        synchronized (this) {
            if (!language.equals(Ui.language)) return false;
            picks = popular;
            newPicks = fresh;
            detectivePicks = detectives;
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
                android.util.Log.w("FreeMyTV", "TMDB search failed", e);
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
        for (Pick p : "detective".equals(kind) ? detectivePicks : kind == null ? newPicks : picks) {
            if ("detective".equals(kind) || kind == null || kind.equals(p.kind)) {
                out.add(p);
                if (out.size() >= 25) break;
            }
        }
        return out;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        if (Ui.ru) return "ru-RU";
        if (Ui.es) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (Ui.de) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        return Ui.en ? "en-US" : "pt-BR";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        TmdbMetadata.add(list, o, kind);
    }

    /** Movie production country (first TMDB country) and theatrical release year. */
    private static String movieOrigin(JSONObject details, String fallback) {
        return TmdbMetadata.movieOrigin(details, fallback);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws IOException, org.json.JSONException {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language + ",en");
        TmdbMetadata.applyDetails(p, o, REGION, this::installed);
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
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

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        if (p.backdrop == null || p.backdrop.isEmpty()) return;
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the original (≥ 1920 wide) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                // Keep the first three New Releases backdrops resident while browsing far to the right.
                // Otherwise returning to the beginning flashes blank cards during disk decode.
                // Evict other LRU entries first, retaining the same overall memory cap.
                while (images.size() > (forLight ? 10 : 4)) {
                    String victim = null;
                    for (String candidate : images.keySet()) {
                        if (!isInitialNewReleaseBackdrop(candidate)) {
                            victim = candidate;
                            break;
                        }
                    }
                    if (victim == null) victim = images.keySet().iterator().next();
                    images.remove(victim);
                }
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            // TV bitmap/shader implementations can fail at runtime; keep the visual fallback,
            // but make unexpected code/platform faults visible without logging private payloads.
            if (e instanceof RuntimeException && !(e instanceof IllegalArgumentException)
                    && !(e instanceof SecurityException))
                AppLog.unexpected("recommendation image", (RuntimeException) e);
            else AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    /** The first cards are revisited often; protect their decoded images from LRU eviction. */
    private boolean isInitialNewReleaseBackdrop(String key) {
        List<Pick> initial = newPicks;
        for (int i = 0; i < Math.min(3, initial.size()); i++) {
            Pick p = initial.get(i);
            if (key.equals(p.backdrop)) return true;
        }
        return false;
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= 24) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 24; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                        .put("poster", p.poster == null ? "" : p.poster).put("rating", p.rating).put("meta", p.meta).put("duration", p.duration).put("tvOrigin", p.tvOrigin).put("movieOrigin", p.movieOrigin).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed));
            }
            JSONArray d = new JSONArray();
            for (Pick p : detectivePicks) {
                d.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title)
                        .put("backdrop", p.backdrop).put("poster", p.poster == null ? "" : p.poster)
                        .put("rating", p.rating).put("meta", p.meta).put("duration", p.duration)
                        .put("tvOrigin", p.tvOrigin).put("movieOrigin", p.movieOrigin)
                        .put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed));
            }
            JSONArray n = new JSONArray();
            for (Pick p : newPicks) {
                n.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                        .put("poster", p.poster == null ? "" : p.poster).put("rating", p.rating).put("meta", p.meta)
                        .put("duration", p.duration).put("tvOrigin", p.tvOrigin).put("movieOrigin", p.movieOrigin)
                        .put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed));
            }
            cache.edit().putString("newPicks", n.toString()).putString("detectivePicks", d.toString()).putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (org.json.JSONException e) {
            AppLog.w("recommendations cache write: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            AppLog.unexpected("recommendations cache write", e);
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster", "");
            p.meta = o.optString("meta");
            p.rating = o.optDouble("rating", 0);
            p.duration = o.optString("duration", "");
            p.tvOrigin = o.optString("tvOrigin", "");
            p.movieOrigin = o.optString("movieOrigin", "");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
