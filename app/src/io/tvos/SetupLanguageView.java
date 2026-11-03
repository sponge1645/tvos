package io.tvos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import io.tvos.settings.SettingsBackground;
import io.tvos.setup.SetupRemoteRenderer;

/** Focus-only language capsule and native remote; no settings change until confirmation. */
final class SetupLanguageView extends View {
    private final SettingsBackground background = new SettingsBackground();
    private final SetupRemoteRenderer remote = new SetupRemoteRenderer();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint capsule = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF row = new RectF();
    private final Shader dark = new LinearGradient(0, 1, 1, 0, 0xFF51595B, 0xFF55646B, Shader.TileMode.CLAMP);
    private final Matrix matrix = new Matrix();
    private final AppLanguage[] languages = AppLanguage.values();
    private final Runnable next;
    private final String title;
    private final boolean reduced;
    private final long start = SystemClock.uptimeMillis();
    private int selected;
    private boolean pressed, confirmed;
    SetupLanguageView(Context context, Runnable next) {
        super(context); this.next = next;
        title = AppStrings.get(R.string.settings_setup_language);
        reduced = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false);
        for (int i = 0; i < languages.length; i++) if (languages[i].code.equals(Prefs.language())) selected = i;
        text.setTypeface(Ui.displaySemibold); text.setTextAlign(Paint.Align.CENTER);
        setFocusable(true); setFocusableInTouchMode(true); describe();
        setOnClickListener(v -> confirm());
    }
    private void describe() { setContentDescription(title + ", " + languages[selected].nativeName); }
    private void confirm() {
        if (confirmed) return; confirmed = true;
        Prefs.setLanguage(languages[selected].code); next.run();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float k = Math.min(getWidth() / 1920f, getHeight() / 1080f);
        float ox = (getWidth() - 1920 * k) / 2, oy = (getHeight() - 1080 * k) / 2;
        boolean light = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
        background.draw(canvas, getWidth(), getHeight(), light);
        float alpha = reduced ? 1 : Math.min(1, Math.max(0, (SystemClock.uptimeMillis() - start) / 260f));
        int saved = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * alpha));
        canvas.translate(ox, oy); canvas.scale(k, k);
        matrix.setScale(1920, -1080); matrix.postTranslate(0, 1080); dark.setLocalMatrix(matrix);
        ink.setColor(light ? 0xFF8897AC : 0xFFFFFFFF); ink.setShader(light ? null : dark);
        text.setColor(light ? 0xFF8897AC : 0xFFFFFFFF); text.setShader(light ? null : dark);
        text.setTextSize(64); canvas.drawText(title, 600, 225, text);
        for (int i = 0; i < languages.length; i++) {
            float cy = 420 + i * 100;
            if (i == selected) {
                row.set(260, cy - 39, 940, cy + 39); capsule.setColor(0xFFF5F5F7);
                canvas.drawRoundRect(row, 39, 39, capsule); text.setShader(null); text.setColor(0xFF1C1C1E);
            } else { text.setColor(light ? 0xFF8897AC : 0xFFFFFFFF); text.setShader(light ? null : dark); }
            text.setTextSize(40); canvas.drawText(languages[i].nativeName, 600, cy + 14, text);
        }
        // Keep the screen-space tile gradient unchanged through the remote's own transform.
        matrix.setScale(1920 * 660 / 650f, -1080 * 660 / 650f);
        matrix.postTranslate(-1370 * 660 / 650f, (1080 - 215) * 660 / 650f); dark.setLocalMatrix(matrix);
        remote.draw(canvas, 1370, 215, 650, ink);
        canvas.restoreToCount(saved);
        if (alpha < 1 && hasWindowFocus()) postInvalidateOnAnimation();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); pressed = false; if (focused) invalidate();
    }
    @Override protected void onDetachedFromWindow() { background.clear(); super.onDetachedFromWindow(); }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            selected = Math.max(0, Math.min(languages.length - 1, selected + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
            describe(); invalidate(); return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getRepeatCount() == 0) pressed = true; return true;
        }
        return super.onKeyDown(key, event);
    }
    @Override public boolean onKeyUp(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            boolean choose = pressed && !event.isCanceled(); pressed = false; if (choose) confirm(); return true;
        }
        return super.onKeyUp(key, event);
    }
}
