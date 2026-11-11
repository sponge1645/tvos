package io.tvos;

import io.tvos.settings.SettingsOrderEditor;
import io.tvos.settings.SettingsScreen;

import io.tvos.version.SoftwareBuild;

import static io.tvos.settings.SettingsScreen.*;
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
    private final SettingsValueLoader<String> labels;
    private final SettingsValueLoader<String[]> device;
    private String updateInstalledVersion, updateCheckShown;
    private long updateCheckAt = Long.MIN_VALUE;
    private AppLanguage updateCheckLanguage;
    private final SettingsOrderEditor buttonEditor;
    private final BooleanSupplier freeActive;
    private final Predicate<Boolean> weatherVisible;
    private final Supplier<String> cityStatus;

    SettingsValues(Activity activity, SettingsOrderEditor buttonEditor, BooleanSupplier freeActive,
                   Predicate<Boolean> weatherVisible, Supplier<String> cityStatus,
                   SettingsValueLoader<String> labels, SettingsValueLoader<String[]> device) {
        this.activity = activity;
        this.labels = labels; this.device = device;
        this.buttonEditor = buttonEditor;
        this.freeActive = freeActive;
        this.weatherVisible = weatherVisible;
        this.cityStatus = cityStatus;
    }

    String vpnApp() {
        String pkg = localPrefs().getString("vpn_app", "");
        if ("su.happ.proxyutility".equals(pkg)) return "HAPP";
        if ("llc.itdev.incy".equals(pkg)) return "INCY";
        return AppStrings.get(R.string.settings_not_selected);
    }

    private String automaticTvLabel() {
        String missing = AppStrings.get(R.string.settings_tv_unselected);
        return labels.get("automatic-tv:" + AppLanguage.current().code, "…", () -> {
            try {
                android.content.Intent intent = HomeApps.automaticLiveTv(activity);
                android.content.pm.ResolveInfo info = intent == null ? null : activity.getPackageManager().resolveActivity(intent, 0);
                if (info != null && info.activityInfo != null)
                    return String.valueOf(info.activityInfo.applicationInfo.loadLabel(activity.getPackageManager()));
            } catch (RuntimeException unavailable) { AppLog.w("Automatic TV label unavailable"); }
            return missing;
        });
    }
    private String tvProviderLabel(String target) {
        return labels.get("tv:" + AppLanguage.current().code + ":" + target, "…", () -> {
            String pkg = LiveTvTargets.packageName(target);
            if (!pkg.isEmpty()) try {
                return String.valueOf(activity.getPackageManager().getApplicationLabel(activity.getPackageManager().getApplicationInfo(pkg, 0)));
            } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) { /* Keep explicit target. */ }
            return target;
        });
    }
    void clearAppCache() { labels.clear(); device.clear(); }
    void pause() { clearAppCache(); }

    private SharedPreferences localPrefs() { return activity.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    private boolean localFlag(String key) { return localPrefs().getBoolean(key, false); }

    int selection(int screen) {
        switch (screen) {
            case THEME_CHOICE: return Math.max(0, java.util.Arrays.asList("auto", "light", "dark").indexOf(Prefs.theme()));
            case TEMPERATURE_CHOICE: return Math.max(0, java.util.Arrays.asList("auto", "C", "F").indexOf(Prefs.temperature()));
            case WEATHER_CHOICE: return (weatherVisible.test(true) ? 1 : 0) | (weatherVisible.test(false) ? 2 : 0);
            case SLIDESHOW_STYLE: return Math.max(0, java.util.Arrays.asList("kenburns", "classic", "slide").indexOf(localPrefs().getString("icloud_style", "kenburns")));
            case DREAM_CHOICE: return Math.max(0, java.util.Arrays.asList("apple", "google", "na", "icloud").indexOf(localPrefs().getString("video_dream_source", "apple")));
            default: return -1;
        }
    }

    // Keep the same display/Wi-Fi reads on Android 9+ TVs during this extraction.
    @SuppressWarnings("deprecation")
    String[] menu(int screen, int rowCount) {
        String[] v = new String[rowCount];
        java.util.Arrays.fill(v, "");
        if (SettingsScreen.multiChoice(screen)) {
            v[selection(screen)] = AppStrings.get(R.string.settings_service_selected);
        } else if (screen == MEDIA) {
            v[0] = MovieService.label(MovieService.index(localPrefs().getString(MovieService.KEY, "")), AppLanguage.current());
            v[1] = onOff(localPrefs().getBoolean("navigation_clicks", true));
            String target = localPrefs().getString("live_tv_app", "");
            v[2] = target.isEmpty() || "automatic".equals(target) ? automaticTvLabel() : tvProviderLabel(target);
        } else if (screen == HOME_SCREEN) {
            v[0] = AppStrings.get(Prefs.homeCollection() ? R.string.settings_home_collection : R.string.settings_home_tvos);
            v[1] = AppStrings.get(Prefs.homeContinue() ? R.string.home_continue_watching : R.string.settings_home_recommendations);
        } else if (screen == HOME_VIEW) {
            v[Prefs.homeCollection() ? 0 : 1] = AppStrings.get(R.string.settings_service_selected);
        } else if (screen == HOME_CARD_STYLE) {
            v[Prefs.homeContinue() ? 0 : 1] = AppStrings.get(R.string.settings_service_selected);
        } else if (screen == MOVIE_SERVICE) {
            v[MovieService.index(localPrefs().getString(MovieService.KEY, ""))] = AppStrings.get(R.string.settings_service_selected);
        } else if (screen == GENERAL) {
            String theme = Prefs.theme();
            v[1] = "light".equals(theme) ? AppStrings.get(R.string.settings_light) : "dark".equals(theme) ? AppStrings.get(R.string.settings_dark) : AppStrings.get(R.string.settings_automatic);
            int sleep = localPrefs().getInt("sleep_choice", -1);
            v[2] = sleep >= 0 && sleep < 7 ? SettingsContent.menuLabels(SLEEP_TIMER)[sleep] : AppStrings.get(R.string.settings_sleep_unset);
            v[3] = settings()[0];
            v[4] = io.tvos.setup.SetupCountries.name(Prefs.recommendationRegion(), AppLanguage.current().locale);
            v[5] = Prefs.hour24() ? AppStrings.get(R.string.settings_24_h) : AppStrings.get(R.string.settings_12_h);

        } else if (screen == SLEEP_TIMER) {
            int selected = localPrefs().getInt("sleep_choice", -1);
            if (selected >= 0 && selected < v.length) v[selected] = AppStrings.get(R.string.settings_service_selected);
        } else if (screen == LANGUAGE) {
            v["ru".equals(Prefs.language()) ? 0 : 1] = AppStrings.get(R.string.settings_service_selected);
        } else if (screen == UPDATES) {
            v[1] = onOff(localPrefs().getBoolean("auto_update", false));
        } else if (screen == ACCESSIBILITY) {
            v[0] = onOff(localFlag("reduce_transparency"));
            v[1] = onOff(localFlag("reduce_motion"));
        } else if (screen == SYSTEM) {
            v[2] = freeActive.getAsBoolean() ? "tvOS" : AppStrings.get(R.string.settings_built_in);
        } else if (screen == CITY) {
            v[0] = onOff(Prefs.automaticRegion());
            v[1] = !cityStatus.get().isEmpty() ? cityStatus.get() : Prefs.manualCity();
        } else if (screen == MENU_BUTTONS) {
            for (int i = 0; i < buttonEditor.size(); i++) {
                int id = buttonEditor.get(i);
                v[i] = Prefs.topButtonEnabled(id) ? "#" + Prefs.topButtonPosition(id) : AppStrings.get(R.string.settings_off);
            }
        } else if (screen == SCREENSAVER) {
            String src = localPrefs().getString("video_dream_source", "apple");
            v[0] = "icloud".equals(src) ? AppStrings.get(R.string.icloud_photos) : "apple".equals(src) ? "Aerial" : "na".equals(src) ? "National Geographic" : "Google";
            v[1] = SettingsContent.menuLabels(SLIDESHOW_STYLE)[selection(SLIDESHOW_STYLE)];
            v[2] = onOff(localPrefs().getBoolean("aerial_show_clock", false));
            v[3] = onOff(localPrefs().getBoolean("aerial_show_location", false));
            v[4] = onOff(localPrefs().getBoolean("screensaver_show_weather", false));
        } else if (screen == TOP_PANEL) {
            v[2] = onOff(localPrefs().getBoolean("show_date", false));
        } else if (screen == WIDGETS) {
            // Date is a submenu; its switch is shown inside DATE_TIME.
        } else if (screen == DATE_TIME) {
            v[0] = onOff(localPrefs().getBoolean("show_date", false));
        } else if (screen == WEATHER) {
            boolean top = weatherVisible.test(true), bottom = weatherVisible.test(false);
            v[0] = AppStrings.get(top && bottom ? R.string.settings_weather_both : bottom ? R.string.settings_weather_bottom : top ? R.string.settings_weather_home : R.string.settings_weather_none);
            v[1] = Prefs.city(); v[2] = settings()[1];
        } else if (screen == ABOUT) {
            String[] fallback = new String[rowCount]; java.util.Arrays.fill(fallback, "—");
            v = device.get("about:" + AppLanguage.current().code + ":" + rowCount, fallback, () -> readDevice(rowCount)).clone();
            v[0] = localPrefs().getString("device_name", "ТВ");
        }

        return v;
    }

    /** One background snapshot per visit, instead of repeated Binder/network reads during focus animation. */
    @SuppressWarnings("deprecation") // Keep the Android 9-compatible display and Wi-Fi APIs.
    private String[] readDevice(int rowCount) {
        String[] v = new String[rowCount];

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
        if (rowCount > 8) v[8] = "—";
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
                    int strength = wi == null ? -1 : io.tvos.network.WifiSignal.strengthForRssi(wi.getRssi());
                    if (rowCount > 8 && strength >= 0) v[8] = strength + "/5";
                    if (ssid != null && !ssid.isEmpty() && !ssid.contains("unknown ssid"))
                        v[5] = ssid.replace("\"", "");
                } catch (Exception ignored) {}
            }
        }
        // Show device name first, preserving all other values.
        String deviceName = v[7];
        System.arraycopy(v, 0, v, 1, 7);
        v[0] = deviceName;
        return v;
    }


    static String[] settingLabels() {
        List<String> l = new ArrayList<>();
        l.add(AppStrings.get(R.string.settings_language));
        l.add(AppStrings.get(R.string.settings_temperature));
        l.add(AppStrings.get(R.string.settings_theme));
        l.add(AppStrings.get(R.string.settings_weather_widget));
        l.add(AppStrings.get(R.string.settings_city));
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
        return v;
    }

    static String onOff(boolean on) {
        return on ? AppStrings.get(R.string.settings_on) : AppStrings.get(R.string.settings_off);
    }

    static String[] keyLabels() {
        return new String[] {AppStrings.get(R.string.settings_netflix_button), AppStrings.get(R.string.settings_youtube_button),
                AppStrings.get(R.string.settings_prime_video_button), AppStrings.get(R.string.settings_tcl_channel_button), AppStrings.get(R.string.settings_mi_button), AppStrings.get(R.string.settings_remote_input), AppStrings.get(R.string.settings_remote_shortcuts)};
    }

    static String[] keyValues(List<String> appPkgs, List<String> appLabels) {
        String[] v = new String[Prefs.KEYS.length + 2];
        for (int i = 0; i < Prefs.KEYS.length; i++) {
            String pkg = Prefs.keyApp(Prefs.KEYS[i]);
            int at = appPkgs.indexOf(pkg);
            v[i] = pkg != null && !pkg.isEmpty() && at >= 0 ? appLabels.get(at)
                    : AppStrings.get(R.string.settings_not_configured);
        }
        v[Prefs.KEYS.length] = "";
        v[Prefs.KEYS.length + 1] = AppStrings.get(R.string.settings_remote_taps);
        return v;
    }

    String updateDetailsText() {
        if (updateInstalledVersion == null) updateInstalledVersion = SettingsUpdateDialog.versionName(activity);
        long checkedAt = localPrefs().getLong("self_update_attempt", 0);
        if (updateCheckShown == null || checkedAt != updateCheckAt || updateCheckLanguage != AppLanguage.current()) {
            updateCheckAt = checkedAt;
            updateCheckLanguage = AppLanguage.current();
            updateCheckShown = formatLastUpdateCheck(checkedAt, AppLanguage.current());
        }
        return updateInstalledVersion + "\n" + updateCheckShown;
    }

    static String formatLastUpdateCheck(long checkedAt, AppLanguage language) {
        if (checkedAt <= 0) return AppStrings.get(R.string.settings_last_check_never, language);
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                AppStrings.get(R.string.settings_last_check_date_pattern, language),
                language.locale);
        return String.format(language.locale,
                AppStrings.get(R.string.settings_last_check, language),
                format.format(new java.util.Date(checkedAt)));
    }
}
