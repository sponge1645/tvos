package io.tvos.weather;

import java.util.Calendar;
import java.util.TimeZone;

/** Forecast appearance follows the forecast city's solar clock, independently of UI theme. */
public final class WeatherCardStyle {
    public static final int CLEAR = 0, CLOUD = 1, FOG = 2, RAIN = 3, SNOW = 4, STORM = 5, UNKNOWN = 6;
    private WeatherCardStyle() { }
    public static int kind(int code) {
        if (code == 0 || code == 1) return CLEAR;
        if (code == 2 || code == 3) return CLOUD;
        if (code == 45 || code == 48) return FOG;
        if (code >= 95 && code <= 99) return STORM;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return SNOW;
        if (code >= 51 && code <= 67 || code >= 80 && code <= 82) return RAIN;
        return UNKNOWN;
    }
    public static boolean day(WeatherForecast w, long now) {
        TimeZone zone = TimeZone.getTimeZone(w.timezone);
        Calendar current = Calendar.getInstance(zone); current.setTimeInMillis(now);
        Calendar rise = Calendar.getInstance(zone); rise.setTimeInMillis(w.sunrise);
        if (w.sunrise > 0 && w.sunset > w.sunrise
                && rise.get(Calendar.YEAR) == current.get(Calendar.YEAR)
                && rise.get(Calendar.DAY_OF_YEAR) == current.get(Calendar.DAY_OF_YEAR))
            return now >= w.sunrise && now < w.sunset;
        int hour = current.get(Calendar.HOUR_OF_DAY);
        return hour >= 6 && hour < 18;
    }
    public static boolean twilight(WeatherForecast w, long now) {
        return w.sunrise > 0 && w.sunset > w.sunrise
                && Math.min(Math.abs(now-w.sunrise), Math.abs(now-w.sunset)) < 45*60_000L;
    }
    public static int top(int kind, boolean day, boolean twilight) {
        if (kind == UNKNOWN) return 0xFF3C414C;
        if (kind == STORM) return day ? 0xFF344652 : 0xFF14202F;
        if (kind == RAIN) return day ? 0xFF3A5969 : 0xFF172E40;
        if (kind == SNOW) return day ? 0xFF698798 : 0xFF334355;
        if (kind == FOG) return day ? 0xFF7A8D9A : 0xFF3B4958;
        if (kind == CLOUD) return day ? 0xFF5D7684 : 0xFF293544;
        if (twilight) return 0xFF795E85;
        return day ? 0xFF168BBE : 0xFF0B1335;
    }
    public static int bottom(int kind, boolean day) {
        if (kind == UNKNOWN) return 0xFF2C313B;
        if (kind == CLEAR) return day ? 0xFF14547A : 0xFF13162F;
        return day ? 0xFF293F51 : 0xFF15202F;
    }
}
