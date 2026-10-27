package io.tvos;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

/** Settings update modal: listener lifecycle, input, transition and drawing. No menu ownership. */
final class SettingsUpdateDialog {
    private final Activity activity;
    private final View owner;
    private final SettingsUpdateProgress progress;
    private final Paint popupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint popupHeading, popupBody, popupButtonText;
    private final RectF popupPanel = new RectF(), popupButton = new RectF();
    private long updatePopupStarted;
    private boolean updatePopupClosing;
    private int updatePopup, updatePopupFocus; // 0 hidden, 1 current, 2 available, 3 error, 4 checking, 6 ready
    private boolean running, updateRequested, installWhenReady;
    private final SelfUpdate.Listener updateListener = this::onSelfUpdate;
    private float k = 1;

    SettingsUpdateDialog(Activity activity, View owner, SettingsUpdateProgress progress) {
        this.activity = activity;
        this.owner = owner;
        this.progress = progress;
    }

    boolean visible() { return updatePopup != 0; }

    void resume() {
        running = true;
        SelfUpdate.get(activity).addListener(updateListener);
        onSelfUpdate();
        SelfUpdate.get(activity).maybeAutomatic(false);
    }

    void pause() {
        running = false;
        SelfUpdate.get(activity).removeListener(updateListener);
    }

    void check() {
        updateRequested = true;
        SelfUpdate.get(activity).check(false);
        onSelfUpdate();
    }

    void resize(float scale) {
        k = scale;
        popupHeading = Ui.text(Ui.displaySemibold, 38*k, 0xFFFFFFFF);
        popupHeading.setTextAlign(Paint.Align.LEFT);
        popupBody = Ui.text(Ui.regular, 25*k, 0xFF999999);
        popupBody.setTextAlign(Paint.Align.LEFT);
        popupButtonText = Ui.text(Ui.displaySemibold, 30*k, 0xFFFFFFFF);
        popupButtonText.setTextAlign(Paint.Align.CENTER);
    }

    static String versionName(Context c) {
        try {
            return "tvOS " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "tvOS";
        }
    }

    private void onSelfUpdate() {
        if (!running) return;
        SelfUpdate updater = SelfUpdate.get(activity);
        SelfUpdate.Status update = updater.status();
        progress.observe(update.kind, update.percent, SystemClock.uptimeMillis());
        owner.invalidate();
        if (!updateRequested) return;
        if (update.kind == SelfUpdate.DOWNLOADING) {
            // Fade back to the page while preserving the user's download-and-install intent.
            if (updatePopup != 0 && !updatePopupClosing) {
                updatePopupClosing = true;
                updatePopupStarted = SystemClock.uptimeMillis();
            }
            return;
        }
        if (update.kind == SelfUpdate.CHECKING) return;
        if (update.kind == SelfUpdate.READY && installWhenReady) {
            installWhenReady = false;
            updateRequested = false;
            updatePopup = 0;
            updatePopupClosing = false;
            updater.install(activity);
            if (updater.status().kind == SelfUpdate.ERROR) {
                updateRequested = true;
                onSelfUpdate();
            }
            return;
        }
        int mode = update.kind == SelfUpdate.CHECKING ? 4
                : update.kind == SelfUpdate.AVAILABLE ? 2
                : update.kind == SelfUpdate.READY ? 6
                : update.kind == SelfUpdate.ERROR ? 3 : 1;
        if (updatePopup != mode || updatePopupClosing) showUpdatePopup(mode);
        else owner.invalidate();

    }

    private void showUpdatePopup(int mode) {
        updatePopup = mode;
        updatePopupFocus = 0;
        updatePopupClosing = false;
        updatePopupStarted = SystemClock.uptimeMillis();
        owner.invalidate();
    }

    private void closeUpdatePopup() {
        updateRequested = false;
        installWhenReady = false;
        updatePopupClosing = true;
        updatePopupStarted = SystemClock.uptimeMillis();
        owner.invalidate();
    }

    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_UP) return true;
        int code = e.getKeyCode();
        if (updatePopupClosing) {
            if (code == KeyEvent.KEYCODE_BACK) closeUpdatePopup();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) { closeUpdatePopup(); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (updatePopup == 2 || updatePopup == 6) updatePopupFocus = code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
            owner.invalidate(); return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (updatePopup == 2 && updatePopupFocus == 0) {
                installWhenReady = true;
                SelfUpdate.get(activity).download();
            } else if (updatePopup == 6 && updatePopupFocus == 0) {
                SelfUpdate.get(activity).install(activity);
            } else closeUpdatePopup();
            return true;
        }
        return true;
    }

    void draw(Canvas c, int w, int h, boolean light) {
        long elapsed = SystemClock.uptimeMillis() - updatePopupStarted;
        float t = Math.min(1f, elapsed / 260f);
        float ease = 1f - (1f-t)*(1f-t)*(1f-t);
        float alpha = updatePopupClosing ? 1f-ease : ease;
        if (updatePopupClosing && t >= 1f) {
            updatePopup = 0;
            updatePopupClosing = false;
            return;
        }
        if (t < 1f) owner.postInvalidateDelayed(16);
        c.save();
        Paint p = popupPaint;
        p.reset();
        p.setAntiAlias(true);
        // Match the underlying settings theme: warm translucent dimming in
        // light mode, near-black dimming in dark mode.
        p.setColor(light ? 0xFF62645B : 0xFF000000);
        p.setAlpha(Math.round((light ? 105 : 175) * alpha));
        c.drawRect(0, 0, w, h, p);
        String[] lines;
        SelfUpdate.Status update = SelfUpdate.get(activity).status();
        String availableVersion = update.release == null ? "" : update.release.versionName;
        if (updatePopup == 2) lines = new String[] {
            AppStrings.get(R.string.settings_update_available).replace("%1$s", availableVersion),
            AppStrings.get(R.string.settings_download_and_install_it_now),
            "",
            AppStrings.get(R.string.settings_confirm_installation_on_your_tv)
        };
        else if (updatePopup == 3) lines = new String[] {AppStrings.get(R.string.settings_could_not_update_tvos),
                update.message.length() <= 44 ? update.message : update.message.substring(0, 44),
                update.message.length() <= 44 ? "" : update.message.substring(44)};
        else if (updatePopup == 4) lines = new String[] {AppStrings.get(R.string.settings_checking_for_updates)};
        else if (updatePopup == 6) lines = new String[] {AppStrings.get(R.string.settings_update_downloaded),
                AppStrings.get(R.string.settings_version) + availableVersion};
        else lines = new String[] {versionName(activity).replace("tvOS ", ""), String.format(AppLanguage.current().locale,
                AppStrings.get(R.string.settings_tvos_is_up_to_date), versionName(activity))};
        String[] buttons = updatePopup == 2 ? new String[] {AppStrings.get(R.string.settings_download_and_install), AppStrings.get(R.string.settings_update_later)}
                : updatePopup == 6 ? new String[] {AppStrings.get(R.string.settings_install), AppStrings.get(R.string.settings_later)} : new String[] {"OK"};
        float left = 724.8f*k, right = 1195.2f*k;
        float textHeight = 0;
        for (String line : lines) textHeight = drawParagraph(null, line, 0, textHeight, right-left-100*k, popupBody);
        float panelHeight = 142*k + textHeight + (8 + buttons.length*72 + (buttons.length-1)*12 + 32)*k;
        float top = (h-panelHeight)/2f, bottom = top+panelHeight;
        float cx = (left+right)/2f, cy = (top+bottom)/2f;
        float scale = 0.94f + 0.06f*ease;
        if (updatePopupClosing) scale = 1f - 0.06f*ease;
        c.save();
        c.scale(scale, scale, cx, cy);
        boolean fading = alpha < 0.999f;
        if (fading) c.saveLayerAlpha(left-70*k, top-70*k, right+70*k, bottom+70*k, Math.round(255*alpha));
        RectF panel = popupPanel;
        panel.set(left, top, right, bottom);
        // Solid panel palette is shared by both settings themes.
        p.clearShadowLayer();
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0xFF1E2425);
        c.drawRoundRect(panel, 72*k, 72*k, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f*k);
        p.setColor(light ? 0x55FFFFFF : 0x24FFFFFF);
        c.drawRoundRect(panel, 72*k, 72*k, p);
        p.setStyle(Paint.Style.FILL);
        Paint heading = popupHeading;
        heading.setColor(0xFFFFFFFF);
        Paint body = popupBody;
        body.setColor(0xFF999999);
        c.drawText(AppStrings.get(R.string.settings_tvos_update), left+50*k, top+86*k, heading);
        float lineY = top+142*k;
        for (String line : lines) lineY = drawParagraph(c, line, left+50*k, lineY, right-left-100*k, body);
        float buttonTop = bottom - (buttons.length*72+(buttons.length-1)*12+32)*k;
        Paint buttonText = popupButtonText;
        for (int i=0; i<buttons.length; i++) {
            RectF b = popupButton;
            b.set(left+24*k, buttonTop+i*84*k, right-24*k, buttonTop+(i*84+72)*k);
            boolean selected = i == updatePopupFocus || buttons.length == 1;
            // Apple TV-style buttons: static fills, no shadows or per-frame focus animation.
            p.clearShadowLayer();
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            boolean primary = selected && (updatePopup == 1 || updatePopup == 2 || updatePopup == 6);
            p.setColor(primary ? 0xFFF7F8FA : 0xFF292D2C);
            c.drawRoundRect(b, 36*k, 36*k, p);
            buttonText.setColor(primary ? 0xFF171A1F : 0xFFF7F8FA);
            c.drawText(buttons[i], b.centerX(), b.centerY()+Ui.halfCap(buttonText), buttonText);
        }
        if (fading) c.restore();
        c.restore();
        c.restore();
    }

    private float drawParagraph(Canvas c, String text, float x, float y, float width, Paint paint) {
        if (text.isEmpty()) return y + 22*k;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && paint.measureText(candidate) > width) {
                if (c != null) c.drawText(line.toString(), x, y, paint);
                y += 33*k;
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (c != null) c.drawText(line.toString(), x, y, paint);
        return y + 33*k;
    }

}
