package io.tvos;

import io.tvos.watching.WatchingSources;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only Watch Next bridge for installed media services. Queries run off the UI thread only while Home is visible. */
final class WatchingCatalog {
    static final int PERMISSION_REQUEST = 5042;
    private static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final String TAG = "tvOS-WatchNext";
    private static final Uri URI = Uri.parse("content://android.media.tv/watch_next_program");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Pattern ITEM = Pattern.compile("^([ms])([0-9]+)(?:[:_].*)?$");
    private static final String[] COLUMNS = {"_id", "package_name", "internal_provider_id", "title",
            "poster_art_uri", "intent_uri", "season_display_number", "episode_display_number",
            "duration_millis", "last_playback_position_millis", "last_engagement_time_utc_millis", "type"};
    private final Context context;
    private final Runnable changed;
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean observerPending;
    private long nextObservedRefresh;
    private final Runnable reload = this::refreshObserved;
    private void refreshObserved() {
        if (!active) { observerPending = false; return; }
        long remaining = nextObservedRefresh - android.os.SystemClock.elapsedRealtime();
        if (remaining > 0) { main.postDelayed(reload, remaining); return; }
        observerPending = false;
        refresh();
    }
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            if (!active || observerPending) return;
            observerPending = true;
            main.postDelayed(reload, Math.max(350, nextObservedRefresh - android.os.SystemClock.elapsedRealtime()));
        }
    };
    private List<Recs.Pick> allPicks = Collections.emptyList();
    private List<Recs.Pick> picks = Collections.emptyList();
    private boolean active, observing, busy, pending, failed;
    private int generation;

    WatchingCatalog(Context context, Runnable changed) {
        this(context, changed, IO);
    }

    WatchingCatalog(Context context, Runnable changed, Executor worker) {
        this.context = context.getApplicationContext();
        this.changed = changed;
        this.worker = worker;
    }

    List<Recs.Pick> picks() { return picks; }
    List<Recs.Pick> forSource(String source) { return WatchingItems.latest(allPicks, source); }

    String source() { return Prefs.watchingSource(); }

    void selectSource(String source) {
        Prefs.setWatchingSource(source);
        requestSeenaRefresh();
        picks = WatchingItems.latest(allPicks, source);
        Log.i(TAG, "Selected source=" + source + ": " + WatchingItems.trace(picks));
    }

    List<String> sources() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (String id : new String[]{"kinopub", "seena", "lampa"}) if (installedSource(id)) ids.add(id);
        for (Recs.Pick pick : allPicks) {
            String id = WatchingSources.id(pick.pkg);
            if (installedSource(id)) ids.add(id);
        }
        String selected = source();
        if (!"all".equals(selected) && installedSource(selected)) ids.add(selected);
        return new ArrayList<>(ids);
    }

    private boolean installedSource(String source) {
        String[] packages;
        switch (source) {
            case "kinopub": packages = new String[]{"watch.kino", "watch.kino.mod"}; break;
            case "seena": packages = new String[]{"com.seena.app"}; break;
            case "lampa": packages = new String[]{"top.rootu.lampa"}; break;
            default: packages = new String[]{source};
        }
        for (String pkg : packages) {
            try {
                context.getPackageManager().getApplicationInfo(pkg, 0);
                return true;
            } catch (android.content.pm.PackageManager.NameNotFoundException | SecurityException e) {
                // Try another package alias.
            }
        }
        return false;
    }

    String sourceLabel(String source) {
        String known = WatchingSources.knownLabel(source);
        if (!known.isEmpty()) return known;
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(source, 0)).toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException | SecurityException e) {
            return source;
        }
    }

    String emptyText() {
        return AppStrings.get(!permitted() ? R.string.home_watching_permission
                : failed ? R.string.home_watching_failed : busy ? R.string.home_genre_loading : R.string.home_watching_empty);
    }

    void requestPermission(Activity activity) {
        if (!permitted()) activity.requestPermissions(new String[]{PERMISSION}, PERMISSION_REQUEST);
        else refresh();
    }

    private boolean permitted() {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    void start() {
        if (active) return;
        active = true;
        refresh();
        requestSeenaRefresh();
    }

    private void requestSeenaRefresh() {
        if (active && Prefs.categoryEnabled(2) && ("all".equals(source()) || "seena".equals(source()))
                && installedSource("seena")) SeenaChannelRefresh.request(context);
    }

    void stop() {
        active = false;
        generation++;
        pending = false;
        main.removeCallbacks(reload);
        observerPending = false;
        if (observing) {
            context.getContentResolver().unregisterContentObserver(observer);
            observing = false;
        }
    }

    void refresh() {
        if (!active) return;
        if (!permitted()) {
            allPicks = picks = Collections.emptyList();
            failed = false;
            changed.run();
            return;
        }
        if (!observing) {
            try {
                for (String table : new String[]{"watch_next_program", "channel", "preview_program"})
                    context.getContentResolver().registerContentObserver(Uri.parse("content://android.media.tv/" + table), true, observer);
                observing = true;
            } catch (SecurityException e) {
                Log.w(TAG, "Watch Next observer denied");
            }
        }
        if (busy) { pending = true; return; }
        busy = true;
        nextObservedRefresh = android.os.SystemClock.elapsedRealtime() + 5000;
        final int request = generation;
        worker.execute(() -> {
            List<Recs.Pick> found = new ArrayList<>();
            boolean error = false;
            try (Cursor cursor = context.getContentResolver().query(URI, COLUMNS, null, null,
                    "last_engagement_time_utc_millis DESC")) {
                if (cursor == null) throw new IllegalStateException("Null Watch Next cursor");
                while (cursor.moveToNext()) {
                    String owner = cursor.getString(1);
                    if (owner == null || owner.isEmpty() || "io.tvos".equals(owner)) continue;
                    Recs.Pick pick = read(cursor, owner);
                    if (pick != null) found.add(pick);
                }
            } catch (RuntimeException e) {
                error = true;
                Log.w(TAG, "Watch Next read failed: " + e.getClass().getSimpleName());
            }
            try { readHistoryChannels(found); }
            catch (RuntimeException e) { Log.w(TAG, "History channel read failed: " + e.getClass().getSimpleName()); }
            final List<Recs.Pick> result = WatchingItems.latest(found);
            final boolean failedRead = error;
            main.post(() -> {
                busy = false;
                if (active && request == generation) {
                    failed = failedRead;
                    if (!failedRead) {
                        for (Recs.Pick pick : result) pick.meta = WatchingText.caption(pick);
                        allPicks = result;
                        picks = WatchingItems.latest(allPicks, source());
                        Log.i(TAG, "Provider snapshot: " + WatchingItems.trace(allPicks));
                        Log.i(TAG, "Selected source=" + source() + ": " + WatchingItems.trace(picks));
                    }
                    changed.run();
                }
                if (active && (pending || request != generation)) {
                    pending = false;
                    refresh();
                }
            });
        });
    }

    private void readHistoryChannels(List<Recs.Pick> found) {
        java.util.Map<Long, String> channels = new java.util.LinkedHashMap<>();
        java.util.Map<Long, String> lampaHistory = new java.util.LinkedHashMap<>(), lampaContinued = new java.util.LinkedHashMap<>();
        try (Cursor cursor = context.getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                new String[]{"_id", "package_name", "internal_provider_id", "display_name", "app_link_intent_uri"}, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String owner = text(cursor, 1);
                if ("com.seena.app".equals(owner) && "seena_tv_history".equals(text(cursor, 2)))
                    channels.put(cursor.getLong(0), owner);
                if ("top.rootu.lampa".equals(owner)) {
                    String link = text(cursor, 4);
                    if ("lampa://top.rootu.lampa/update_channel/history".equals(link)) lampaHistory.put(cursor.getLong(0), owner);
                    if ("lampa://top.rootu.lampa/update_channel/continued".equals(link)) lampaContinued.put(cursor.getLong(0), owner);
                }
            }
        }
        Log.i(TAG, "History channels: total=" + (channels.size() + lampaContinued.size() + lampaHistory.size())
                + ", lampa=" + (lampaContinued.size() + lampaHistory.size()));
        channels.putAll(lampaContinued);
        channels.putAll(lampaHistory);
        String[] columns = COLUMNS.clone();
        columns[10] = "_id"; // Preview programs do not have Watch Next engagement timestamps.
        int rank = 0;
        for (java.util.Map.Entry<Long, String> channel : channels.entrySet()) {
            List<Recs.Pick> channelPicks = new ArrayList<>();
            try (Cursor cursor = context.getContentResolver().query(
                    Uri.parse("content://android.media.tv/preview_program?channel=" + channel.getKey()),
                    columns, null, null, "_id ASC")) {
                if (cursor == null) continue;
                while (cursor.moveToNext()) {
                    if (!channel.getValue().equals(text(cursor, 1))) continue;
                    Recs.Pick pick = read(cursor, channel.getValue());
                    if (pick == null) continue;
                    // Preserve publisher insertion order; do not invent playback dates or remaining time.
                    pick.watchingUpdated = -1L - rank++;
                    pick.watchingRemaining = -1;
                    found.add(pick);
                    channelPicks.add(pick);
                }
            }
            Log.i(TAG, "History channel=" + channel.getKey() + ", owner=" + channel.getValue()
                    + ": " + WatchingItems.trace(channelPicks));
        }
    }

    private static String text(Cursor cursor, int column) {
        String value = cursor.getString(column);
        return value == null ? "" : value;
    }

    private Recs.Pick read(Cursor cursor, String owner) {
        String title = text(cursor, 3), link = text(cursor, 5);
        if (title.isEmpty() || link.isEmpty()) return null;
        Intent intent = intent(link, owner);
        if (intent == null) return null;
        Recs.Pick pick = new Recs.Pick();
        pick.id = 0; // Source item IDs never enter TMDB enrichment or backdrop caches.
        pick.pkg = owner;
        pick.title = title;
        pick.resumeUri = link;
        String episode = text(cursor, 7);
        pick.kind = cursor.getInt(11) == 3 || (!episode.isEmpty() && !"0".equals(episode)) ? "tv" : "movie";
        pick.watchingSeason = text(cursor, 6);
        pick.watchingEpisode = text(cursor, 7);
        pick.watchingRemaining = WatchingItems.remaining(cursor.getLong(8), cursor.getLong(9));
        pick.watchingUpdated = cursor.getLong(10);
        pick.poster = poster(text(cursor, 4), owner);
        String service = WatchingSources.id(owner);
        if (!"kinopub".equals(service)) {
            pick.watchingKey = service + ":" + pick.kind + ":" + title.trim().toLowerCase(java.util.Locale.ROOT);
            return pick;
        }
        String internalId = text(cursor, 2);
        Matcher identity = ITEM.matcher(internalId);
        if (identity.matches()) pick.watchingKey = "kinopub:" + identity.group(2);
        else if (internalId.matches("[0-9]+")) pick.watchingKey = "kinopub:" + internalId;
        String resume = intent.getStringExtra("kino_resume");
        if (resume != null && resume.length() <= 131072) {
            try {
                JSONObject data = new JSONObject(resume);
                if (data.optInt("id") > 0) pick.watchingKey = "kinopub:" + data.optInt("id");
                // The system row may deliberately use a landscape thumbnail; keep the portrait poster.
                String portrait = poster(data.optString("posterBig", ""), owner);
                if (portrait.isEmpty()) portrait = poster(data.optString("poster", ""), owner);
                if (!portrait.isEmpty()) pick.poster = portrait;
            } catch (JSONException e) {
                Log.w(TAG, "Invalid KinoPub resume metadata");
            }
        }
        if (pick.watchingKey == null) {
            // Some versions use a plain item ID instead of the MOD's m/s prefix.
            Uri data = intent.getData();
            String id = data == null ? null : data.getLastPathSegment();
            if (id != null && id.matches("[0-9]+")) pick.watchingKey = "kinopub:" + id;
            else pick.watchingKey = "kinopub:" + pick.kind + ":" + title;
        }
        return pick;
    }

    private String poster(String value, String owner) {
        return ProviderImages.allowed(context, value, owner) ? value : "";
    }

    private static Intent intent(String link, String owner) {
        if (owner == null || owner.isEmpty() || "io.tvos".equals(owner)) return null;
        if (link == null || link.length() > 262144) return null;
        try {
            Intent intent = Intent.parseUri(link, Intent.URI_INTENT_SCHEME);
            if (intent.getComponent() != null && !owner.equals(intent.getComponent().getPackageName())) return null;
            intent.setSelector(null);
            intent.setPackage(owner);
            intent.setClipData(null);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    boolean open(Recs.Pick pick) {
        Intent intent = intent(pick.resumeUri, pick.pkg);
        return intent != null && AppLaunch.start(context, intent, "Watch Next resume");
    }
}
