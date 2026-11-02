package io.tvos.weather;

import java.util.ArrayList;
import java.util.List;

/** Mutable forecast snapshot; temperatures are Celsius and all times are epoch milliseconds. */
public final class WeatherForecast {
    public double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
    public int code = -1;
    public boolean isDay = true;
    public long sunrise, sunset;   // today's, in epoch ms
    // Next hours (starting at the next full hour): time, temperature, code and day/night.
    public final List<Long> hours = new ArrayList<>();
    public final List<Double> hourTemp = new ArrayList<>();
    public final List<Integer> hourCode = new ArrayList<>();
    public final List<Boolean> hourDay = new ArrayList<>();
}
