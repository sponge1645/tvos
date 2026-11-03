package io.tvos;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import io.tvos.settings.SettingsBackground;
import io.tvos.setup.WelcomePaths;

/** Vector handwriting: cached geometry, no video decoder or per-frame path allocation. */
final class SetupWelcomeView extends View {
    private final SettingsBackground background = new SettingsBackground();
    private final PathMeasure[][] measures = new PathMeasure[2][];
    private final float[] lengths = new float[2];
    private final RectF[] bounds = new RectF[2];
    private final Path segment = new Path();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Shader dark = new LinearGradient(0, 1, 1, 0, 0xFF95B3BE, 0xFF95B3BE, Shader.TileMode.CLAMP);
    private final Matrix shaderMatrix = new Matrix();
    private final Runnable next;
    private final String[] hints;
    private final boolean reduced;
    private long start;
    private boolean running = true, pressed;
    private float scale;
    private float wordHeight, wordStroke, wipeHalfWidth;
    private long exitStart = -1;
    private Runnable afterExit;

    SetupWelcomeView(Context context, Runnable next) {
        super(context); this.next = next;
        reduced = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false);
        hints = new String[]{AppStrings.get(R.string.settings_setup_center_hint, AppLanguage.ENGLISH), AppStrings.get(R.string.settings_setup_center_hint, AppLanguage.RUSSIAN)};
        hint.setTypeface(Ui.displaySemibold); hint.setTextAlign(Paint.Align.CENTER);
        ink.setStyle(Paint.Style.STROKE); ink.setStrokeCap(Paint.Cap.ROUND); ink.setStrokeJoin(Paint.Join.ROUND);
        for (int language = 0; language < 2; language++) {
            Path[] paths = WelcomePaths.create(language == 1);
            measures[language] = new PathMeasure[paths.length];
            RectF all = new RectF(); RectF part = new RectF();
            for (int i = 0; i < paths.length; i++) {
                measures[language][i] = new PathMeasure(paths[i], false);
                lengths[language] += measures[language][i].getLength();
                paths[i].computeBounds(part, true); if (i == 0) all.set(part); else all.union(part);
            }
            all.inset(-30, -30); bounds[language] = all;
        }
        start = SystemClock.uptimeMillis();
        setFocusable(true); setFocusableInTouchMode(true);
        setContentDescription(hints["ru".equals(Prefs.language()) ? 1 : 0]);
    }
    void stop() { running = false; pressed = false; afterExit = null; background.clear(); }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        scale = Math.min(w / 1920f, h / 1080f);
        shaderMatrix.setScale(w, -h); shaderMatrix.postTranslate(0, h); dark.setLocalMatrix(shaderMatrix);
        hint.setTextSize(40 * scale);
        RectF english = bounds[0];
        float originalScale = Math.min(570 * scale / english.width(), 210 * scale / english.height());
        wordStroke = 90 * originalScale;
        wordHeight = (english.height() - 60) * originalScale;
        float widestWord = 0;
        for (int i = 0; i < bounds.length; i++) {
            RectF b = bounds[i];
            widestWord = Math.max(widestWord, (b.width() - 60) * wordHeight * (i == 1 ? 1.2f : 1f) / (b.height() - 60) + wordStroke);
        }
        float fit = Math.min(1, getWidth() * .8f / widestWord);
        wordHeight *= fit; wordStroke *= fit; wipeHalfWidth = widestWord * fit / 2 + 12 * scale;
        float widest = Math.max(hint.measureText(hints[0]), hint.measureText(hints[1]));
        if (widest > w * .85f) hint.setTextSize(hint.getTextSize() * w * .85f / widest);
    }
    private void word(Canvas canvas, int language, float progress, float alpha) {
        if (alpha <= 0 || progress <= 0) return;
        RectF b = bounds[language];
        float s = wordHeight * (language == 1 ? 1.2f : 1f) / (b.height() - 60);
        int save = canvas.save(); canvas.translate(getWidth() / 2f, getHeight() * .47f + (language == 1 ? 30 * scale : 0));
        canvas.scale(s, s); canvas.translate(-b.centerX(), -b.centerY());
        shaderMatrix.setScale(getWidth() / s, -getHeight() / s);
        shaderMatrix.postTranslate(b.centerX() - getWidth() / (2 * s), b.centerY() + (getHeight() * .53f - (language == 1 ? 30 * scale : 0)) / s);
        dark.setLocalMatrix(shaderMatrix);
        ink.setStrokeWidth(wordStroke / s); ink.setAlpha(Math.round(255 * alpha));
        float remaining = lengths[language] * progress;
        for (PathMeasure measure : measures[language]) {
            float length = measure.getLength();
            if (remaining <= 0) break;
            segment.rewind(); measure.getSegment(0, Math.min(length, remaining), segment, true);
            canvas.drawPath(segment, ink); remaining -= length;
        }
        canvas.restoreToCount(save);
    }
    /** Soft horizontal replacement, confined to the small word area; no offscreen bitmap. */
    private void replacement(Canvas canvas, int language, float progress) {
        float left = getWidth() / 2f - wipeHalfWidth;
        float right = getWidth() / 2f + wipeHalfWidth;
        float feather = 35.2f * scale;
        float edge = left - feather + (right - left + 2 * feather) * progress;
        int save = canvas.save();
        canvas.clipRect(edge + feather, 0, getWidth(), getHeight());
        word(canvas, 1 - language, 1, 1); canvas.restoreToCount(save);
        save = canvas.save(); canvas.clipRect(0, 0, edge - feather, getHeight());
        word(canvas, language, progress, 1); canvas.restoreToCount(save);
        for (int band = 0; band < 8; band++) {
            float x = edge - feather + 2 * feather * band / 8;
            float blend = 1 - (band + .5f) / 8;
            save = canvas.save(); canvas.clipRect(x, 0, x + 2 * feather / 8, getHeight());
            word(canvas, 1 - language, 1, 1 - blend);
            word(canvas, language, progress, blend); canvas.restoreToCount(save);
        }
    }
    private long elapsedSinceStart(long now) { return Math.max(0, now - start); }
    void leave(Runnable action) {
        if (!running || exitStart >= 0) return;
        if (reduced) { stop(); action.run(); }
        else { afterExit = action; exitStart = SystemClock.uptimeMillis(); invalidate(); }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean light = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
        background.draw(canvas, getWidth(), getHeight(), light);
        ink.setShader(light ? null : dark); ink.setColor(light ? 0xFF6E7E94 : 0xFFFFFFFF);
        hint.setShader(light ? null : dark); hint.setColor(light ? 0xFF6E7E94 : 0xFFFFFFFF);
        long now = SystemClock.uptimeMillis();
        if (exitStart >= 0 && now - exitStart >= 280) {
            if (running) { Runnable action = afterExit; stop(); if (action != null) post(action); } return;
        }
        int content = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(),
                exitStart < 0 ? Math.round(255 * (reduced ? 1 : Math.min(1, elapsedSinceStart(now) / 260f))) : Math.round(255 * Math.max(0, 1 - (now - exitStart) / 220f)));
        long elapsed = Math.max(0, now - start);
        long cycle = elapsed / 4800; float phase = elapsed % 4800;
        int language = (int) (cycle % 2);
        if (reduced) {
            language = "ru".equals(Prefs.language()) ? 1 : 0; word(canvas, language, 1, 1);
            shaderMatrix.setScale(getWidth(), -getHeight()); shaderMatrix.postTranslate(0, getHeight()); dark.setLocalMatrix(shaderMatrix);
            hint.setAlpha(255); canvas.drawText(hints[language], getWidth() / 2f, getHeight() * .47f + 210 * scale, hint);
        } else {
            float progress = Math.min(1, phase / 3800);
            if (cycle == 0 || progress >= 1) word(canvas, language, progress, 1);
            else replacement(canvas, language, progress);
            // Text changes near completion of the incoming handwriting, independently of its fade.
            float blend = cycle == 0 ? 1 : Math.max(0, Math.min(1, (phase - 3200) / 500));
            shaderMatrix.setScale(getWidth(), -getHeight()); shaderMatrix.postTranslate(0, getHeight()); dark.setLocalMatrix(shaderMatrix);
            if (blend < 1) { hint.setAlpha(Math.round(255 * (1 - blend))); canvas.drawText(hints[1 - language], getWidth() / 2f, getHeight() * .47f + 210 * scale, hint); }
            hint.setAlpha(Math.round(255 * blend)); canvas.drawText(hints[language], getWidth() / 2f, getHeight() * .47f + 210 * scale, hint);
            if (running && hasWindowFocus()) postInvalidateOnAnimation();
        }
        canvas.restoreToCount(content);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); pressed = false;
        if (focused && running) invalidate();
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getRepeatCount() == 0) pressed = true; return true;
        }
        return super.onKeyDown(key, event);
    }
    @Override public boolean onKeyUp(int key, KeyEvent event) {
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            boolean advance = pressed && !event.isCanceled(); pressed = false;
            if (advance && running && exitStart < 0) {
                next.run();
            } return true;
        }
        return super.onKeyUp(key, event);
    }
}
