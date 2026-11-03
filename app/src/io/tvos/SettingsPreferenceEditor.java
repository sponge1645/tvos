package io.tvos;

import android.content.SharedPreferences;

/** Preference mutation adapter; no Activity, screen navigation or rendering state. */
final class SettingsPreferenceEditor {
    private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
    private static final String[] DREAM_SOURCES = {"apple", "google", "na"};
    private static final int CITY_ROW = 4;
    private final SharedPreferences preferences;
    private final Runnable changed, refresh;

    SettingsPreferenceEditor(SharedPreferences preferences, Runnable changed, Runnable refresh) {
        this.preferences = preferences;
        this.changed = changed;
        this.refresh = refresh;
    }

    boolean flag(String key) { return preferences.getBoolean(key, false); }
    void setFlag(String key, boolean value) {
        preferences.edit().putBoolean(key, value).apply();
        refresh.run();
    }

    void cycleDreamSource(int direction) {
        String[] choices = DREAM_SOURCES;
        String value = preferences.getString("video_dream_source", "google");
        int at = 0;
        for (int i = 0; i < choices.length; i++) if (choices[i].equals(value)) at = i;
        preferences.edit().putString("video_dream_source", choices[(at + direction + choices.length) % choices.length]).apply();
        refresh.run();
    }

    boolean weatherVisible(boolean top) {
        if (!Prefs.weather()) return false;
        String key = top ? "weather_show_top" : "weather_show_bottom";
        if (preferences.contains(key)) return preferences.getBoolean(key, true);
        int old = preferences.getInt("weather_placement", 1);
        return top ? (old == 1 || old == 3) : (old == 2 || old == 3);
    }

    void toggleWeather(boolean top) {
        boolean newTop = top ? !weatherVisible(true) : weatherVisible(true);
        boolean newBottom = top ? weatherVisible(false) : !weatherVisible(false);
        preferences.edit()
                .putBoolean("weather_show_top", newTop)
                .putBoolean("weather_show_bottom", newBottom)
                .putInt("weather_placement", (newTop ? 1 : 0) | (newBottom ? 2 : 0))
                .apply();
        Prefs.setWeather(newTop || newBottom);
        changed.run();
        refresh.run();
    }

    void toggleDateTime() {
        String key = "show_date";
        setFlag(key, !preferences.getBoolean(key, false));
    }

    void temperature(int dir) { Prefs.setTemperature(step(UNITS, Prefs.temperature(), dir)); changed.run(); }
    void cycleWeather(int dir) {
        int current = (weatherVisible(true) ? 1 : 0) | (weatherVisible(false) ? 2 : 0);
        int next = 1 + Math.floorMod(Math.max(1, current) - 1 + dir, 3);
        preferences.edit().putBoolean("weather_show_top", (next & 1) != 0)
                .putBoolean("weather_show_bottom", (next & 2) != 0).putInt("weather_placement", next).apply();
        Prefs.setWeather(true); changed.run(); refresh.run();
    }
    void general(int row, int dir) {
        if (row == 1) Prefs.setTheme(step(THEMES, Prefs.theme(), dir));
        else if (row == 4) Prefs.setHour24(!Prefs.hour24());
        changed.run();
    }

    void legacy(int selected, int dir) {
        switch (selected) {
            case 0: Prefs.setLanguage(AppLanguage.step(Prefs.language(), dir).code); break;
            case 1: Prefs.setTemperature(step(UNITS, Prefs.temperature(), dir)); break;
            case 2: Prefs.setTheme(step(THEMES, Prefs.theme(), dir)); break;
            case 3: Prefs.setWeather(!Prefs.weather()); break;
            case CITY_ROW: return;
            default: return;
        }
        changed.run();
    }
    private static String step(String[] choices, String value, int direction) {
        int at = -1;
        for (int i = 0; i < choices.length; i++) if (choices[i].equals(value)) { at = i; break; }
        return choices[(at + direction + choices.length) % choices.length];
    }
}
