package io.tvos.watching;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import java.util.Map;

/** Identify history channels without assigning playback dates or changing publisher order. */
public final class HistoryChannels {
    private static final String TAG = "tvOS-WatchNext";
    private static final String[] COLUMNS = {"_id", "package_name", "internal_provider_id", "display_name", "app_link_intent_uri"};
    private final Context context;

    public HistoryChannels(Context context) { this.context = context; }

    /** Null preserves the caller's early return when the provider supplies no cursor. */
    public Map<Long, String> find() {
        java.util.Map<Long, String> channels = new java.util.LinkedHashMap<>();
        java.util.Map<Long, String> lampaHistory = new java.util.LinkedHashMap<>(), lampaContinued = new java.util.LinkedHashMap<>();
        try (Cursor cursor = context.getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                COLUMNS, null, null, null)) {
            if (cursor == null) return null;
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
        return channels;
    }

    private static String text(Cursor cursor, int column) {
        String value = cursor.getString(column);
        return value == null ? "" : value;
    }
}
