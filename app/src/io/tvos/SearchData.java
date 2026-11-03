package io.tvos;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Search presentation data, using the launcher's existing TMDB transport and title identity. */
final class SearchData {
    private SearchData() { }
    static final class Detail {
        String overview = "", genres = "", backdrop = "";
        int minutes;
    }
    static List<Recs.Pick> trending() throws java.io.IOException, org.json.JSONException {
        JSONObject data = TmdbClient.get("/trending/all/week?language=" + AppLanguage.current().apiTag);
        List<Recs.Pick> result = new ArrayList<>();
        JSONArray entries = data.optJSONArray("results");
        if (entries != null) for (int i = 0; i < entries.length() && result.size() < 10; i++) {
            JSONObject item = entries.optJSONObject(i);
            Recs.Pick pick = item == null ? null : TmdbMetadata.search(item);
            if (pick != null) result.add(pick);
        }
        return result;
    }
    static Detail detail(Recs.Pick pick) throws java.io.IOException, org.json.JSONException {
        JSONObject data = TmdbClient.get("/" + ("tv".equals(pick.kind) ? "tv" : "movie") + "/" + pick.id
                + "?language=" + AppLanguage.current().apiTag);
        Detail result = new Detail();
        result.overview = data.optString("overview", "");
        result.backdrop = data.optString("backdrop_path", "");
        result.minutes = data.optInt("runtime", 0);
        List<String> names = new ArrayList<>();
        JSONArray genres = data.optJSONArray("genres");
        if (genres != null) for (int i = 0; i < genres.length(); i++) {
            JSONObject genre = genres.optJSONObject(i);
            if (genre != null && !genre.optString("name", "").isEmpty()) names.add(genre.optString("name"));
        }
        result.genres = String.join(", ", names);
        return result;
    }
}
