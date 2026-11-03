package io.tvos;

import android.content.Context;
import android.graphics.Canvas;
import io.tvos.setup.SetupCountries;
import io.tvos.settings.SettingsRowRenderer;
import java.util.function.Consumer;
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
    private final RectF row = new RectF();
    private final Shader dark = new LinearGradient(0, 1, 1, 0, 0xFF51595B, 0xFF55646B, Shader.TileMode.CLAMP);
    private final Matrix matrix = new Matrix();
    private final AppLanguage[] languages = AppLanguage.values();
    private final Shadows shadows = new Shadows();
    private final SettingsRowRenderer focusRow = new SettingsRowRenderer(shadows::draw);
    private final boolean region;
    private final Consumer<String> choose;
    private String[] codes, names;
    private String detected = "", status = "";
    private boolean moved;
    private final String more;
    private final Runnable next;
    private final String title;
    private final boolean reduced;
    private final long start = SystemClock.uptimeMillis();
    private int selected;
    private boolean pressed, confirmed;
    SetupLanguageView(Context context, Runnable next) {
        this(context, next, null);
    }
    SetupLanguageView(Context context, Runnable next, Consumer<String> choose) {
        super(context); this.next = next; this.choose = choose; region = choose != null;
        title = AppStrings.get(region ? R.string.settings_region : R.string.settings_setup_language_title);
        more = AppStrings.get(R.string.settings_setup_more_regions);
        if (region) {
            codes = SetupCountries.codes(AppLanguage.current().locale);
            names = new String[codes.length];
            for (int i = 0; i < codes.length; i++) names[i] = SetupCountries.name(codes[i], AppLanguage.current().locale);
        } else {
            codes = new String[languages.length]; names = new String[languages.length];
            for (int i = 0; i < languages.length; i++) { codes[i] = languages[i].code; names[i] = languages[i].nativeName; }
        }
        reduced = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false);
        String current = region ? context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("setup_country", "") : Prefs.language();
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(current)) selected = i;
        moved = region && SetupCountries.valid(current);
        text.setTypeface(Ui.displaySemibold); text.setTextAlign(Paint.Align.CENTER);
        setFocusable(true); setFocusableInTouchMode(true); describe();
        setOnClickListener(v -> confirm());
    }
    private void describe() { setContentDescription(title + ", " + names[selected]); }
    private void confirm() {
        if (confirmed) return; confirmed = true;
        if (region) choose.accept(codes[selected]);
        else { Prefs.setLanguage(codes[selected]); next.run(); }
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
        if (region) {
            if (!detected.isEmpty()) drawRow(canvas, 0, 335, light);
            text.setColor(light ? 0xFF8897AC : 0xFFFFFFFF); text.setShader(light ? null : dark);
            boolean suggestion = !detected.isEmpty() || !status.isEmpty();
            if (detected.isEmpty() && !status.isEmpty()) {
                text.setTextSize(28); canvas.drawText(status, 600, 335, text);
            }
            text.setTextSize(40); float width = text.measureText(more);
            if (width > 800) text.setTextSize(40 * 800 / width);
            canvas.drawText(more, 600, suggestion ? 425 : 330, text);
            int offset = detected.isEmpty() ? 0 : 1;
            int first = Math.max(offset, Math.min(Math.max(offset, codes.length - 6), selected - 3));
            for (int i = first; i < Math.min(codes.length, first + 6); i++) drawRow(canvas, i,
                    (suggestion ? 500 : 405) + (i - first) * 80, light);
        } else for (int i = 0; i < codes.length; i++) drawRow(canvas, i, 420 + i * 80, light);
        // Keep the screen-space tile gradient unchanged through the remote's own transform.
        matrix.setScale(1920 * 660 / 650f, -1080 * 660 / 650f);
        matrix.postTranslate(-1370 * 660 / 650f, (1080 - 215) * 660 / 650f); dark.setLocalMatrix(matrix);
        remote.draw(canvas, 1370, 215, 650, ink);
        canvas.restoreToCount(saved);
        if (alpha < 1 && hasWindowFocus()) postInvalidateOnAnimation();
    }
    private void drawRow(Canvas canvas, int index, float cy, boolean light) {
        if (index == selected) {
            row.set(206.66667f, cy - 30, 924, cy + 30);
            focusRow.draw(canvas, row, 1, light, 1);
            text.setShader(null); text.setColor(0xFF1C1C1E);
        } else { text.setColor(light ? 0xFF8897AC : 0xFFFFFFFF); text.setShader(light ? null : dark); }
        text.setTextSize(40); float width = text.measureText(names[index]);
        if (width > 600) text.setTextSize(40 * 600 / width);
        canvas.drawText(names[index], 600, cy + 14, text);
    }
    void status(String value) { status = value; invalidate(); }
    void detectedCountry(String code) {
        if (!region || !SetupCountries.valid(code) || confirmed) return;
        String old = codes[selected];
        int at = -1; for (int i = 0; i < codes.length; i++) if (codes[i].equals(code)) { at = i; break; }
        if (at < 0) return;
        String country = names[at];
        System.arraycopy(codes, 0, codes, 1, at); System.arraycopy(names, 0, names, 1, at);
        codes[0] = code; names[0] = country; detected = code;
        selected = 0; if (moved) for (int i = 0; i < codes.length; i++) if (codes[i].equals(old)) selected = i;
        describe(); invalidate();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); pressed = false; if (focused) invalidate();
    }
    @Override protected void onDetachedFromWindow() { background.clear(); shadows.clear(); super.onDetachedFromWindow(); }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            selected = Math.max(0, Math.min(codes.length - 1, selected + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
            moved = true; describe(); invalidate(); return true;
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
