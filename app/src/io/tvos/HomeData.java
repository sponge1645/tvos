package io.tvos;

import io.tvos.weather.WeatherForecast;
import io.tvos.weather.WeatherParser;
import io.tvos.weather.CityGeocoder;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather/sunrise/sunset (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * No API key is needed. Location comes from the region settings.
 */
final class HomeData {
    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static final long WEATHER_EVERY = 30 * 60_000L;
    interface Listener { void onData(); }

    /** Settings changed (city or weather preferences): fetch again now. */
    synchronized void settingsChanged() {
        settingsEpoch++;
        weatherAt = 0;
        weatherFailedAt = 0;
    }

    private static final WeatherParser WEATHER_PARSER = new WeatherParser(System::currentTimeMillis);
    private static final CityGeocoder CITIES = new CityGeocoder(HomeData::get);
    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    volatile long weatherAt;
    volatile WeatherForecast weather = new WeatherForecast();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final java.util.Set<String> cityNameRequests = new java.util.HashSet<>();
    private volatile boolean fetchingWeather;
    private volatile int settingsEpoch;
    private volatile long weatherFailedAt;

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            String w = cache.getString("weather", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = WEATHER_PARSER.parse(w);
                weatherAt = cache.getLong("weatherAt", 0);
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    synchronized void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingWeather && (now - weatherAt > WEATHER_EVERY || now < weatherAt) && !failedRecently(weatherFailedAt, now)) {
            fetchingWeather = true;
            final int token = settingsEpoch;
            final String latitude = Prefs.latitude(), longitude = Prefs.longitude();
            final String place = latitude + "," + longitude;
            final String url = "https://api.open-meteo.com/v1/forecast?latitude=" + latitude
                    + "&longitude=" + longitude + WEATHER_QUERY;
            io.execute(() -> {
                try {
                    if (token != settingsEpoch || !place.equals(weatherPlace())) return;
                    String body = get(url);
                    WeatherForecast fresh = WEATHER_PARSER.parse(body);
                    synchronized (this) {
                        // A late response must not label the old city's forecast as the new city's cache.
                        if (token != settingsEpoch || !place.equals(weatherPlace())) return;
                        weather = fresh;
                        weatherAt = System.currentTimeMillis();
                        cache.edit().putString("weather", body).putLong("weatherAt", weatherAt).putString("weatherPlace", place).apply();
                    }
                    notifyListeners();
                } catch (Exception e) {
                    synchronized (this) {
                        if (token == settingsEpoch && place.equals(weatherPlace())) weatherFailedAt = System.currentTimeMillis();
                    }
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private static boolean failedRecently(long at, long now) {
        return at != 0 && now >= at && now - at < 2 * 60_000L;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    /** Localized labels are cached independently of the forecast and of the current UI language. */
    synchronized void localizeCity(String language) {
        if (Prefs.hasCityName(language) || Prefs.cityRaw().isEmpty()) return;
        final String latitude = Prefs.latitude(), longitude = Prefs.longitude(), name = Prefs.cityRaw();
        final long id = Prefs.cityId();
        final String place = latitude + "," + longitude;
        final String key = place + "|" + id + "|" + language;
        if (!cityNameRequests.add(key)) return;
        io.execute(() -> {
            String[] found = null;
            try {
                if (place.equals(weatherPlace())) found = CITIES.localize(name, latitude, longitude, id, language,
                        id > 0 ? null : AppLanguage.searchOrder(language));
            } catch (Exception e) { AppLog.w("city localization: " + e.getMessage()); }
            final String[] result = found;
            main.post(() -> {
                synchronized (HomeData.this) { cityNameRequests.remove(key); }
                if (result != null && Prefs.cacheCityName(result[0], language, latitude, longitude,
                        Long.parseLong(result[1])) && language.equals(Prefs.language())) notifyListeners();
            });
        });
    }

    static List<String[]> findCity(String name) throws Exception {
        return CITIES.search(name, Prefs.language());
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "tvOS");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";

            if (code == 0) return AppStrings.get(R.string.weather_clear);
            if (code == 1) return day ? AppStrings.get(R.string.weather_mostly_sunny) : AppStrings.get(R.string.weather_mostly_clear);
            if (code == 2) return AppStrings.get(R.string.weather_partly_cloudy);
            if (code == 3) return AppStrings.get(R.string.weather_cloudy);
            if (code == 45 || code == 48) return AppStrings.get(R.string.weather_fog);
            if (code >= 51 && code <= 57) return AppStrings.get(R.string.weather_drizzle);
            if (code == 65 || code == 67 || code == 82) return AppStrings.get(R.string.weather_heavy_rain);
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return AppStrings.get(R.string.weather_snow);
            if (code >= 95) return AppStrings.get(R.string.weather_thunderstorms);
            return AppStrings.get(R.string.weather_rain);
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    static String hour(long at) {
        return Ui.once("hour", at, () -> {
            AppLanguage language = AppLanguage.current();
            return new SimpleDateFormat(language.hourPattern, language.locale).format(new java.util.Date(at));
        });
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        return minutes < 60 ? String.format(Ui.locale(), AppStrings.get(R.string.runtime_minutes_ago), minutes) : String.format(Ui.locale(), AppStrings.get(R.string.runtime_hours_ago), minutes / 60);
    }
}
