package io.tvos;

import io.tvos.settings.SettingsOrderEditor;

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
    private String updateInstalledVersion, updateCheckShown;
    private long updateCheckAt = Long.MIN_VALUE;
    private AppLanguage updateCheckLanguage;
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

    private String tvTarget, tvLabel, tvLanguage;
    private String tvProviderLabel(String target) {
        String language = AppLanguage.current().code;
        if (target.equals(tvTarget) && language.equals(tvLanguage)) return tvLabel;
        String pkg = LiveTvTargets.packageName(target);
        String label = target;
        if (!pkg.isEmpty()) try { label = String.valueOf(activity.getPackageManager().getApplicationLabel(activity.getPackageManager().getApplicationInfo(pkg, 0))); }
        catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) { /* Keep explicit target. */ }
        tvTarget = target; tvLanguage = language; tvLabel = label;
        return label;
    }
    private String vpnPackage, vpnLanguage, vpnLabel;
    void clearAppCache() { vpnPackage = vpnLanguage = vpnLabel = tvTarget = tvLabel = tvLanguage = null; }

    private SharedPreferences localPrefs() { return activity.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    private boolean localFlag(String key) { return localPrefs().getBoolean(key, false); }

    // Keep the same display/Wi-Fi reads on Android 9+ TVs during this extraction.
    @SuppressWarnings("deprecation")
    String[] menu(int screen, int rowCount) {
        String[] v = new String[rowCount];
        java.util.Arrays.fill(v, "");
        if (screen == MEDIA) {
            v[0] = MovieService.label(MovieService.index(localPrefs().getString(MovieService.KEY, "")), AppLanguage.current());
            v[1] = onOff(localPrefs().getBoolean("navigation_clicks", true));
            String target = localPrefs().getString("live_tv_app", "");
            v[2] = target.isEmpty() ? AppStrings.get(R.string.settings_tv_unselected) : tvProviderLabel(target);
        } else if (screen == HOME_SCREEN) {
            v[Prefs.homeContinue() ? 1 : 0] = AppStrings.get(R.string.settings_service_selected);
        } else if (screen == MOVIE_SERVICE) {
            v[MovieService.index(localPrefs().getString(MovieService.KEY, ""))] = AppStrings.get(R.string.settings_service_selected);
        } else if (screen == GENERAL) {
            String theme = Prefs.theme();
            v[1] = "light".equals(theme) ? AppStrings.get(R.string.settings_light) : "dark".equals(theme) ? AppStrings.get(R.string.settings_dark) : AppStrings.get(R.string.settings_automatic);
            v[2] = settings()[0];
            v[3] = io.tvos.setup.SetupCountries.name(Prefs.recommendationRegion(), AppLanguage.current().locale);
            v[4] = Prefs.hour24() ? AppStrings.get(R.string.settings_24_h) : AppStrings.get(R.string.settings_12_h);

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
            String src = localPrefs().getString("video_dream_source", "google");
            v[0] = "apple".equals(src) ? "Aerial" : "na".equals(src) ? "National Geographic" : "Google";
            v[1] = onOff(localPrefs().getBoolean("aerial_show_clock", false));
            v[2] = onOff(localPrefs().getBoolean("aerial_show_location", false));
            v[3] = onOff(localPrefs().getBoolean("screensaver_show_weather", false));
        } else if (screen == TOP_PANEL) {
            v[2] = onOff(localPrefs().getBoolean("show_date", false));
            v[4] = AppStrings.get(Prefs.homeContinue() ? R.string.home_continue_watching : R.string.settings_home_recommendations);
        } else if (screen == WIDGETS) {
            // Date is a submenu; its switch is shown inside DATE_TIME.
        } else if (screen == DATE_TIME) {
            v[0] = onOff(localPrefs().getBoolean("show_date", false));
        } else if (screen == WEATHER) {
            boolean top = weatherVisible.test(true), bottom = weatherVisible.test(false);
            v[0] = AppStrings.get(top && bottom ? R.string.settings_weather_both : bottom ? R.string.settings_weather_bottom : R.string.settings_weather_home);
            v[1] = Prefs.city(); v[2] = settings()[1];
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
                AppStrings.get(R.string.settings_prime_video_button), AppStrings.get(R.string.settings_tcl_channel_button), AppStrings.get(R.string.settings_mi_button), AppStrings.get(R.string.settings_volume_hold), AppStrings.get(R.string.settings_remote_shortcuts)};
    }

    static String[] keyValues(List<String> appPkgs, List<String> appLabels) {
        String[] v = new String[Prefs.KEYS.length + 2];
        for (int i = 0; i < Prefs.KEYS.length; i++) {
            String pkg = Prefs.keyApp(Prefs.KEYS[i]);
            int at = appPkgs.indexOf(pkg);
            v[i] = pkg != null && !pkg.isEmpty() && at >= 0 ? appLabels.get(at)
                    : AppStrings.get(R.string.settings_not_configured);
        }
        String state = Prefs.volumeHoldStatus();
        v[Prefs.KEYS.length] = !Prefs.volumeHold() ? AppStrings.get(R.string.settings_off)
                : "READY".equals(state) ? AppStrings.get(R.string.settings_on)
                : "ERROR".equals(state) ? AppStrings.get(R.string.settings_volume_unavailable)
                : "STARTING".equals(state) ? AppStrings.get(R.string.settings_volume_starting)
                : AppStrings.get(R.string.settings_volume_waiting);
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
