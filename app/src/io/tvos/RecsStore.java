package io.tvos;

import io.tvos.Recs.Pick;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import android.content.SharedPreferences;

/** Persistent catalog snapshots; preserves schema 16, preference keys and fallback values. */
final class RecsStore {
    private static final int CACHE_VERSION = 16;
    static final class Snapshot {
        List<Pick> picks = new ArrayList<>(), newPicks = new ArrayList<>();
        int period = -1;
        long listAt, keyStamp;
        String listLanguage = "";
    }
    private final SharedPreferences cache;
    RecsStore(SharedPreferences cache) { this.cache = cache; }
    Snapshot load() {
        Snapshot snapshot = new Snapshot();
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) snapshot.picks = parse(new JSONArray(saved));
            String savedNew = cache.getString("newPicks", null);
            if (savedNew != null) snapshot.newPicks = parse(new JSONArray(savedNew));
            if (cache.contains("detectivePicks")) cache.edit().remove("detectivePicks").apply();
            snapshot.period = cache.getInt("period", -1);
            snapshot.listAt = cache.getLong("listAt", 0);
            snapshot.listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            snapshot.keyStamp = cache.getLong("keyStamp", 0);
        } catch (org.json.JSONException | ClassCastException e) {
            AppLog.w("recommendations cache read: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            AppLog.unexpected("recommendations cache read", e);
        }
        return snapshot;
    }
    void save(List<Pick> picks, List<Pick> newPicks, int period, long listAt, String listLanguage, long keyStamp) {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                        .put("poster", p.poster == null ? "" : p.poster).put("rating", p.rating).put("meta", p.meta).put("duration", p.duration).put("tvOrigin", p.tvOrigin).put("movieOrigin", p.movieOrigin).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
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
            cache.edit().putString("newPicks", n.toString()).putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (org.json.JSONException e) {
            AppLog.w("recommendations cache write: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            AppLog.unexpected("recommendations cache write", e);
        }
    }
    static List<Pick> parse(JSONArray a) {
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
}
