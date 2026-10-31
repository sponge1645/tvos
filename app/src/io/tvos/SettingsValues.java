package io.tvos;

import io.tvos.version.SoftwareBuild;

import static io.tvos.SettingsContent.*;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Current settings labels/values and platform reads; no Canvas, navigation or stored UI state. */
final class SettingsValues {
    private static final int CITY_ROW = 4;
    private final Activity activity;
    private final SettingsOrderEditor buttonEditor;
    private final BooleanSupplier freeActive;
    private final Predicate<Boolean> weatherVisible;
    private final Supplier<String> cityStatus;

    SettingsValues(Activity activity, SettingsOrderEditor buttonEditor, BooleanSupplier freeActive,
                   Predicate<Boolean> weatherVisible, Supplier<String> cityStatus) {
        this.activity = activity;
        this.buttonEditor = buttonEditor;
        this.freeActive = freeActive;
        this.weatherVisible = weatherVisible;
        this.cityStatus = cityStatus;
    }

    String vpnApp() {
        String pkg = localPrefs().getString("vpn_app", "");
        if (pkg.isEmpty()) return AppStrings.get(R.string.settings_not_configured);
        String language = AppLanguage.current().code;
        if (pkg.equals(vpnPackage) && language.equals(vpnLanguage)) return vpnLabel;
        try { vpnLabel = String.valueOf(activity.getPackageManager().getApplicationLabel(activity.getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (android.content.pm.PackageManager.NameNotFoundException | SecurityException e) { vpnLabel = AppStrings.get(R.string.settings_not_found); }
        vpnPackage = pkg; vpnLanguage = language;
        return vpnLabel;
    }

    private String vpnPackage, vpnLanguage, vpnLabel;
    void clearAppCache() { vpnPackage = vpnLanguage = vpnLabel = null; }

    private SharedPreferences localPrefs() { return activity.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    private boolean localFlag(String key) { return localPrefs().getBoolean(key, false); }

    // Keep the same display/Wi-Fi reads on Android 9+ TVs during this extraction.
    @SuppressWarnings("deprecation")
    String[] menu(int screen, int rowCount) {
        String[] v = new String[rowCount];
        java.util.Arrays.fill(v, "");
        if (screen == MEDIA) {
            v[0] = MovieService.label(MovieService.index(localPrefs().getString(MovieService.KEY, "")), AppLanguage.current());
            v[1] = onOff(localFlag(DockContinue.KEY));
        } else if (screen == MOVIE_SERVICE) {
            v[MovieService.index(localPrefs().getString(MovieService.KEY, ""))] = AppStrings.get(R.string.settings_service_selected);
        } else if (screen == GENERAL) {
            String theme = Prefs.theme();
            v[1] = "light".equals(theme) ? AppStrings.get(R.string.settings_light) : "dark".equals(theme) ? AppStrings.get(R.string.settings_dark) : AppStrings.get(R.string.settings_automatic);
            v[2] = settings()[0];
            v[3] = Prefs.city();
            v[4] = "";
            v[5] = settings()[1];
            v[6] = Prefs.hour24() ? AppStrings.get(R.string.settings_24_h) : AppStrings.get(R.string.settings_12_h);
            v[7] = onOff(localPrefs().getBoolean("navigation_clicks", true));

        } else if (screen == UPDATES) {
            v[1] = onOff(localPrefs().getBoolean("auto_update", false));
        } else if (screen == ACCESSIBILITY) {
            v[0] = onOff(localFlag("reduce_transparency"));
            v[1] = onOff(localFlag("reduce_motion"));
        } else if (screen == SYSTEM) {
            v[2] = freeActive.getAsBoolean() ? "tvOS" : AppStrings.get(R.string.settings_built_in);
        } else if (screen == REGION) {
            v[0] = onOff(Prefs.automaticRegion());
            v[1] = !cityStatus.get().isEmpty() ? cityStatus.get() : Prefs.manualCity();
        } else if (screen == MENU_BUTTONS) {
            for (int i = 0; i < buttonEditor.size(); i++) {
                int id = buttonEditor.get(i);
                v[i] = Prefs.topButtonEnabled(id) ? "#" + Prefs.topButtonPosition(id) : AppStrings.get(R.string.settings_off);
            }
        } else if (screen == SCREENSAVER) {
            String src = localPrefs().getString("video_dream_source", "google");
            v[0] = "apple".equals(src) ? "Aerial" : "na".equals(src) ? "National Geographic" : "Google";
            v[1] = onOff(localPrefs().getBoolean("aerial_show_clock", false));
            v[2] = onOff(localPrefs().getBoolean("aerial_show_location", false));
            v[3] = onOff(localPrefs().getBoolean("screensaver_show_weather", false));
        } else if (screen == WIDGETS) {
            // Date is a submenu; its switch is shown inside DATE_TIME.
        } else if (screen == DATE_TIME) {
            v[0] = onOff(localPrefs().getBoolean("show_date", true));
        } else if (screen == WEATHER) {
            v[0] = onOff(weatherVisible.test(true)); v[1] = onOff(weatherVisible.test(false));
        } else if (screen == ABOUT) {
            v[0] = android.os.Build.MODEL == null || android.os.Build.MODEL.trim().isEmpty()
                    ? AppStrings.get(R.string.settings_unknown) : android.os.Build.MODEL.trim();
            v[1] = AppStrings.get(R.string.settings_unavailable);
            try {
                String serial = android.os.Build.getSerial();
                if (serial != null && !serial.isEmpty() && !"unknown".equalsIgnoreCase(serial)) v[1] = serial;
            } catch (Exception ignored) {}
            try {
                v[2] = SoftwareBuild.display(activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).versionName);
            } catch (Exception e) { v[2] = "—"; }
            v[3] = "Android " + android.os.Build.VERSION.RELEASE;
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            v[4] = dm.widthPixels + " × " + dm.heightPixels;
            v[5] = AppStrings.get(R.string.settings_network_unavailable);
            v[6] = "—";
            v[7] = localPrefs().getString("device_name", "ТВ");
            LocalNetwork.Info network = LocalNetwork.read(activity);
            if (network != null) {
                v[6] = network.address;
                if (network.ethernet) v[5] = "Ethernet";
                else {
                    v[5] = AppStrings.get(R.string.settings_wi_fi_name_unavailable);
                    try {
                        android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                                activity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        android.net.wifi.WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                        String ssid = wi == null ? null : wi.getSSID();
                        if (ssid != null && !ssid.isEmpty() && !ssid.contains("unknown ssid"))
                            v[5] = ssid.replace("\"", "");
                    } catch (Exception ignored) {}
                }
            }
            // Show device name first, preserving all other values.
            String deviceName = v[7];
            System.arraycopy(v, 0, v, 1, 7);
            v[0] = deviceName;
        }
        return v;
    }

    static int randomRow() { return 5; }

    static String[] settingLabels() {
        List<String> l = new ArrayList<>();
        l.add(AppStrings.get(R.string.settings_language));
        l.add(AppStrings.get(R.string.settings_temperature));
        l.add(AppStrings.get(R.string.settings_theme));
        l.add(AppStrings.get(R.string.settings_weather_widget));
        l.add(AppStrings.get(R.string.settings_city));
        l.add(AppStrings.get(R.string.settings_random_movie));
        return l.toArray(new String[0]);
    }

    String[] settings() {
        String[] labels = settingLabels();
        String[] v = new String[labels.length];
        v[0] = AppLanguage.from(Prefs.language()).nativeName;
        String unit = Prefs.temperature();
        v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : AppStrings.get(R.string.settings_automatic_value);
        String theme = Prefs.theme();
        v[2] = theme.equals("light") ? AppStrings.get(R.string.settings_theme_light) : theme.equals("dark") ? AppStrings.get(R.string.settings_theme_dark) : AppStrings.get(R.string.settings_automatic_value);
        v[3] = onOff(Prefs.weather());
        v[CITY_ROW] = !cityStatus.get().isEmpty() ? cityStatus.get() : Prefs.city().isEmpty() ? "—" : Prefs.city();
        v[randomRow()] = onOff(Prefs.randomMovie());
        return v;
    }

    static String onOff(boolean on) {
        return on ? AppStrings.get(R.string.settings_on) : AppStrings.get(R.string.settings_off);
    }

    static String[] keyLabels() {
        return new String[] {AppStrings.get(R.string.settings_netflix_button), AppStrings.get(R.string.settings_youtube_button),
                AppStrings.get(R.string.settings_prime_video_button), AppStrings.get(R.string.settings_tcl_channel_button), AppStrings.get(R.string.settings_mi_button)};
    }

    static String[] keyValues(List<String> appPkgs, List<String> appLabels) {
        String[] v = new String[Prefs.KEYS.length];
        for (int i = 0; i < v.length; i++) {
            String pkg = Prefs.keyApp(Prefs.KEYS[i]);
            int at = appPkgs.indexOf(pkg);
            v[i] = pkg != null && !pkg.isEmpty() && at >= 0 ? appLabels.get(at)
                    : AppStrings.get(R.string.settings_not_configured);
        }
        return v;
    }

}
