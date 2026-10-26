package io.tvos;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
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

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    volatile long weatherAt;
    volatile Weather weather = new Weather();

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
                weather = parseWeather(w);
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
                    Weather fresh = parseWeather(body);
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

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        return w;
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
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
                if (place.equals(weatherPlace())) found = findCityName(name, latitude, longitude, id, language);
            } catch (Exception e) { AppLog.w("city localization: " + e.getMessage()); }
            final String[] result = found;
            main.post(() -> {
                synchronized (HomeData.this) { cityNameRequests.remove(key); }
                if (result != null && Prefs.cacheCityName(result[0], language, latitude, longitude,
                        Long.parseLong(result[1])) && language.equals(Prefs.language())) notifyListeners();
            });
        });
    }

    private static String[] findCityName(String name, String latitude, String longitude,
                                          long id, String language) throws Exception {
        double lat = Double.parseDouble(latitude), lon = Double.parseDouble(longitude);
        if (id > 0) {
            JSONObject city = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/get?id=" + id
                    + "&language=" + language));
            if (city.optString("name", "").isEmpty()
                    || Math.abs(city.getDouble("latitude") - lat) > 0.25
                    || Math.abs(city.getDouble("longitude") - lon) > 0.25) return null;
            return new String[] {city.getString("name"), Long.toString(id)};
        }
        // Existing installations have a name and coordinates but no GeoNames ID yet.
        String[] nearest = null;
        double distance = 0.25 * 0.25;
        // Search indexes may not match a Cyrillic query when English results are requested.
        // Discover the stable ID in either supported language, then translate through /get.
        for (String searchLanguage : AppLanguage.searchOrder(language)) {
            for (String[] city : findCity(name, searchLanguage)) {
                double dy = Double.parseDouble(city[2]) - lat, dx = Double.parseDouble(city[3]) - lon;
                double candidate = dy * dy + dx * dx;
                if (candidate < distance) { distance = candidate; nearest = city; }
            }
            if (nearest != null) break;
        }
        if (nearest == null) return null;
        long foundId = Long.parseLong(nearest[4]);
        if (foundId > 0) return findCityName(name, latitude, longitude, foundId, language);
        return language.equals(nearest[5]) ? new String[] {nearest[1], nearest[4]} : null;
    }

    static List<String[]> findCity(String name) throws Exception {
        return findCity(name, Prefs.language());
    }

    private static List<String[]> findCity(String name, String lang) throws Exception {
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + lang
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude")),
                    Long.toString(r.optLong("id", 0)), lang});
        }
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
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
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
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
