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
    private final Shader dark = new LinearGradient(0, 1, 1, 0, 0xFF51595B, 0xFF55646B, Shader.TileMode.CLAMP);
    private final Matrix shaderMatrix = new Matrix();
    private final Runnable next;
    private final String[] hints;
    private final boolean reduced;
    private long start;
    private boolean running = true, pressed;
    private float scale;

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
    void stop() { running = false; pressed = false; background.clear(); }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        scale = Math.min(w / 1920f, h / 1080f);
        shaderMatrix.setScale(w, -h); shaderMatrix.postTranslate(0, h); dark.setLocalMatrix(shaderMatrix);
        hint.setTextSize(30 * scale);
        float widest = Math.max(hint.measureText(hints[0]), hint.measureText(hints[1]));
        if (widest > w * .85f) hint.setTextSize(hint.getTextSize() * w * .85f / widest);
    }
    private void word(Canvas canvas, int language, float progress, float alpha) {
        if (alpha <= 0 || progress <= 0) return;
        RectF b = bounds[language];
        float s = Math.min(570 * scale / b.width(), 210 * scale / b.height());
        int save = canvas.save(); canvas.translate(getWidth() / 2f, getHeight() * .47f);
        canvas.scale(s, s); canvas.translate(-b.centerX(), -b.centerY());
        // Keep the tile gradient in screen coordinates after transforming the canvas.
        shaderMatrix.setScale(getWidth() / s, -getHeight() / s);
        shaderMatrix.postTranslate(b.centerX() - getWidth() / (2 * s), b.centerY() + getHeight() * .53f / s);
        dark.setLocalMatrix(shaderMatrix);
        ink.setStrokeWidth(60); ink.setAlpha(Math.round(255 * alpha));
        float remaining = lengths[language] * progress;
        for (PathMeasure measure : measures[language]) {
            float length = measure.getLength();
            if (remaining <= 0) break;
            segment.rewind(); measure.getSegment(0, Math.min(length, remaining), segment, true);
            canvas.drawPath(segment, ink); remaining -= length;
        }
        canvas.restoreToCount(save);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        boolean light = !HomeData.get(getContext()).isNight(System.currentTimeMillis());
        background.draw(canvas, getWidth(), getHeight(), light);
        ink.setShader(light ? null : dark); ink.setColor(light ? 0xFF8897AC : 0xFFFFFFFF);
        hint.setShader(light ? null : dark); hint.setColor(light ? 0xFF8897AC : 0xFFFFFFFF);
        long elapsed = Math.max(0, SystemClock.uptimeMillis() - start);
        long cycle = elapsed / 6000; float phase = elapsed % 6000;
        int language = (int) (cycle % 2);
        if (reduced) {
            language = "ru".equals(Prefs.language()) ? 1 : 0; word(canvas, language, 1, 1);
            shaderMatrix.setScale(getWidth(), -getHeight()); shaderMatrix.postTranslate(0, getHeight()); dark.setLocalMatrix(shaderMatrix);
            hint.setAlpha(255); canvas.drawText(hints[language], getWidth() / 2f, getHeight() * .65f, hint);
        } else {
            float fade = Math.max(0, (phase - 5200) / 800);
            float progress = Math.min(1, (phase + (cycle > 0 ? 800 : 0)) / 3800);
            word(canvas, language, progress, 1 - fade);
            if (fade > 0) word(canvas, 1 - language, (phase - 5200) / 3800, fade);
            // Text changes near completion of the incoming handwriting, independently of its fade.
            float blend = cycle == 0 ? 1 : Math.max(0, Math.min(1, (phase - 2400) / 500));
            shaderMatrix.setScale(getWidth(), -getHeight()); shaderMatrix.postTranslate(0, getHeight()); dark.setLocalMatrix(shaderMatrix);
            if (blend < 1) { hint.setAlpha(Math.round(255 * (1 - blend))); canvas.drawText(hints[1 - language], getWidth() / 2f, getHeight() * .65f, hint); }
            hint.setAlpha(Math.round(255 * blend)); canvas.drawText(hints[language], getWidth() / 2f, getHeight() * .65f, hint);
            if (running && hasWindowFocus()) postInvalidateOnAnimation();
        }
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
            if (advance && running) { stop(); next.run(); } return true;
        }
        return super.onKeyUp(key, event);
    }
}
