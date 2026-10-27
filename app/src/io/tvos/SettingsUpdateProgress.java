package io.tvos;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

/** Inline download progress; samples updater events, without changing transport or installation. */
final class SettingsUpdateProgress {
    private boolean active, checking;
    private int percent = -1, initialPercent;
    private long started, labelSecond = -1;
    private AppLanguage labelLanguage;
    private String label;
    private float scale = -1;
    private Paint text;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    boolean busy() { return active || checking; }
    boolean active() { return active; }

    void observe(int kind, int value, long now) {
        checking = kind == SelfUpdate.CHECKING;
        if (kind != SelfUpdate.DOWNLOADING) {
            active = false;
            percent = -1;
            labelSecond = -1;
            return;
        }
        if (!active || value < percent) {
            started = now;
            initialPercent = Math.max(0, value);
        }
        active = true;
        percent = Math.max(-1, Math.min(100, value));
        labelSecond = -1;
    }

    String label(long now) {
        AppLanguage language = AppLanguage.current();
        long second = now / 1000;
        if (labelSecond == second && labelLanguage == language && label != null) return label;
        labelSecond = second;
        labelLanguage = language;
        if (percent < 0) label = AppStrings.get(R.string.settings_downloading_update);
        else if (percent == 100) label = AppStrings.get(R.string.settings_update_verifying);
        else if (percent <= initialPercent || now - started < 2000) {
            label = String.format(language.locale, AppStrings.get(R.string.settings_update_download_percent), percent);
        } else {
            long seconds = Math.min(86400, Math.max(1,
                    (100L - percent) * Math.max(0, now - started) / (percent - initialPercent) / 1000));
            String time;
            if (seconds < 60) time = AppStrings.get(R.string.settings_update_less_than_minute);
            else {
                int minutes = (int) ((seconds + 59) / 60);
                time = String.format(language.locale, AppStrings.quantity(R.plurals.settings_update_minutes, minutes), minutes);
            }
            label = String.format(language.locale, AppStrings.get(R.string.settings_update_download_remaining), time);
        }
        return label;
    }

    void draw(Canvas canvas, float k, boolean light) {
        if (!active) return;
        if (scale != k) {
            scale = k;
            text = Ui.text(Ui.displaySemibold, 30 * k, 0);
            text.setTextAlign(Paint.Align.CENTER);
        }
        text.setColor(light ? 0xB33A5761 : 0xFF888D90);
        canvas.drawText(label(SystemClock.uptimeMillis()), 522 * k, 865 * k, text);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(light ? 0x33000000 : 0x99000000);
        bar.set(342 * k, 895 * k, 702 * k, 905 * k);
        canvas.drawRoundRect(bar, 5 * k, 5 * k, paint);
        if (percent > 0) {
            bar.right = bar.left + 360 * k * percent / 100f;
            paint.setColor(light ? 0xE63A5761 : 0xD9FFFFFF);
            canvas.drawRoundRect(bar, 5 * k, 5 * k, paint);
        }
    }

    void drawSpinner(Canvas canvas, float x, float y, float k, int color, boolean reduced) {
        if (!busy()) return;
        int phase = reduced ? 0 : (int) (SystemClock.uptimeMillis() / 80 % 12);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3.5f * k);
        int saved = canvas.save();
        canvas.translate(x, y);
        for (int i = 0; i < 12; i++) {
            int age = (i - phase + 12) % 12;
            paint.setColor((color & 0x00FFFFFF) | ((255 - age * 18) << 24));
            canvas.drawLine(0, -9 * k, 0, -16 * k, paint);
            canvas.rotate(30);
        }
        canvas.restoreToCount(saved);
    }
}
