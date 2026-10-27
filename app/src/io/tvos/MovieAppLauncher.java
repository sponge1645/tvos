package io.tvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Installed movie-app selection, deep links and KinoPub resolution. No home layout or focus ownership. */
final class MovieAppLauncher {
    interface Host {
        boolean running();
        int epoch();
        void toast(String text);
    }

    private final Activity activity;
    private final View anchor;
    private final Executor worker;
    private final Handler handler;
    private final Host host;

    MovieAppLauncher(Activity activity, View anchor, Executor worker, Handler handler, Host host) {
        this.activity = activity;
        this.anchor = anchor;
        this.worker = worker;
        this.handler = handler;
        this.host = host;
    }

    /** Choose a launchable installed TV application. Does not claim the title is available there. */
    void choose(Recs.Pick pick) {
        if (pick == null) return;
        PackageManager pm = activity.getPackageManager();
        String service = MovieService.id(MovieService.index(activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(MovieService.KEY, "")));
        if (!service.isEmpty()) {
            String preferred = MovieService.resolve(service, pkg -> HomeApps.launchIntent(pm, pkg) != null);
            if (!preferred.isEmpty()) { open(pick, preferred, MovieService.label(MovieService.index(service), AppLanguage.current())); return; }
            host.toast(AppStrings.get(R.string.home_default_service_unavailable));
        }
        LinkedHashMap<String, String> apps = new LinkedHashMap<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(activity.getPackageName()) || HomeApps.launchIntent(pm, pkg) == null) continue;
            apps.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        // Also include launchable phone-style apps installed on some YaOS devices.
        Intent regular = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(regular, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(activity.getPackageName()) || HomeApps.launchIntent(pm, pkg) == null) continue;
            apps.putIfAbsent(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        // WATCH_APPS_ONLY_V4: show only supported video applications.
        java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(
                "top.rootu.lampa", "com.seena.app", "watch.kino", "watch.kino.mod", "com.vk.tv"));
        apps.keySet().retainAll(allowed);
        if (apps.isEmpty()) {
            host.toast(AppStrings.get(R.string.home_no_launchable_apps_found));
            return;
        }
        java.util.List<Map.Entry<String, String>> entries = new ArrayList<>(apps.entrySet());
        entries.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) labels[i] = entries.get(i).getValue();
        new AlertDialog.Builder(activity)
                .setTitle(AppStrings.get(R.string.home_open_app))
                .setItems(labels, (dialog, which) -> {
                    Map.Entry<String, String> selected = entries.get(which);
                    open(pick, selected.getKey(), selected.getValue());
                })
                .setNegativeButton(AppStrings.get(R.string.home_cancel), null)
                .show();
    }

    private void open(Recs.Pick pick, String pkg, String label) {
        if ("top.rootu.lampa".equals(pkg)) {
            try {
                Intent link = new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.themoviedb.org/"
                                + ("tv".equals(pick.kind) ? "tv/" : "movie/") + pick.id));
                link.setPackage("top.rootu.lampa");
                if (AppLaunch.start(activity, link, "Lampa title link")) return;
            } catch (RuntimeException e) {
                AppLog.unexpected("Lampa link preparation", e);
            }
            // Older Lampa builds may not handle TMDB links; use normal launch.
        }
        if ("com.seena.app".equals(pkg)) {
            // Seena 1.9.0: ACTION_SEARCH with a full query, no delayed relaunch.
            try {
                Intent search = new Intent(Intent.ACTION_SEARCH);
                search.setClassName("com.seena.app", "com.seena.app.MainActivity");
                search.putExtra(android.app.SearchManager.QUERY, pick.title);
                search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!AppLaunch.start(activity, search, "Seena search"))
                    host.toast(AppStrings.get(R.string.home_couldn_t_open_seena_search));
            } catch (RuntimeException e) {
                AppLog.unexpected("Seena search preparation", e);
                host.toast(AppStrings.get(R.string.home_couldn_t_open_seena_search));
            }
            return;
        }
        if ("com.vk.tv".equals(pkg)) {
            openVkVideoSearch(pick.title);
            return;
        }
        if (KinoPubLink.supports(pkg)) {
            openKinoPub(pick, pkg);
            return;
        }
        HomeApps.Item app = HomeApps.app(pkg, label);
        if (!HomeApps.open(activity, app, anchor, 0, 0, 1, 1)) {
            host.toast(AppStrings.get(R.string.home_couldn_t_open_app));
        }
    }

    /** Open VK Video search with the selected movie title; no automation. */
    private void openVkVideoSearch(String title) {
        if (title == null || title.trim().isEmpty()) return;
        try {
            String query = java.net.URLEncoder.encode(title.trim(), "UTF-8");
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("vkvideo://video/search?query=" + query));
            intent.setPackage("com.vk.tv");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (AppLaunch.start(activity, intent, "VK Video search")) return;
        } catch (java.io.UnsupportedEncodingException e) {
            AppLog.w("VK Video query encoding: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            AppLog.unexpected("VK Video search preparation", e);
        }
        host.toast(AppStrings.get(R.string.home_couldn_t_open_vk_video_search));
    }

    /** KinoPub autocomplete: exact title first; type is a preference, not a hard filter (TV shows can be tagged differently). */
    private void openKinoPub(Recs.Pick pick, String targetPackage) {
        final int epoch = host.epoch();
        if (pick == null || pick.title == null || pick.title.trim().isEmpty()) return;
        final String title = pick.title.trim();
        final String kind = pick.kind;
        final String origin = "tv".equals(kind) ? pick.tvOrigin : pick.movieOrigin;
        final Matcher yearMatch = Pattern.compile("(?:19|20)\\d{2}").matcher(origin == null ? "" : origin);
        final String year = yearMatch.find() ? yearMatch.group() : "";
        final SharedPreferences cache = activity.getSharedPreferences("kinopub-ids", Context.MODE_PRIVATE);
        final String cacheKey = kind + ":" + pick.id;
        final int cached = cache.getInt(cacheKey, -1);
        if (cached > 0) {
            launchKinoPub(cached, targetPackage);
            return;
        }
        host.toast(AppStrings.get(R.string.home_searching_kinopub));
        worker.execute(() -> {
            int id = -1;
            // Try both TMDB's localized and original titles. KinoPub may index only one.
            java.util.LinkedHashSet<String> queries = new java.util.LinkedHashSet<>();
            queries.add(title);
            if (pick.originalTitle != null && !pick.originalTitle.trim().isEmpty()) queries.add(pick.originalTitle.trim());
            // Keep candidates by KinoPub ID: the same result may appear in both queries.
            java.util.Map<Integer, Integer> scores = new java.util.HashMap<>();
            final String normalizedTitle = normalizeKinoTitle(title);
            final String normalizedOriginal = normalizeKinoTitle(pick.originalTitle == null ? "" : pick.originalTitle);
            for (String query : queries) {
                try {
                    String endpoint = "https://api.kinopub.link/v1.1/autocomplete?query="
                            + URLEncoder.encode(query, "UTF-8");
                    HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    try {
                        if (conn.getResponseCode() != 200) continue;
                        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) != -1 && bytes.size() < 131072) bytes.write(buf, 0, n);
                            JSONArray matches = new JSONArray(bytes.toString("UTF-8"));
                            for (int i = 0; i < matches.length(); i++) {
                                JSONObject item = matches.optJSONObject(i);
                                if (item == null) continue;
                                String type = item.optString("type", "");
                                int candidate = item.optInt("id", -1);
                                if (candidate <= 0) continue;
                                String value = item.optString("value", "");
                                Matcher m = Pattern.compile("\\((\\d{4})\\)\\s*$").matcher(value);
                                String names = m.find() ? value.substring(0, m.start()) : value;
                                int nameScore = 0;
                                for (String part : names.split(" / ")) {
                                    String normalized = normalizeKinoTitle(part);
                                    if (normalized.isEmpty()) continue;
                                    if (normalized.equals(normalizedTitle)) nameScore = Math.max(nameScore, 100);
                                    if (!normalizedOriginal.isEmpty() && normalized.equals(normalizedOriginal))
                                        nameScore = Math.max(nameScore, 100);
                                }
                                // An exact title is mandatory; autocomplete contains many unrelated items.
                                if (nameScore == 0) continue;
                                // Prefer the matching category, but do not exclude shows,
                                // documentaries or other KinoPub categories with an exact title.
                                int score = nameScore;
                                if (("movie".equals(kind) && "movie".equals(type))
                                        || ("tv".equals(kind) && "serial".equals(type))) score += 10;
                                Matcher ym = Pattern.compile("\\((\\d{4})\\)\\s*$").matcher(value);
                                if (!year.isEmpty() && ym.find()) {
                                    int delta = Math.abs(Integer.parseInt(year) - Integer.parseInt(ym.group(1)));
                                    if (delta == 0) score += 20;
                                    else if (delta <= ("tv".equals(kind) ? 3 : 1)) score += 5;
                                    else continue; // Different production with the same name.
                                }
                                Integer old = scores.get(candidate);
                                if (old == null || score > old) scores.put(candidate, score);
                            }
                        }
                    } finally {
                        conn.disconnect();
                    }
                } catch (java.io.IOException | org.json.JSONException e) {
                    AppLog.w("KinoPub lookup: " + e.getClass().getSimpleName());
                } catch (RuntimeException e) {
                    AppLog.unexpected("KinoPub lookup", e);
                }
            }
            int bestScore = -1;
            boolean ambiguous = false;
            for (java.util.Map.Entry<Integer, Integer> candidate : scores.entrySet()) {
                int score = candidate.getValue();
                if (score > bestScore) {
                    bestScore = score;
                    id = candidate.getKey();
                    ambiguous = false;
                } else if (score == bestScore) {
                    ambiguous = true;
                }
            }
            if (ambiguous) id = -1;
            final int resolved = id;
            handler.post(() -> {
                if (!host.running() || epoch != host.epoch()) return;
                if (resolved > 0) {
                    cache.edit().putInt(cacheKey, resolved).apply();
                    launchKinoPub(resolved, targetPackage);
                } else {
                    host.toast(AppStrings.get(R.string.home_no_exact_kinopub_match));
                }
            });
        });
    }

    private static String normalizeKinoTitle(String title) {
        return title.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll(" +", " ");
    }

    private void launchKinoPub(int id, String targetPackage) {
        for (Intent intent : KinoPubLink.candidates(targetPackage, id)) {
            if (AppLaunch.start(activity, intent, "KinoPub card launch")) return;
        }
        host.toast(AppStrings.get(R.string.home_couldn_t_open_kinopub_card));
    }

}
