package io.tvos;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings changed on the TV itself (tvOS > Settings). config.properties only gives the first
 * values; anything the user changes here wins. Read from the :home process (home screen, screensaver, settings).
 */
final class Prefs {
    private Prefs() {}

    /** Remote keys we handle, with the app each opens until the user picks another. Codes read from the TCL's log. */
    static final int KEY_NETFLIX = 4062, KEY_YOUTUBE = 190, KEY_PRIME = 4099, KEY_TCL_CHANNEL = 4020;
    static final int[] KEYS = {KEY_NETFLIX, KEY_YOUTUBE, KEY_PRIME, KEY_TCL_CHANNEL};

    /** Bumped by every change: screens compare it to know when to redraw. */
    static volatile int version;

    private static Context app;

    static void init(Context c) {
        app = c.getApplicationContext();
        AppStrings.init(app);
        migrateKeys();   // before anything reads the button choices
        removeRetiredWidgetData();
        Ui.applyLanguage();   // the language picked on the TV, now that it can be read
    }

    /** Delete only obsolete widget keys; keep weather, region and all other settings. */
    private static void removeRetiredWidgetData() {
        SharedPreferences settings = sp();
        String[] retired = {"quotes", "coin0", "coin1", "coin2", "coin3"};
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

    // MARK: - Weather

    static boolean weather() { return sp().getBoolean("weather", true); }
    static void setWeather(boolean on) { put("weather", on); }

    // Home screen recommendation sections (enabled by default).
    static boolean detectives() { return sp().getBoolean("showDetectives", false); }
    static void setDetectives(boolean on) { put("showDetectives", on); }
    static boolean randomMovie() { return sp().getBoolean("showRandomMovie", true); }
    static void setRandomMovie(boolean on) { put("showRandomMovie", on); }

    // Category IDs remain stable: New, Movies, Detectives, Shows, Random Movie.
    private static int categoryVersion = -1, categoryMask;
    private static int[] categoryOrderCache;

    private static void readCategories() {
        if (categoryOrderCache != null && categoryVersion == version) return;
        int[] order = new int[5];
        boolean[] seen = new boolean[5];
        int count = 0;
        for (String value : sp().getString("category_order", "0,1,2,3,4").split(",")) {
            try {
                int id = Integer.parseInt(value);
                if (id >= 0 && id < 5 && !seen[id]) { order[count++] = id; seen[id] = true; }
            } catch (NumberFormatException ignored) { }
        }
        for (int id = 0; id < 5; id++) if (!seen[id]) order[count++] = id;
        categoryMask = 0;
        for (int id = 0; id < 5; id++) {
            boolean enabled = id == 2 ? detectives() : id == 4 ? randomMovie()
                    : sp().getBoolean("category_" + id, true);
            if (enabled) categoryMask |= 1 << id;
        }
        categoryOrderCache = order;
        categoryVersion = version;
    }

    /** Shared read-only snapshot; rebuilt only after settings change, never during each animation frame. */
    static int[] categoryOrder() { readCategories(); return categoryOrderCache; }
    static boolean categoryEnabled(int id) { readCategories(); return id >= 0 && id < 5 && (categoryMask & 1 << id) != 0; }
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
        if (id < 0 || id >= 5) throw new IllegalArgumentException("Invalid category");
        if (id == 2) setDetectives(enabled);
        else if (id == 4) setRandomMovie(enabled);
        else put("category_" + id, enabled);
    }

    static void setCategoryOrder(List<Integer> order) {
        if (order.size() != 5) throw new IllegalArgumentException("Incomplete categories");
        boolean[] seen = new boolean[5];
        StringBuilder value = new StringBuilder();
        for (Integer id : order) {
            if (id == null || id < 0 || id >= 5 || seen[id]) throw new IllegalArgumentException("Invalid category order");
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
            if (sp().getBoolean("top_button_" + id, id != 2)) topButtonMask |= 1 << id;
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

    // MARK: - Remote buttons

    private static String defaultApp(int keyCode) {
        switch (keyCode) {
            case KEY_NETFLIX: return "com.netflix.ninja";
            case KEY_YOUTUBE: return "com.google.android.youtube.tv";
            case KEY_PRIME: return "com.amazon.amazonvideo.livingroom";
            case KEY_TCL_CHANNEL: return "";
            default: return "";
        }
    }

    // The key service runs in its own small process (:keys), so that a busy home screen can never delay the TV's keys.
    // SharedPreferences are not shared between processes, so these few values live in a tiny file both processes read.
    // The service asks on every key: the values stay in memory and the file is read again only when it changes.
    private static File keysFile() {
        return new File(app.getFilesDir(), "remote-keys.txt");
    }

    private static final String[] keyApps = new String[KEYS.length];   // null = the default
    private static long keysStamp = Long.MIN_VALUE;

    /** A temporary file per process (two can start together after an update), synced before it replaces the old one. */
    private static boolean writeKeys(String text) {
        File tmp = new File(app.getFilesDir(), "remote-keys." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(keysFile());
    }

    /** Choices saved before they moved to the file (in SharedPreferences) are copied there once. */
    private static void migrateKeys() {
        if (keysFile().exists()) return;
        SharedPreferences old = sp();
        boolean any = false;
        for (int key : KEYS) any |= old.contains("key" + key);
        if (!any) return;
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(old.getString("key" + key, defaultApp(key))).append('\n');
        writeKeys(sb.toString());
    }

    /** Package the key opens ("" = nothing). */
    static String keyApp(int keyCode) {
        synchronized (keyApps) {
            File f = keysFile();
            long stamp = f.lastModified() * 31 + f.length();   // 0 without the file: every key opens its default
            if (stamp != keysStamp) {
                keysStamp = stamp;
                java.util.Arrays.fill(keyApps, null);
                if (stamp != 0) {
                    try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            for (int i = 0; i < KEYS.length; i++) {
                                String prefix = KEYS[i] + "=";
                                if (line.startsWith(prefix)) keyApps[i] = line.substring(prefix.length());
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i] == keyCode && keyApps[i] != null) return keyApps[i];
            return defaultApp(keyCode);
        }
    }

    static void setKeyApp(int keyCode, String pkg) {
        StringBuilder sb = new StringBuilder();
        for (int key : KEYS) sb.append(key).append('=').append(key == keyCode ? pkg : keyApp(key)).append('\n');
        if (writeKeys(sb.toString())) version++;
    }
}
