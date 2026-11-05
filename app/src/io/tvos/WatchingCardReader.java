package io.tvos;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;
import io.tvos.watching.WatchingSources;
import io.tvos.watching.ResumeIntentPolicy;

/** Android/provider row adapter for the existing recommendation-card model. */
final class WatchingCardReader {
    private static final String TAG = "tvOS-WatchNext";
    private static final Pattern ITEM = Pattern.compile("^([ms])([0-9]+)(?:[:_].*)?$");
    private static final Pattern NUMERIC = Pattern.compile("[0-9]+");
    private final Context context;

    WatchingCardReader(Context context) { this.context = context; }

    Recs.Pick read(Cursor cursor, String owner) {
        String title = text(cursor, 3), link = text(cursor, 5);
        if (title.isEmpty() || link.isEmpty()) return null;
        Intent intent = ResumeIntentPolicy.parse(link, owner);
        if (intent == null) return null;
        Recs.Pick pick = new Recs.Pick();
        pick.id = 0; // Source item IDs never enter TMDB enrichment or backdrop caches.
        pick.pkg = owner;
        pick.title = title;
        pick.resumeUri = link;
        String episode = text(cursor, 7);
        pick.kind = (cursor.getInt(11) == 2 || cursor.getInt(11) == 3) || (!episode.isEmpty() && !"0".equals(episode)) ? "tv" : "movie";
        pick.watchingSeason = text(cursor, 6);
        pick.watchingEpisode = episode;
        pick.watchingRemaining = WatchingItems.remaining(cursor.getLong(8), cursor.getLong(9));
        pick.watchingProgress = WatchingItems.progress(cursor.getLong(8), cursor.getLong(9));
        pick.watchingUpdated = cursor.getLong(10);
        pick.poster = poster(text(cursor, 4), owner);
        pick.backdrop = pick.poster;
        // Watch Next/preview programs have a separate landscape thumbnail. Do not
        // confuse poster_art_uri with thumbnail_uri (Lampa can publish both).
        try {
            String thumbnail = poster(text(cursor, 12), owner);
            if (cursor.getInt(13) == 0 && !thumbnail.isEmpty()) pick.backdrop = thumbnail;
        } catch (IndexOutOfBoundsException ignored) { /* Compatibility with older row adapters. */ }
        try {
            String date = text(cursor, 14);
            if (date.matches("[0-9]{4}.*")) pick.watchingYear = date.substring(0, 4);
        } catch (IndexOutOfBoundsException ignored) { /* Older row adapter. */ }
        String service = WatchingSources.id(owner);
        if (!"kinopub".equals(service)) {
            pick.watchingKey = service + ":" + pick.kind + ":" + title.trim().toLowerCase(java.util.Locale.ROOT);
            if (pick.watchingYear.isEmpty()) pick.watchingYear = labelYear(title);
            return pick;
        }
        String internalId = text(cursor, 2);
        Matcher identity = ITEM.matcher(internalId);
        if (identity.matches()) pick.watchingKey = "kinopub:" + identity.group(2);
        else if (NUMERIC.matcher(internalId).matches()) pick.watchingKey = "kinopub:" + internalId;
        String resume = intent.getStringExtra("kino_resume");
        if (Log.isLoggable("tvOS-Artwork", Log.DEBUG)) {
            String date;
            try { date = diagnosticDate(text(cursor, 14)); }
            catch (IndexOutOfBoundsException ignored) { date = "column-absent"; }
            Log.d("tvOS-Artwork", "provider metadata: package=" + owner
                    + ", title=" + title.replace('\n', ' ').replace('\r', ' ')
                    + ", item=" + (internalId.matches("[ms]?[0-9]+(?:[:_][0-9]+)*") ? internalId : "other")
                    + ", release_date=" + date
                    + ", kino_resume=" + (resume == null ? "absent" : "present")
                    + ", resumeLength=" + (resume == null ? 0 : resume.length()));
        }
        if (resume != null && resume.length() <= 131072) {
            try {
                JSONObject data = new JSONObject(resume);
                logMetadataIdentity(data);
                // KinoPub can omit the TV Provider release_date while retaining
                // the release year in its resume payload.
                String year = data.optString("year", "").trim();
                if (pick.watchingYear.isEmpty() && year.matches("[0-9]{4}")) pick.watchingYear = year;
                if (data.optInt("id") > 0) pick.watchingKey = "kinopub:" + data.optInt("id");
                // The system row may deliberately use a landscape thumbnail; keep the portrait poster.
                String portrait = poster(data.optString("posterBig", ""), owner);
                if (portrait.isEmpty()) portrait = poster(data.optString("poster", ""), owner);
                if (!portrait.isEmpty()) pick.poster = portrait;
            } catch (JSONException e) {
                Log.w(TAG, "Invalid KinoPub resume metadata");
            }
        }
        if (pick.watchingYear.isEmpty()) pick.watchingYear = labelYear(title);
        if (pick.watchingKey == null) {
            // Some versions use a plain item ID instead of the MOD's m/s prefix.
            Uri data = intent.getData();
            String id = data == null ? null : data.getLastPathSegment();
            if (id != null && NUMERIC.matcher(id).matches()) pick.watchingKey = "kinopub:" + id;
            else pick.watchingKey = "kinopub:" + pick.kind + ":" + title;
        }
        return pick;
    }

    /** Only an explicitly marked trailing year; never infer dates from numbers in titles. */
    static String labelYear(String title) {
        Matcher year = Pattern.compile("\\s*[\\(\\[]([12][0-9]{3})[\\)\\]]\\s*$").matcher(title);
        return year.find() ? year.group(1) : "";
    }

    // Log field names/types, and only date-shaped values. Never dump a payload or URI.
    private static String diagnosticDate(String value) {
        if (value.isEmpty()) return "empty";
        return value.length() <= 32 && value.matches("[0-9TtZz: .+/-]+")
                ? value : "other(length=" + value.length() + ")";
    }

    private static void logMetadataIdentity(JSONObject data) {
        if (!Log.isLoggable("tvOS-Artwork", Log.DEBUG)) return;
        String original = data.optString("original", "").trim();
        if (original.isEmpty()) original = "empty";
        else if (original.length() > 200 || original.contains("://")
                || original.toLowerCase(java.util.Locale.ROOT).startsWith("www."))
            original = "omitted";
        else original = original.replaceAll("[\\p{Cntrl}]", " ");
        Log.d("tvOS-Artwork", "provider identity: id=" + diagnosticId(data.optString("id", ""), false)
                + ", imdb=" + diagnosticId(data.optString("imdb", ""), true)
                + ", kp=" + diagnosticId(data.optString("kp", ""), false)
                + ", year=" + diagnosticDate(data.optString("year", ""))
                + ", original=" + original);
    }

    private static String diagnosticId(String value, boolean imdb) {
        value = value.trim();
        if (value.isEmpty()) return "empty";
        if (value.equals("0")) return "0";
        return value.matches(imdb ? "(?:tt)?[0-9]{1,12}" : "[0-9]{1,12}")
                ? value : "other";
    }

    private String poster(String value, String owner) {
        return ProviderImages.allowed(context, value, owner) ? value : "";
    }

    private static String text(Cursor cursor, int column) {
        String value = cursor.getString(column);
        return value == null ? "" : value;
    }
}
