package io.tvos;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/** Explicit, read-only diagnostic of TV provider visibility from the home process. */
final class WatchNextProbe {
    private static final String TAG = "tvOS-WatchNext";

    private WatchNextProbe() {}

    static void run(Context context) {
        new Thread(() -> {
            for (String table : new String[]{"watch_next_program", "channel", "preview_program"}) {
                int total = 0;
                int kino = 0;
                try (Cursor cursor = context.getContentResolver().query(
                        Uri.parse("content://android.media.tv/" + table),
                        new String[]{"_id", "package_name"}, null, null, null)) {
                    if (cursor == null) {
                        Log.i(TAG, table + ": null cursor");
                        continue;
                    }
                    while (cursor.moveToNext()) {
                        total++;
                        String owner = cursor.getString(1);
                        if ("watch.kino".equals(owner) || "watch.kino.mod".equals(owner)) kino++;
                    }
                    Log.i(TAG, table + ": visible=" + total + ", kinopub=" + kino);
                } catch (SecurityException e) {
                    Log.w(TAG, table + ": access denied", e);
                } catch (RuntimeException e) {
                    Log.w(TAG, table + ": query failed", e);
                }
            }
        }, "tvOS-WatchNextProbe").start();
    }
}
