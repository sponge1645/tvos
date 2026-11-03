package io.tvos;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (tvOS > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Historical key codes used only to name learned buttons. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    // Stable preference identity; Xiaomi RC scan code 400 maps here independently of Android key code.
    static final int KEY_MI = -10001;
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL, KEY_MI};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        AppStrings.init(app);
        // Legacy manufacturer app-key settings have been retired in favor of learned configurations.
        new File(app.getFilesDir(), "remote-keys.txt").delete();
        SharedPreferences.Editor retiredKeys = sp().edit();
        for (int key : KEYS) retiredKeys.remove("key" + key);
        retiredKeys.apply();
        removeRetiredWidgetData();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /** Delete retired widget/category keys; keep weather, region and other settings. */
    private static void removeRetiredWidgetData() {
        SharedPreferences settings = sp();
        String[] retired = {"quotes", "coin0", "coin1", "coin2", "coin3", "showDetectives", "category_2", "showRandomMovie", "category_4"};
        SharedPreferences.Editor edit = null;
        for (String key : retired) if (settings.contains(key)) {
            if (edit == null) edit = settings.edit();
            edit.remove(key);
        }
        if (edit != null) edit.apply();
        SharedPreferences data = app.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        if (data.contains("prices") || data.contains("pricesAt"))
            data.edit().remove("prices").remove("pricesAt").apply();
    }

    private static SharedPreferences sp() {
        return app.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    private static void put(String key, String value) {
        sp().edit().putString(key, value).apply();
        version++;
    }

    private static void put(String key, boolean value) {
        sp().edit().putBoolean(key, value).apply();
        version++;
    }

    static String recommendationRegion() {
        return io.tvos.catalog.RecommendationRegion.resolve(sp().getString("setup_country", ""), Config.TMDB_REGION);
    }
    static void setRecommendationRegion(String code) {
        String country = io.tvos.catalog.RecommendationRegion.normalize(code);
        if (country.isEmpty() || country.equals(sp().getString("setup_country", ""))) return;
        sp().edit().putString("setup_country", country).apply(); version++;
        Recs.regionChanged();
    }
    static void setLiveTvApp(String pkg) { put("live_tv_app", pkg); }

    static boolean homeContinue() {
        if (app == null) return false;
        return "continue".equals(sp().getString("home_screen_mode",
                sp().getBoolean("dock_continue", false) ? "continue" : "recommendations"));
    }
    static void setHomeContinue(boolean enabled) {
        put("home_screen_mode", enabled ? "continue" : "recommendations");
    }

    // MARK: - Language and units

    /** Supported codes and fallback behavior come from the shared language catalog. */
    static String language() {
        String v = app == null ? Config.LANGUAGE : sp().getString("language", Config.LANGUAGE);
        return AppLanguage.from(v).code;
    }

    static void setLanguage(String v) {
        String previous = language();
        put("language", v);
        Ui.applyLanguage();
        if (!previous.equals(language())) {
            Recs.languageChanged();
            HomeData.get(app).localizeCity(language());
        }
    }

    /** "auto", "C" or "F". */
    static String temperature() {
        String v = sp().getString("temperature", Config.TEMPERATURE).trim().toUpperCase(Locale.ROOT);
        return v.startsWith("F") ? "F" : v.startsWith("C") ? "C" : "auto";
    }

    static void setTemperature(String v) { put("temperature", v); }

    /** "auto" (dark from sunset to sunrise), "light" or "dark". */
    static String theme() {
        String v = sp().getString("theme", "auto");
        return v.equals("light") || v.equals("dark") ? v : "auto";
    }

    static void setTheme(String v) { put("theme", v); }

    /** Launcher-only time format, independent of Android system clock settings. */
    static boolean hour24() { return app == null || sp().getBoolean("clock_24h", true); }
    static void setHour24(boolean enabled) { put("clock_24h", enabled); }

    // MARK: - City

    static String cityRaw() { return sp().getString("city", Config.CITY).trim(); }
    private static String cityPlace() { return latitude() + "," + longitude(); }
    static long cityId() { return sp().getLong("city_id", 0); }
    static boolean hasCityName(String language) {
        return cityPlace().equals(sp().getString("city_names_place", ""))
                && !sp().getString("city_name_" + language, "").isEmpty();
    }
    static String city() {
        return hasCityName(language()) ? sp().getString("city_name_" + language(), "") : cityRaw();
    }
    static String latitude() { return sp().getString("latitude", Config.LATITUDE); }
    static String longitude() { return sp().getString("longitude", Config.LONGITUDE); }

    static void setCity(String name, String latitude, String longitude) {
        setCity(name, latitude, longitude, 0, language());
    }

    static void setCity(String name, String latitude, String longitude, long id, String nameLanguage) {
        sp().edit().putString("city", name).putString("latitude", latitude).putString("longitude", longitude)
                .putLong("city_id", id).putString("city_names_place", latitude + "," + longitude)
                .remove("city_name_ru").remove("city_name_en").putString("city_name_" + nameLanguage, name).apply();
        version++;
        HomeData data = HomeData.get(app);
        data.localizeCity("ru");
        data.localizeCity("en");
    }

    /** A late name response must never relabel a different selected location. */
    static boolean cacheCityName(String name, String language, String latitude, String longitude, long id) {
        if (name == null || name.trim().isEmpty() || !cityPlace().equals(latitude + "," + longitude)
                || (id > 0 && cityId() > 0 && id != cityId())) return false;
        if (hasCityName(language) && name.equals(sp().getString("city_name_" + language, ""))) return false;
        SharedPreferences.Editor editor = sp().edit();
        if (!cityPlace().equals(sp().getString("city_names_place", "")))
            editor.remove("city_name_ru").remove("city_name_en");
        editor.putString("city_names_place", cityPlace()).putString("city_name_" + language, name);
        if (id > 0 && cityId() == 0) editor.putLong("city_id", id);
        editor.apply();
        if (language.equals(language())) version++;
        return true;
    }

    static boolean automaticRegion() { return sp().getBoolean("region_auto", false); }
    static long regionGeneration() { return sp().getLong("region_generation", 0); }
    static String manualCity() {
        if (!automaticRegion()) return city();
        String localized = sp().getString("region_manual_city_name_" + language(), "");
        return localized.isEmpty() ? sp().getString("region_manual_city", Config.CITY) : localized;
    }
    static void setAutomaticRegion(boolean enabled) {
        if (enabled == automaticRegion()) return;
        SharedPreferences.Editor editor = sp().edit();
        String[] keys = {"city", "latitude", "longitude", "city_names_place", "city_name_ru", "city_name_en"};
        if (enabled) {
            for (String key : keys) {
                String fallback = key.equals("city") ? Config.CITY : key.equals("latitude") ? Config.LATITUDE
                        : key.equals("longitude") ? Config.LONGITUDE : "";
                editor.putString("region_manual_" + key, sp().getString(key, fallback));
            }
            editor.putLong("region_manual_city_id", cityId());
        } else {
            for (String key : keys) editor.putString(key, sp().getString("region_manual_" + key, ""));
            editor.putLong("city_id", sp().getLong("region_manual_city_id", 0));
        }
        editor.putBoolean("region_auto", enabled).putLong("region_generation", regionGeneration() + 1).apply();
        version++;
        HomeData.get(app).settingsChanged();
    }
    static boolean applyAutomaticCity(String name, String lat, String lon, String language, long generation) {
        if (!automaticRegion() || generation != regionGeneration()) return false;
        if (!cityRaw().equals(name) || !latitude().equals(lat) || !longitude().equals(lon)) {
            setCity(name, lat, lon, 0, language);
            HomeData.get(app).settingsChanged();
        }
        return true;
    }

    // MARK: - Weather

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    // Home screen recommendation sections (enabled by default).
    static String watchingSource() { return sp().getString("watching_source", "all"); }
    static void setWatchingSource(String source) { put("watching_source", source); }


    // Keep existing IDs stable; Watching uses the vacant ID 2 with its own preference key.
    private static int categoryVersion = -1, categoryMask;
    private static int[] categoryOrderCache;

    private static void readCategories() {
        if (categoryOrderCache != null && categoryVersion == version) return;
        int[] order = new int[4];
        boolean[] seen = new boolean[4];
        int count = 0;
        for (String value : sp().getString("category_order", "0,1,3,2").split(",")) {
            try {
                int id = Integer.parseInt(value);
                if (id >= 0 && id < 4 && !seen[id]) { order[count++] = id; seen[id] = true; }
            } catch (NumberFormatException ignored) { }
        }
        for (int id = 0; id < 4; id++) if (!seen[id]) order[count++] = id;
        categoryMask = 0;
        for (int id = 0; id < 4; id++) {
            boolean enabled = sp().getBoolean(id == 2 ? "category_watching" : "category_" + id, id != 2);
            if (enabled) categoryMask |= 1 << id;
        }
        categoryOrderCache = order;
        categoryVersion = version;
    }

    /** Shared read-only snapshot; rebuilt only after settings change, never during each animation frame. */
    static int[] categoryOrder() { readCategories(); return categoryOrderCache; }
    static boolean categoryEnabled(int id) { readCategories(); return id >= 0 && id < 4 && (categoryMask & 1 << id) != 0; }
    static int firstCategory() { for (int id : categoryOrder()) if (categoryEnabled(id)) return id; return -1; }
    static int lastCategory() {
        int result = -1;
        for (int id : categoryOrder()) if (categoryEnabled(id)) result = id;
        return result;
    }
    static int categoryCount() { readCategories(); return Integer.bitCount(categoryMask); }
    static int categoryPosition(int id) {
        int position = 0;
        for (int candidate : categoryOrder()) if (categoryEnabled(candidate)) {
            position++;
            if (candidate == id) return position;
        }
        return 0;
    }

    static void setCategoryEnabled(int id, boolean enabled) {
        if (id < 0 || id >= 4) throw new IllegalArgumentException("Invalid category");
        put(id == 2 ? "category_watching" : "category_" + id, enabled);
    }

    static void setCategoryOrder(List<Integer> order) {
        if (order.size() != 4) throw new IllegalArgumentException("Incomplete categories");
        boolean[] seen = new boolean[4];
        StringBuilder value = new StringBuilder();
        for (Integer id : order) {
            if (id == null || id < 0 || id >= 4 || seen[id]) throw new IllegalArgumentException("Invalid category order");
            seen[id] = true;
            if (value.length() > 0) value.append(',');
            value.append(id);
        }
        put("category_order", value.toString());
    }

    // Menu Bar action IDs: Search, TV, VPN, Settings, Clock, Network.
    private static final int[] DEFAULT_TOP_BUTTON_ORDER = {0, 1, 2, 3, 5, 4};
    private static int topButtonVersion = -1, topButtonMask;
    private static int[] topButtonOrderCache;

    private static void readTopButtons() {
        if (topButtonOrderCache != null && topButtonVersion == version) return;
        int[] order = new int[6];
        boolean[] seen = new boolean[6];
        int count = 0;
        for (String value : sp().getString("top_button_order", "0,1,2,3,5,4").split(",")) {
            try {
                int id = Integer.parseInt(value);
                if (id >= 0 && id < 6 && !seen[id]) { order[count++] = id; seen[id] = true; }
            } catch (NumberFormatException ignored) {}
        }
        for (int id : DEFAULT_TOP_BUTTON_ORDER) if (!seen[id]) order[count++] = id;
        topButtonMask = 0;
        for (int id = 0; id < 6; id++)
            if (sp().getBoolean("top_button_" + id, id == 0 || id == 3 || id == 4)) topButtonMask |= 1 << id;
        topButtonOrderCache = order;
        topButtonVersion = version;
    }

    static int[] topButtonOrder() { readTopButtons(); return topButtonOrderCache; }
    static boolean topButtonEnabled(int id) {
        readTopButtons();
        return id >= 0 && id < 6 && (topButtonMask & 1 << id) != 0;
    }
    static int topButtonCount() { readTopButtons(); return Integer.bitCount(topButtonMask); }
    static int topButtonPosition(int id) {
        int position = 0;
        for (int candidate : topButtonOrder()) if (topButtonEnabled(candidate)) {
            position++;
            if (candidate == id) return position;
        }
        return 0;
    }
    static void setTopButtonEnabled(int id, boolean enabled) {
        if (id < 0 || id >= 6) throw new IllegalArgumentException("Invalid Menu Bar button");
        put("top_button_" + id, enabled);
    }
    static void setTopButtonOrder(List<Integer> order) {
        if (order.size() != 6) throw new IllegalArgumentException("Incomplete Menu Bar buttons");
        boolean[] seen = new boolean[6];
        StringBuilder value = new StringBuilder();
        for (Integer id : order) {
            if (id == null || id < 0 || id >= 6 || seen[id])
                throw new IllegalArgumentException("Invalid Menu Bar button order");
            seen[id] = true;
            if (value.length() > 0) value.append(',');
            value.append(id);
        }
        put("top_button_order", value.toString());
    }

    // MARK: - Shelf

    /** Packages on the home shelf, in order; null until the user edits it (then the built-in list applies). */
    static List<String> shelf() {
        String v = sp().getString("shelf", null);
        if (v == null) return null;
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    static void setShelf(List<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String p : packages) sb.append(sb.length() == 0 ? "" : ",").append(p);
        put("shelf", sb.toString());
    }

    // Compatibility for retired screen IDs. App-button presets no longer store or dispatch actions.
    static String keyApp(int keyCode) { return ""; }
    static void setKeyApp(int keyCode, String pkg) { }
}
