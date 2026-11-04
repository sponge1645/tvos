package io.tvos;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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

/** Compact reference-style forecast card with six hours and five daily ranges. */
@SuppressWarnings("deprecation")
final class HomeWeatherPanel extends Dialog implements HomeData.Listener {
    private final HomeData data;
    private final ForecastView forecast;
    private boolean closing;
    private java.util.function.DoubleConsumer triggerOpacity = value -> { };
    void setTriggerOpacityListener(java.util.function.DoubleConsumer listener) { triggerOpacity = listener; }
    private final Runnable minute = new Runnable() {
        @Override public void run() {
            if (!isShowing() || closing) return;
            data.refreshIfStale(); onData(); forecast.postDelayed(this, 60_000);
        }
    };

    HomeWeatherPanel(Context context, HomeData data) {
        super(context); this.data = data;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        forecast = new ForecastView(context);
        forecast.prepareLabels(data.weather);
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); setContentView(forecast);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0f);
            window.setWindowAnimations(0);
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        forecast.setFocusable(true); forecast.setFocusableInTouchMode(true); forecast.requestFocus();
        forecast.setAlpha(0);
        forecast.post(() -> {
            if (closing || !isShowing()) return;
            float k = Math.min(forecast.getWidth()/1920f, forecast.getHeight()/1080f);
            forecast.setPivotX(390*k); forecast.setPivotY(390*k);
            forecast.setScaleX(reduced() ? 1 : .96f); forecast.setScaleY(reduced() ? 1 : .96f);
            forecast.setTranslationY(reduced() ? 0 : 18*k);
            triggerOpacity.accept(reduced() ? 0 : 1);
            forecast.animate().setUpdateListener(animation -> triggerOpacity.accept(1-forecast.getAlpha()))
                    .alpha(1).scaleX(1).scaleY(1).translationY(0)
                    .setDuration(reduced() ? 0 : 280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
        });
    }
    private boolean reduced() { return getContext().getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false); }
    @Override protected void onStart() { super.onStart(); data.addListener(this); data.refreshIfStale(); onData(); forecast.postDelayed(minute, 60_000); }
    @Override protected void onStop() { data.removeListener(this); forecast.removeCallbacks(minute); forecast.animate().cancel(); triggerOpacity.accept(1); super.onStop(); }
    @Override public void onData() {
        WeatherForecast w = data.weather;
        forecast.setContentDescription(Prefs.city() + ", " + HomeData.degrees(w.temp) + ", "
                + HomeData.condition(w.code, io.tvos.weather.WeatherCardStyle.day(w, System.currentTimeMillis())) + ". " + AppStrings.get(R.string.weather_open_forecast));
        forecast.invalidate();
    }
    private void close() {
        if (closing) return; closing = true;
        forecast.animate().cancel();
        if (reduced()) dismiss();
        else {
            float k = Math.min(forecast.getWidth()/1920f, forecast.getHeight()/1080f);
            forecast.animate().alpha(0).scaleX(.97f).scaleY(.97f).translationY(12*k)
                    .setDuration(220).setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                    .withEndAction(this::dismiss).start();
        }
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
        private WeatherForecast solarForecast;
        private long solarMinute = -1;
        private boolean weatherDay;
        private String timeFormatKey = "";
        private SimpleDateFormat hoursFormat, weekdaysFormat;
        private TimeZone forecastZone;
        private final WeatherCardBackground background = new WeatherCardBackground();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF panel = new RectF(), bar = new RectF();
        ForecastView(Context context) { super(context); }
        void prepareLabels(WeatherForecast w) {
            String key = w.timezone + Ui.locale().toLanguageTag() + android.text.format.DateFormat.is24HourFormat(getContext());
            if (!key.equals(timeFormatKey)) {
                timeFormatKey = key;
                hoursFormat = new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(getContext()) ? "HH" : "h a", Ui.locale());
                weekdaysFormat = new SimpleDateFormat("EEE", Ui.locale());
                forecastZone = TimeZone.getTimeZone(w.timezone);
                hoursFormat.setTimeZone(forecastZone); weekdaysFormat.setTimeZone(forecastZone);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP && !panel.contains(event.getX(), event.getY())) close();
            return true;
        }
        private void label(Canvas c, String value, float x, float y, float size, int color, Paint.Align align, float width) {
            text.setColor(color); text.setTextSize(size); text.setTextAlign(align);
            text.setTypeface(size >= 80 ? Ui.displayRegular : Ui.displayMedium);
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
            int ink = 0xFFF9FAFC, muted = 0xFFBFC2C9, lowInk = 0xFF818690;
            panel.set(40*k, 40*k, 740*k, 740*k);
            WeatherForecast w = data.weather;
            long now = System.currentTimeMillis();
            if (solarForecast != w || solarMinute != now/60_000L) {
                solarForecast = w; solarMinute = now/60_000L;
                weatherDay = io.tvos.weather.WeatherCardStyle.day(w, now);
            }
            boolean animate = background.draw(c, panel, w, now, weatherDay, reduced());
            int saved = c.save(); c.scale(k, k); c.translate(0, -10);
            label(c, Prefs.city(), 76, 108, 32, ink, Paint.Align.LEFT, 490);
            label(c, HomeData.degrees(w.temp), 76, 194, 88, ink, Paint.Align.LEFT, 350);
            icon(c, w.code, weatherDay, 684, 104, 38, ink);
            label(c, HomeData.condition(w.code, weatherDay), 704, 160, 28, ink, Paint.Align.RIGHT, 300);
            label(c, "↑" + HomeData.degrees(w.max) + " ↓" + HomeData.degrees(w.min),
                    704, 194, 27, ink, Paint.Align.RIGHT, 300);
            fill.setColor(0x658E939E); c.drawRect(76, 238, 704, 239, fill);
            prepareLabels(w);
            TimeZone zone = forecastZone;
            SimpleDateFormat hours = hoursFormat;
            int next = 0;
            while (next < w.hours.size() && w.hours.get(next) <= now) next++;
            int count = Math.min(6, w.hours.size() - next);
            for (int i = 0; i < count; i++) {
                int at = next + i; float x = 102 + i * 115;
                label(c, hours.format(new Date(w.hours.get(at))), x, 276, 23, muted, Paint.Align.CENTER, 100);
                icon(c, w.hourCode.get(at), w.hourDay.get(at), x, 328, 44, ink);
                label(c, HomeData.degrees(w.hourTemp.get(at)), x, 397, 28, ink, Paint.Align.CENTER, 100);
            }
            if (count == 0) label(c, AppStrings.get(R.string.weather_loading), 390, 330, 25, muted, Paint.Align.CENTER, 620);
            fill.setColor(0x658E939E); c.drawRect(76, 420, 704, 421, fill);
            Calendar today = Calendar.getInstance(zone); today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0); today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0);
            int first = 0; while (first < w.days.size() && w.days.get(first).time < today.getTimeInMillis()) first++;
            int end = Math.min(w.days.size(), first + 5);
            double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (int i = first; i < end; i++) {
                WeatherForecast.Day d = w.days.get(i);
                if (Double.isNaN(d.min) || Double.isNaN(d.max)) continue;
                low = Math.min(low, d.min); high = Math.max(high, d.max);
            }
            double span = Math.max(1, high - low);
            SimpleDateFormat weekdays = weekdaysFormat;
            for (int i = first; i < end; i++) {
                WeatherForecast.Day d = w.days.get(i); float y = 470 + (i - first) * 55;
                label(c, weekdays.format(new Date(d.time)), 76, y+12, 28, ink, Paint.Align.LEFT, 100);
                icon(c, d.code, true, 206, y, 38, ink);
                label(c, HomeData.degrees(d.min), 338, y+12, 28, lowInk, Paint.Align.RIGHT, 90);
                label(c, HomeData.degrees(d.max), 704, y+12, 28, ink, Paint.Align.RIGHT, 72);
                fill.setColor(0x18202731); bar.set(355,y-4,657,y+4); c.drawRoundRect(bar,4,4,fill);
                if (!Double.isNaN(d.min) && !Double.isNaN(d.max)) {
                    float left = 355+(float)((d.min-low)/span)*302;
                    float right = 355+(float)((d.max-low)/span)*302;
                    right = Math.min(657, Math.max(left+8,right)); left = Math.min(left,right-8);
                    fill.setColor(0xFF59CBE7); bar.set(left,y-4,right,y+4); c.drawRoundRect(bar,4,4,fill);
                }
            }
            if (first == w.days.size()) label(c, AppStrings.get(R.string.weather_loading), 390, 540, 26, muted, Paint.Align.CENTER, 620);
            c.restoreToCount(saved);
            if (animate && isShowing() && !closing) postInvalidateDelayed(50);
        }
    }
}
