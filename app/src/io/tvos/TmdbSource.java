package io.tvos;

/** Injectable JSON transport boundary shared by catalog and detail requests. */
interface TmdbSource {
    org.json.JSONObject get(String path) throws java.io.IOException, org.json.JSONException;
}
