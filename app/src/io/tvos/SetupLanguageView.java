package io.tvos;

import android.content.Context;
import android.graphics.Canvas;
import io.tvos.setup.SetupCountries;
import io.tvos.settings.SettingsRowRenderer;
import io.tvos.settings.SettingsListViewport;
import java.util.function.Consumer;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import io.tvos.settings.SettingsBackground;
import io.tvos.setup.SetupRemoteRenderer;
import io.tvos.setup.SetupLayout;

/** Focus-only language capsule and native remote; no settings change until confirmation. */
final class SetupLanguageView extends View {
    private final SettingsBackground background = new SettingsBackground();
    private final SetupRemoteRenderer remote = new SetupRemoteRenderer();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF row = new RectF();
    private final Rect titleBounds = new Rect();
    private static final float TITLE_Y = 225, MORE_GAP = 16;
    private final float firstRow;
    private final Rect moreBounds = new Rect();
    private final Shader dark = new LinearGradient(0, 1, 1, 0, 0xFF95B3BE, 0xFF95B3BE, Shader.TileMode.CLAMP);
    private final Matrix matrix = new Matrix();
    private final AppLanguage[] languages = AppLanguage.values();
    private final Shadows shadows = new Shadows();
    private final SettingsRowRenderer focusRow = new SettingsRowRenderer(shadows::draw);
    private final boolean region;
    private final Consumer<String> choose;
    private String[] codes, names;
    private String detected = "", status = "";
    private boolean moved;
    private Spring[] focus;
    private final Spring scroll = new Spring(.30f, 1f, 0);
    private final SettingsListViewport viewport = new SettingsListViewport();
    private final SettingsListViewport.Content rows = this::drawRows;
    private boolean light;
    private long frame;
    private float rowStart;
    private boolean moving;
    private final String more;
    private final Runnable next;
    private final String regionTitle;
    private final io.tvos.settings.SettingsTransitions transitions = new io.tvos.settings.SettingsTransitions();
    private final io.tvos.settings.SettingsTextRenderer titleText = new io.tvos.settings.SettingsTextRenderer();
    private final String[] languageTitles;
    private final boolean reduced;
    private final long start = SystemClock.uptimeMillis();
    private int selected;
    private boolean pressed, confirmed;
    private long exitStart = -1;
    private Runnable afterExit;
    SetupLanguageView(Context context, Runnable next) {
        this(context, next, null);
    }
    SetupLanguageView(Context context, Runnable next, Consumer<String> choose) {
        super(context); this.next = next; this.choose = choose; region = choose != null;
        regionTitle = AppStrings.get(region ? R.string.settings_region : R.string.settings_setup_language_title);
        languageTitles = new String[languages.length];
        for (int i = 0; i < languages.length; i++) languageTitles[i] = AppStrings.get(R.string.settings_setup_language_title, languages[i]);
        more = AppStrings.get(R.string.settings_setup_more_regions);
        // Shared heading baseline and first capsule, independent of list length.
        text.setTypeface(Ui.displayBold); text.setTextSize(80);
        int titleBottom = 0;
        for (String heading : languageTitles) {
            text.getTextBounds(heading, 0, heading.length(), titleBounds);
            titleBottom = Math.max(titleBottom, titleBounds.bottom);
        }
        String heading = AppStrings.get(R.string.settings_region);
        text.getTextBounds(heading, 0, heading.length(), titleBounds);
        titleBottom = Math.max(titleBottom, titleBounds.bottom);
        firstRow = TITLE_Y + titleBottom + SetupLayout.GAP + SetupLayout.BUTTON_HEIGHT / 2;
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
        focus = new Spring[codes.length];
        for (int i = 0; i < focus.length; i++) focus[i] = new Spring(.28f, .85f, i == selected ? 1 : 0);
        frame = SystemClock.uptimeMillis();
        text.setTypeface(Ui.displaySemibold); text.setTextAlign(Paint.Align.CENTER);
        setFocusable(true); setFocusableInTouchMode(true); describe();
        setOnClickListener(v -> confirm());
    }
    private String title() { return region ? regionTitle : languageTitles[selected]; }
    private void describe() { setContentDescription(title() + ", " + names[selected]); }
    private void confirm() {
        if (confirmed) return; confirmed = true;
        if (region) choose.accept(codes[selected]);
        else { Prefs.setLanguage(codes[selected]); next.run(); }
    }
    void leave(Runnable action) {
        if (exitStart >= 0) return;
        if (reduced) action.run();
        else { afterExit = action; exitStart = SystemClock.uptimeMillis(); invalidate(); }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float k = Math.min(getWidth() / 1920f, getHeight() / 1080f);
        float ox = (getWidth() - 1920 * k) / 2, oy = (getHeight() - 1080 * k) / 2;
        light = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
        background.draw(canvas, getWidth(), getHeight(), light);
        long time = SystemClock.uptimeMillis();
        if (exitStart >= 0 && time - exitStart >= 280) {
            Runnable action = afterExit; afterExit = null; if (action != null) post(action); return;
        }
        float alpha = reduced ? 1 : Math.min(1, Math.max(0, (SystemClock.uptimeMillis() - start) / 260f));
        if (exitStart >= 0) alpha *= Math.max(0, 1 - (time - exitStart) / 220f);
        int saved = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * alpha));
        canvas.translate(ox, oy); canvas.scale(k, k);
        matrix.setScale(1920, -1080); matrix.postTranslate(0, 1080); dark.setLocalMatrix(matrix);
        ink.setColor(light ? 0xFF6E7E94 : 0xFFFFFFFF); ink.setShader(light ? null : dark);
        text.setColor(light ? 0xFF6E7E94 : 0xFFFFFFFF); text.setShader(light ? null : dark);
        text.setTypeface(Ui.displayBold); text.setTextSize(80);
        boolean suggestion = region && (!detected.isEmpty() || !status.isEmpty());
        float contentTop = firstRow - SetupLayout.BUTTON_HEIGHT / 2;
        boolean titleMoving = transitions.drawTitle(canvas, title(), SystemClock.uptimeMillis(), reduced, 600, TITLE_Y, text, titleText);
        text.setTypeface(Ui.displaySemibold);
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(.05f, Math.max(0, (now - frame) / 1000f)); frame = now; moving = titleMoving;
        for (int i = 0; i < focus.length; i++) {
            focus[i].target = i == selected ? 1 : 0;
            if (reduced) focus[i].snap(focus[i].target); else moving |= focus[i].step(dt);
        }
        if (region) {
            if (!detected.isEmpty()) drawRow(canvas, 0, contentTop + SetupLayout.BUTTON_HEIGHT / 2, light);
            text.setColor(light ? 0xFF6E7E94 : 0xFFFFFFFF); text.setShader(light ? null : dark);
            if (detected.isEmpty() && !status.isEmpty()) {
                text.setTextSize(28); canvas.drawText(status, 600, contentTop + 45, text);
            }
            text.setTypeface(Ui.displayMedium); text.setTextSize(40); float width = text.measureText(more);
            if (width > 800) text.setTextSize(40 * 800 / width);
            text.setShader(null); text.setColor(light ? 0xFF93A9C2 : 0xFF576B74);
            text.getTextBounds(more, 0, more.length(), moreBounds);
            float baseline = contentTop + (suggestion ? SetupLayout.BUTTON_HEIGHT + SetupLayout.GAP : 0) - moreBounds.top;
            canvas.drawText(more, 600, baseline, text);
            text.getTextBounds(more, 0, more.length(), moreBounds);
            // Leave 16px below the label; finish the top fade above the capsule.
            rowStart = baseline + moreBounds.bottom + MORE_GAP + SetupLayout.BUTTON_HEIGHT / 2;
            text.setTypeface(Ui.displaySemibold);
        } else rowStart = firstRow;
        int offset = region && !detected.isEmpty() ? 1 : 0;
        scroll.target = region ? Math.max(offset, Math.min(Math.max(offset, codes.length - 6), selected - 3)) : 0;
        if (reduced) scroll.snap(scroll.target); else moving |= scroll.step(dt);
        viewport.resize(190, rowStart - 74, 1020, Math.round((getHeight() - oy) / k), 1);
        // The viewport fades the shadow as well as the rows. An extra clip here
        // cuts through the blurred shadow and produces a visible horizontal seam.
        viewport.draw(canvas, rows);
        // Keep the screen-space tile gradient unchanged through the remote's own transform.
        matrix.setScale(1920 * 660 / 650f, -1080 * 660 / 650f);
        matrix.postTranslate(-1370 * 660 / 650f, (1080 - 215) * 660 / 650f); dark.setLocalMatrix(matrix);
        remote.draw(canvas, 1370, 215, 650, ink);
        canvas.restoreToCount(saved);
        if ((exitStart >= 0 || alpha < 1 || moving) && hasWindowFocus()) postInvalidateOnAnimation();
    }
    private void drawRows(Canvas canvas) {
        int offset = region && !detected.isEmpty() ? 1 : 0;
        int first = region ? Math.max(offset, (int) Math.floor(scroll.value) - 1) : 0;
        int end = region ? Math.min(codes.length, first + 9) : codes.length;
        for (int i = first; i < end; i++) drawRow(canvas, i, rowStart + (i - scroll.value) * 80, light);
    }
    private void drawRow(Canvas canvas, int index, float cy, boolean light) {
        float amount = Math.max(0, Math.min(1, focus[index].value));
        if (amount > .001f) {
            row.set(208.44444f, cy - 30, 924, cy + 30);
            focusRow.drawFocused(canvas, row, 1, light, amount);
            text.setShader(null); text.setColor(io.tvos.settings.SettingsListRenderer.blend(light ? 0xFF6E7E94 : 0xFF95B3BE, 0xFF1C1C1E, amount));
        } else { text.setColor(light ? 0xFF6E7E94 : 0xFFFFFFFF); text.setShader(light ? null : dark); }
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
        for (int i = 0; i < focus.length; i++) focus[i].snap(i == selected ? 1 : 0);
        describe(); invalidate();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); pressed = false; if (focused) invalidate();
    }
    @Override protected void onDetachedFromWindow() { background.clear(); shadows.clear(); titleText.clear(); super.onDetachedFromWindow(); }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = Math.max(0, Math.min(codes.length - 1, selected + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
            if (next != selected) { focus[selected].snap(0); selected = next; }
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
