package io.tvos;

import io.tvos.watching.WatchingSources;
import io.tvos.watching.ResumeIntentPolicy;
import io.tvos.watching.HistoryChannels;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only Watch Next bridge for installed media services. Queries run off the UI thread only while Home is visible. */
final class WatchingCatalog {
    static final int PERMISSION_REQUEST = 5042;
    private static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final String TAG = "tvOS-WatchNext";
    private static final Uri URI = Uri.parse("content://android.media.tv/watch_next_program");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final String[] COLUMNS = {"_id", "package_name", "internal_provider_id", "title",
            "poster_art_uri", "intent_uri", "season_display_number", "episode_display_number",
            "duration_millis", "last_playback_position_millis", "last_engagement_time_utc_millis", "type", "thumbnail_uri", "thumbnail_aspect_ratio", "release_date"};
    private final Context context;
    private final WatchingCardReader reader;
    private final HistoryChannels history;
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
        reader = new WatchingCardReader(this.context);
        history = new HistoryChannels(this.context);
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
        if (active && ((Prefs.homeContinue() && Prefs.categoryCount() == 0) || (Prefs.categoryEnabled(2) && ("all".equals(source()) || "seena".equals(source()))))
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
                    Recs.Pick pick = reader.read(cursor, owner);
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
        java.util.Map<Long, String> channels = history.find();
        if (channels == null) return;
        String[] columns = COLUMNS.clone();
        columns[10] = "_id"; // Preview programs do not have Watch Next engagement timestamps.
        int rank = 0;
        for (java.util.Map.Entry<Long, String> channel : channels.entrySet()) {
            List<Recs.Pick> channelPicks = new ArrayList<>();
            int rows = 0, foreign = 0, missingTitle = 0, missingLink = 0, invalidLink = 0;
            try (Cursor cursor = context.getContentResolver().query(
                    Uri.parse("content://android.media.tv/preview_program?channel=" + channel.getKey()),
                    columns, null, null, "_id ASC")) {
                if (cursor == null) {
                    Log.w(TAG, "History channel=" + channel.getKey() + ": null cursor");
                    continue;
                }
                while (cursor.moveToNext()) {
                    rows++;
                    if (!channel.getValue().equals(text(cursor, 1))) { foreign++; continue; }
                    if (text(cursor, 3).isEmpty()) { missingTitle++; continue; }
                    if (text(cursor, 5).isEmpty()) { missingLink++; continue; }
                    Recs.Pick pick = reader.read(cursor, channel.getValue());
                    if (pick == null) { invalidLink++; continue; }
                    // Preserve publisher insertion order; do not invent playback dates or remaining time.
                    pick.watchingUpdated = -1L - rank++;
                    pick.watchingRemaining = -1;
                    found.add(pick);
                    channelPicks.add(pick);
                }
            }
            Log.i(TAG, "History channel=" + channel.getKey() + ", owner=" + channel.getValue()
                    + ": rows=" + rows + ", foreign=" + foreign + ", missingTitle=" + missingTitle
                    + ", missingLink=" + missingLink + ", invalidLink=" + invalidLink
                    + ", " + WatchingItems.trace(channelPicks));
        }
    }

    private static String text(Cursor cursor, int column) {
        String value = cursor.getString(column);
        return value == null ? "" : value;
    }

    boolean open(Recs.Pick pick) {
        Intent intent = ResumeIntentPolicy.parse(pick.resumeUri, pick.pkg);
        return intent != null && AppLaunch.start(context, intent, "Watch Next resume");
    }
}
