package io.tvos;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Reads screensaver options in the settings-owning :home process, including on Android 11. */
public final class ScreensaverSettingsProvider extends ContentProvider {
    private static final Uri URI = Uri.parse("content://io.tvos.screensaver-settings");

    static Bundle read(Context context) {
        Bundle result = context.getContentResolver().call(URI, "snapshot", null, null);
        if (result == null) throw new IllegalStateException("Missing screensaver settings snapshot");
        return result;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"snapshot".equals(method)) throw new IllegalArgumentException("Unknown method");
        SharedPreferences prefs = getContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        Bundle result = new Bundle();
        result.putString("video_dream_source", prefs.getString("video_dream_source", "google"));
        for (String key : new String[]{"aerial_show_clock", "aerial_show_location", "screensaver_show_weather"})
            result.putBoolean(key, prefs.getBoolean(key, false));
        return result;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException();
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
