package io.tvos.weather;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.function.LongSupplier;

/** Open-Meteo decoding; no storage, transport, UI or refresh policy. */
public final class WeatherParser {
    private final LongSupplier clock;

    public WeatherParser(LongSupplier clock) { this.clock = clock; }

    public WeatherForecast parse(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        WeatherForecast w = new WeatherForecast();
        w.timezone = zone.getID();
        SimpleDateFormat dates = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        dates.setTimeZone(zone);
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        try { w.humidity = current.getDouble("relative_humidity_2m"); } catch (Exception unavailable) { /* Old cache. */ }
        try { w.wind = current.getDouble("wind_speed_10m"); } catch (Exception unavailable) { /* Old cache. */ }
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), dates);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), dates);
        JSONArray dayTimes = daily.optJSONArray("time"), dayCodes = daily.optJSONArray("weather_code");
        JSONArray dayMin = daily.getJSONArray("temperature_2m_min"), dayMax = daily.getJSONArray("temperature_2m_max");
        JSONArray rain = daily.optJSONArray("precipitation_probability_max");
        if (dayTimes != null && dayCodes != null) {
            int count = Math.min(5, Math.min(dayTimes.length(), Math.min(dayCodes.length(), Math.min(dayMin.length(), dayMax.length()))));
            for (int i = 0; i < count; i++) {
                WeatherForecast.Day day = new WeatherForecast.Day();
                day.time = localTime(dayTimes.getString(i) + "T00:00", dates);
                day.min = dayMin.getDouble(i); day.max = dayMax.getDouble(i); day.code = dayCodes.getInt(i);
                day.rain = rain == null ? -1 : rain.optInt(i, -1);
                w.days.add(day);
            }
        }
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            long now = clock.getAsLong();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), dates);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        return w;
    }

    private static long localTime(String iso, SimpleDateFormat dates) throws ParseException {
        return dates.parse(iso).getTime();
    }
}
