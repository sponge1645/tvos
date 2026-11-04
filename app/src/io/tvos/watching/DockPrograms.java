package io.tvos.watching;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;

/** Read-only TV-provider recommendations; publisher authorization is supplied by the Android adapter. */
public final class DockPrograms {
    private static final String[] CHANNEL_COLUMNS = {"_id", "package_name", "display_name", "internal_provider_id", "app_link_intent_uri"};
    private static final String[] PROGRAM_COLUMNS = {"package_name", "poster_art_uri", "thumbnail_uri", "poster_art_aspect_ratio", "thumbnail_aspect_ratio", "title", "release_date"};
    private final Context context;
    private final BiPredicate<String, String> allowed;

    public DockPrograms(Context context, BiPredicate<String, String> allowed) {
        this.context = context;
        this.allowed = allowed;
    }

    public static final class Candidate {
        public final String title, poster, year;
        public final List<String> urls = new ArrayList<>();
        public Candidate(String title, String poster, String year) { this.title = title; this.poster = poster; this.year = year; }
    }

    public static int priority(String name, String id) {
        String text = (name + " " + id).toLowerCase(java.util.Locale.ROOT);
        if (text.contains("history") || text.contains("истори") || text.contains("watch_next") || text.contains("continued") || text.contains("смотр")) return -1;
        if ((text.contains("нов") || text.contains("new")) && (text.contains("фильм") || text.contains("movie"))) return 100;
        if (text.contains("recs") || text.contains("recommend") || text.contains("рекоменд")) return 90;
        if (text.contains("movie") || text.contains("фильм")) return 80;
        return 10;
    }

    public List<Candidate> candidates(String pkg) {
        List<long[]> channels = new ArrayList<>();
        String service = WatchingSources.id(pkg);
        int visible = 0;
        try (Cursor c = context.getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                CHANNEL_COLUMNS, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                visible++;
                if (!service.equals(WatchingSources.id(c.getString(1)))) continue;
                int score = priority(value(c, 2), value(c, 3) + " " + value(c, 4));
                channels.add(new long[]{c.getLong(0), score});
                android.util.Log.i("tvOS-DockBackdrop", pkg + " channel=" + c.getLong(0)
                        + ", name=" + value(c, 2) + ", score=" + score
                        + ", route=" + route(value(c, 4)));
            }
        }
        android.util.Log.i("tvOS-DockBackdrop", pkg + " visible channels=" + visible + ", owned=" + channels.size());
        channels.sort(Comparator.comparingLong((long[] c) -> c[1]).reversed());
        List<Candidate> result = new ArrayList<>();
        int queried = 0;
        for (long[] channel : channels) {
            if (channel[1] < 0) continue;
            if (queried++ >= 4) break;
            collect("content://android.media.tv/preview_program?channel=" + channel[0], service,
                    "weight DESC, _id ASC", result);
        }
        if (result.isEmpty()) collect("content://android.media.tv/watch_next_program", service,
                "last_engagement_time_utc_millis DESC", result);
        // Prefer published landscapes over portrait-only titles requiring a fallback lookup.
        // Stable sort retains channel priority among equally suitable candidates.
        result.sort(Comparator.comparingInt(candidate -> candidate.urls.isEmpty() ? 1 : 0));
        if (result.size() > 6) result.subList(6, result.size()).clear();
        return result;
    }

    /** Look up this resume title, rather than the first six recommended titles. */
    public Candidate forResume(String pkg, String title, String poster) {
        if (pkg == null || title == null || title.trim().isEmpty()) return null;
        String service = WatchingSources.id(pkg);
        Candidate selected = null, exact = null;
        boolean ambiguous = false;
        List<Long> channelIds = new ArrayList<>();
        try (Cursor channels = context.getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                CHANNEL_COLUMNS, null, null, null)) {
            if (channels != null) while (channelIds.size() < 16 && channels.moveToNext())
                if (service.equals(WatchingSources.id(value(channels, 1)))) channelIds.add(channels.getLong(0));
        }
        // TV Provider can deny selection clauses and unscoped program queries.
        // Read through the same channel-scoped route used by recommendations.
        for (long channel : channelIds) {
        try (Cursor c = context.getContentResolver().query(Uri.parse("content://android.media.tv/preview_program?channel=" + channel),
                PROGRAM_COLUMNS, null, null, "_id ASC")) {
            int rows = 0;
            if (c != null) while (rows++ < 512 && c.moveToNext()) {
                String owner = value(c, 0);
                if (!service.equals(WatchingSources.id(owner)) || !resumeTitle(title).equals(resumeTitle(value(c, 5)))) continue;
                String date = value(c, 6);
                Candidate candidate = new Candidate(title, value(c, 1), date.length() >= 4 ? date.substring(0, 4) : "");
                if (c.getInt(4) == 0 && allowed.test(value(c, 2), owner)) add(candidate.urls, value(c, 2));
                if (c.getInt(3) == 0 && allowed.test(value(c, 1), owner)) add(candidate.urls, value(c, 1));
                // A portrait-only matching row can still supply the missing release year.
                if (candidate.urls.isEmpty() && !candidate.year.matches("[0-9]{4}")) continue;
                if (!identity(poster).isEmpty() && identity(poster).equals(identity(candidate.poster))) exact = candidate;
                if (selected == null) selected = candidate;
                else if (!identity(selected.poster).equals(identity(candidate.poster)) || !selected.year.equals(candidate.year)) ambiguous = true;
            }
        }
        }
        return exact != null ? exact : ambiguous ? null : selected;
    }

    private static String resumeTitle(String title) {
        return java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFC)
                .toLowerCase(java.util.Locale.ROOT).replace('ё', 'е')
                .replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }

    private static String identity(String url) {
        if (url == null) return "";
        int query = url.indexOf('?');
        String path = query < 0 ? url : url.substring(0, query);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void collect(String uri, String service, String order, List<Candidate> result) {
        long started = System.nanoTime();
        int rows = 0, owned = 0, before = result.size();
        try (Cursor c = context.getContentResolver().query(Uri.parse(uri),
                PROGRAM_COLUMNS,
                null, null, order)) {
            int added = 0;
            if (c != null) while (added < 6 && c.moveToNext()) {
                rows++;
                if (!service.equals(WatchingSources.id(c.getString(0)))) continue;
                owned++;
                String date = value(c, 6);
                Candidate candidate = new Candidate(value(c, 5), value(c, 1), date.length() >= 4 ? date.substring(0, 4) : "");
                if (c.getInt(4) == 0 && allowed.test(value(c, 2), value(c, 0))) add(candidate.urls, value(c, 2));
                if (c.getInt(3) == 0 && allowed.test(value(c, 1), value(c, 0))) add(candidate.urls, value(c, 1));
                if (!candidate.urls.isEmpty() || !candidate.title.isEmpty()) { result.add(candidate); added++; }
            }
        }
        trace(service, uri.contains("?channel=") ? "programs channel=" + uri.substring(uri.indexOf("?channel=") + 9) : "watch next",
                started, "rows=" + rows + ", owned=" + owned + ", accepted=" + (result.size() - before));
    }

    private static String route(String link) {
        int end = link.indexOf('?');
        String path = end < 0 ? link : link.substring(0, end);
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String value(Cursor c, int column) { String s = c.getString(column); return s == null ? "" : s; }

    public static void add(List<String> urls, String url) {
        if (urls.size() < 6 && !urls.contains(url) && (url.startsWith("https://") || url.startsWith("content://") || url.startsWith("android.resource://"))) urls.add(url);
    }

    private static void trace(String pkg, String stage, long started, String outcome) {
        android.util.Log.i("tvOS-DockBackdrop", pkg + " " + stage + ": "
                + (System.nanoTime() - started) / 1_000_000 + " ms, " + outcome);
    }
}
