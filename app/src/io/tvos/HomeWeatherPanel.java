package io.tvos;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import io.tvos.weather.WeatherForecast;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/** A modal forecast using the home's existing liquid glass and weather symbols. */
@SuppressWarnings("deprecation")
final class HomeWeatherPanel extends Dialog implements HomeData.Listener {
    private final Scene scene;
    private final HomeData data;
    private final ForecastView forecast;
    private boolean closing;
    private final Runnable minute = new Runnable() {
        @Override public void run() {
            if (!isShowing() || closing) return;
            data.refreshIfStale(); onData(); forecast.postDelayed(this, 60_000);
        }
    };

    HomeWeatherPanel(Context context, Scene scene, HomeData data) {
        super(context); this.scene = scene; this.data = data;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        forecast = new ForecastView(context);
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); setContentView(forecast);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        forecast.setFocusable(true); forecast.setFocusableInTouchMode(true); forecast.requestFocus();
        forecast.setAlpha(0); forecast.animate().alpha(1).setDuration(reduced() ? 0 : 180).start();
    }
    private boolean reduced() { return getContext().getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false); }
    @Override protected void onStart() { super.onStart(); data.addListener(this); data.refreshIfStale(); onData(); forecast.postDelayed(minute, 60_000); }
    @Override protected void onStop() { data.removeListener(this); forecast.removeCallbacks(minute); forecast.animate().cancel(); super.onStop(); }
    @Override public void onData() {
        WeatherForecast w = data.weather;
        forecast.setContentDescription(Prefs.city() + ", " + HomeData.degrees(w.temp) + ", "
                + HomeData.condition(w.code, w.isDay) + ". " + AppStrings.get(R.string.weather_open_forecast));
        forecast.invalidate();
    }
    private void close() {
        if (closing) return; closing = true;
        if (reduced()) dismiss();
        else forecast.animate().alpha(0).setDuration(150).withEndAction(this::dismiss).start();
    }
    @Override public void onBackPressed() { close(); }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                || key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
                || key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) return true;
        return super.onKeyDown(key, event);
    }

    private final class ForecastView extends View {
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF panel = new RectF(), bar = new RectF();
        ForecastView(Context context) { super(context); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP && !panel.contains(event.getX(), event.getY())) close();
            return true;
        }
        private void label(Canvas c, String value, float x, float y, float size, int color, Paint.Align align, float width) {
            text.setColor(color); text.setTextSize(size); text.setTextAlign(align);
            text.setTypeface(Ui.displaySemibold);
            float measured = text.measureText(value);
            if (measured > width) text.setTextSize(size * width / measured);
            c.drawText(value, x, y, text);
        }
        private void icon(Canvas c, int code, boolean day, float x, float y, float size, int color) {
            Symbols.draw(getContext(), c, Symbols.weather(code, day), x, y, size, color);
        }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float k = Math.min(getWidth() / 1920f, getHeight() / 1080f);
            boolean night = data.isNight(System.currentTimeMillis());
            int ink = night ? 0xFFF5F5F7 : 0xFF1D1D1F, muted = night ? 0xFFB7B9C1 : 0xFF5E6570;
            fill.setShader(null); fill.setColor(0x33000000); c.drawRect(0, 0, getWidth(), getHeight(), fill);
            panel.set(40*k, 50*k, 740*k, 1000*k);
            scene.setOffset(0, 0);
            scene.liquid(c, panel, 36*k, night ? 0xFF111113 : 0xFFFFFFFF,
                    night ? .38f : .78f, .035f, 30*k, 24*k, .75f, 1);
            int saved = c.save(); c.scale(k, k);
            WeatherForecast w = data.weather;
            label(c, Prefs.city(), 390, 108, 32, ink, Paint.Align.CENTER, 620);
            label(c, HomeData.degrees(w.temp), 390, 208, 100, ink, Paint.Align.CENTER, 600);
            label(c, HomeData.condition(w.code, w.isDay), 390, 250, 29, ink, Paint.Align.CENTER, 620);
            label(c, "↑ " + HomeData.degrees(w.max) + "   ↓ " + HomeData.degrees(w.min), 390, 288, 26, muted, Paint.Align.CENTER, 600);
            label(c, AppStrings.get(R.string.weather_hourly_title), 76, 346, 21, muted, Paint.Align.LEFT, 620);
            String humidity = Double.isNaN(w.humidity) ? "—" : Math.round(w.humidity) + "%";
            String wind = Double.isNaN(w.wind) ? "—" : String.valueOf(Math.round(w.wind));
            label(c, String.format(Ui.locale(), AppStrings.get(R.string.weather_details), humidity, wind), 76, 379, 21, muted, Paint.Align.LEFT, 620);
            TimeZone zone = TimeZone.getTimeZone(w.timezone);
            SimpleDateFormat hours = new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(getContext()) ? "HH" : "h a", Ui.locale());
            hours.setTimeZone(zone);
            long now = System.currentTimeMillis();
            int next = 0;
            while (next < w.hours.size() && w.hours.get(next) <= now) next++;
            int count = Math.min(6, 1 + w.hours.size() - next);
            for (int i = 0; i < count; i++) {
                int at = next + i - 1; float x = 126 + i * 106;
                label(c, i == 0 ? AppStrings.get(R.string.weather_now) : hours.format(new Date(w.hours.get(at))), x, 425, 23, ink, Paint.Align.CENTER, 100);
                icon(c, i == 0 ? w.code : w.hourCode.get(at), i == 0 ? w.isDay : w.hourDay.get(at), x, 467, 44, ink);
                label(c, HomeData.degrees(i == 0 ? w.temp : w.hourTemp.get(at)), x, 520, 28, ink, Paint.Align.CENTER, 100);
            }
            label(c, AppStrings.get(R.string.weather_daily_title), 76, 584, 21, muted, Paint.Align.LEFT, 620);
            Calendar today = Calendar.getInstance(zone); today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0); today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0);
            int first = 0; while (first < w.days.size() && w.days.get(first).time < today.getTimeInMillis()) first++;
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (int i = first; i < w.days.size(); i++) { low = Math.min(low, w.days.get(i).min); high = Math.max(high, w.days.get(i).max); }
            if (!Double.isNaN(w.temp)) { low = Math.min(low, w.temp); high = Math.max(high, w.temp); }
            double span = Math.max(1, high - low);
            SimpleDateFormat weekdays = new SimpleDateFormat("EEE", Ui.locale()); weekdays.setTimeZone(zone);
            for (int i = first; i < Math.min(w.days.size(), first + 5); i++) {
                WeatherForecast.Day d = w.days.get(i); float y = 628 + (i - first) * 61;
                fill.setShader(null); fill.setColor(night ? 0x22FFFFFF : 0x181D1D1F); c.drawRect(76, y-25, 704, y-24, fill);
                label(c, d.time == today.getTimeInMillis() ? AppStrings.get(R.string.weather_today) : weekdays.format(new Date(d.time)), 76, y+12, 27, ink, Paint.Align.LEFT, 146);
                icon(c, d.code, true, 258, y, 34, ink);
                if (d.rain >= 20) label(c, d.rain+"%", 258, y+27, 17, 0xFF268CD4, Paint.Align.CENTER, 70);
                label(c, HomeData.degrees(d.min), 365, y+12, 27, muted, Paint.Align.RIGHT, 90);
                label(c, HomeData.degrees(d.max), 704, y+12, 27, ink, Paint.Align.RIGHT, 90);
                fill.setColor(night ? 0x25FFFFFF : 0x201D1D1F); bar.set(385,y-4,620,y+4); c.drawRoundRect(bar,4,4,fill);
                float a = 385+(float)((d.min-low)/span)*235, b = 385+(float)((d.max-low)/span)*235;
                fill.setColor(Color.WHITE); fill.setShader(new LinearGradient(a,0,Math.max(a+1,b),0,tempColor(d.min),tempColor(d.max),Shader.TileMode.CLAMP));
                bar.set(a,y-4,Math.max(a+8,b),y+4); c.drawRoundRect(bar,4,4,fill); fill.setShader(null);
                if (d.time == today.getTimeInMillis() && !Double.isNaN(w.temp)) { fill.setColor(ink); c.drawCircle(385+(float)((w.temp-low)/span)*235,y,5,fill); }
            }
            if (first == w.days.size()) label(c, AppStrings.get(R.string.weather_loading), 390, 670, 26, muted, Paint.Align.CENTER, 620);
            if (data.weatherAt > 0) {
                String time = android.text.format.DateFormat.getTimeFormat(getContext()).format(new Date(data.weatherAt));
                label(c, "Open-Meteo · " + String.format(Ui.locale(), AppStrings.get(R.string.weather_updated), time), 390, 967, 21, muted, Paint.Align.CENTER, 620);
            }
            c.restoreToCount(saved);
        }
    }
    private static int tempColor(double celsius) {
        if (celsius < 0) return 0xFF619EFF;
        if (celsius < 12) return ActionSurface.blend(0xFF619EFF, 0xFF52D5C1, (float)(celsius/12));
        if (celsius < 24) return ActionSurface.blend(0xFF52D5C1, 0xFFF4CF49, (float)((celsius-12)/12));
        return ActionSurface.blend(0xFFF4CF49, 0xFFFF6349, (float)Math.min(1,(celsius-24)/12));
    }
}
