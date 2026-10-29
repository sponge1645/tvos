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
    private final String installedVersion;
    private String[] textSource, textLines;
    private float textWidth;
    private int textLimit;
    private final View owner;
    private final SettingsUpdateProgress progress;
    private final SettingsPopupBackdrop backdrop = new SettingsPopupBackdrop();
    private final SettingsPopupBackdrop.Source backdropSource;
    private final Paint popupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint popupHeading, popupBody, popupButtonText;
    private final Paint.FontMetrics headingMetrics = new Paint.FontMetrics();
    private final Paint.FontMetrics bodyMetrics = new Paint.FontMetrics();
    private final RectF popupPanel = new RectF(), popupButton = new RectF();
    private long updatePopupStarted;
    private boolean updatePopupClosing;
    private int updatePopup, updatePopupFocus; // 0 hidden, 1 current, 2 available, 3 error, 6 ready
    private boolean running, updateRequested, installWhenReady;
    private final SelfUpdate.Listener updateListener = this::onSelfUpdate;
    private float k = 1;

    SettingsUpdateDialog(Activity activity, View owner, SettingsUpdateProgress progress, SettingsPopupBackdrop.Source backdropSource) {
        this.activity = activity;
        installedVersion = versionName(activity);
        this.owner = owner;
        this.progress = progress;
        this.backdropSource = backdropSource;
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
        backdrop.clear();
    }

    void check() {
        updateRequested = true;
        SelfUpdate.get(activity).check(false);
        onSelfUpdate();
    }

    void resize(float scale) {
        k = scale;
        textSource = textLines = null;
        backdrop.clear();
        popupHeading = Ui.text(Ui.displayBold, 33*k, 0xFFFFFFFF);
        popupHeading.setTextAlign(Paint.Align.LEFT);
        popupBody = Ui.text(Ui.displayMedium, 25*k, 0xFF899295);
        popupBody.setTextAlign(Paint.Align.LEFT);
        popupHeading.getFontMetrics(headingMetrics);
        popupBody.getFontMetrics(bodyMetrics);
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
            backdrop.clear();
            updater.install(activity);
            if (updater.status().kind == SelfUpdate.ERROR) {
                updateRequested = true;
                onSelfUpdate();
            }
            return;
        }
        int mode = update.kind == SelfUpdate.AVAILABLE ? 2
                : update.kind == SelfUpdate.READY ? 6
                : update.kind == SelfUpdate.ERROR ? 3 : 1;
        if (updatePopup != mode || updatePopupClosing) showUpdatePopup(mode);
        else owner.invalidate();

    }

    private void showUpdatePopup(int mode) {
        backdrop.clear();
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
            if (code == KeyEvent.KEYCODE_BACK) {
                updateRequested = false;
                installWhenReady = false;
            }
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

    void draw(Canvas c, int w, int h, boolean light, boolean reduceTransparency, boolean reduceMotion) {
        long elapsed = SystemClock.uptimeMillis() - updatePopupStarted;
        float t = reduceMotion ? 1f : Math.min(1f, elapsed / 260f);
        float ease = 1f - (1f-t)*(1f-t)*(1f-t);
        float alpha = updatePopupClosing ? 1f-ease : ease;
        if (updatePopupClosing && t >= 1f) {
            updatePopup = 0;
            updatePopupClosing = false;
            backdrop.clear();
            return;
        }
        if (t < 1f) owner.postInvalidateDelayed(16);
        if (reduceTransparency) backdrop.clear();
        else backdrop.prepare(w, h, light, backdropSource);
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
        else if (updatePopup == 3) lines = new String[] {AppStrings.get(R.string.settings_could_not_update_tvos), update.message};
        else if (updatePopup == 6) lines = new String[] {AppStrings.get(R.string.settings_update_downloaded),
                AppStrings.get(R.string.settings_version) + availableVersion};
        else lines = new String[] {installedVersion.replace("tvOS ", ""), String.format(AppLanguage.current().locale,
                AppStrings.get(R.string.settings_tvos_is_up_to_date), installedVersion)};
        String[] buttons = updatePopup == 2 ? new String[] {AppStrings.get(R.string.settings_download_and_install), AppStrings.get(R.string.settings_update_later)}
                : updatePopup == 6 ? new String[] {AppStrings.get(R.string.settings_install), AppStrings.get(R.string.settings_later)} : new String[] {"OK"};
        float left = 710*k, right = 1210*k;
        float headingHeight = headingMetrics.descent-headingMetrics.ascent;
        float bodyTop = 50*k + headingHeight + 30*k;
        float controlsHeight = (40 + buttons.length*72 + (buttons.length-1)*12 + 25)*k;
        int limit = Math.max(1, (int) ((h-80*k-bodyTop-controlsHeight)/(33*k)));
        float width = right-left-90*k;
        if (textLines == null || textWidth != width || textLimit != limit || !java.util.Arrays.equals(textSource, lines)) {
            textSource = lines;
            textWidth = width; textLimit = limit;
            textLines = UpdateDialogText.wrap(lines, width, limit, popupBody::measureText);
        }
        float textHeight = 0;
        for (String line : textLines) textHeight += (line.isEmpty() ? 22 : 33)*k;
        float bodyHeight = textHeight-33*k + bodyMetrics.descent-bodyMetrics.ascent;
        float panelHeight = bodyTop + bodyHeight + controlsHeight;
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
        // Theme tint overlays the same softly blurred settings snapshot.
        p.clearShadowLayer();
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        if (!reduceTransparency) backdrop.draw(c, panel, 60*k);
        int panelTint = light ? 0xFFC4D5EB : 0xFF1E2425;
        p.setColor(reduceTransparency || !backdrop.ready() ? panelTint : (panelTint & 0x00FFFFFF) | 0xB3000000);
        c.drawRoundRect(panel, 60*k, 60*k, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.2f*k);
        p.setColor(light ? 0x55FFFFFF : 0x24FFFFFF);
        c.drawRoundRect(panel, 60*k, 60*k, p);
        p.setStyle(Paint.Style.FILL);
        Paint heading = popupHeading;
        heading.setColor(light ? 0xFF1C1C1E : 0xFFFFFFFF);
        Paint body = popupBody;
        body.setColor(light ? 0xFF1C1C1E : 0xFF899295);
        c.drawText(AppStrings.get(R.string.settings_tvos_update), left+45*k, top+50*k-headingMetrics.ascent, heading);
        float lineY = top+bodyTop-bodyMetrics.ascent;
        for (String line : textLines) {
            if (!line.isEmpty()) c.drawText(line, left+45*k, lineY, body);
            lineY += (line.isEmpty() ? 22 : 33)*k;
        }
        float buttonTop = bottom - (buttons.length*72+(buttons.length-1)*12+25)*k;
        Paint buttonText = popupButtonText;
        for (int i=0; i<buttons.length; i++) {
            RectF b = popupButton;
            b.set(left+25*k, buttonTop+i*84*k, right-25*k, buttonTop+(i*84+72)*k);
            boolean selected = i == updatePopupFocus || buttons.length == 1;
            // Dialog buttons: static fills, no shadows or per-frame focus animation.
            p.clearShadowLayer();
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            boolean primary = selected && (buttons.length == 1 || updatePopup == 2 || updatePopup == 6);
            p.setColor(primary ? 0xFFF7F8FA : light ? 0xFFB9C5D3 : 0xFF292D2C);
            c.drawRoundRect(b, 36*k, 36*k, p);
            buttonText.setColor(primary ? 0xFF171A1F : light ? 0xFF1C1C1E : 0xFFF7F8FA);
            c.drawText(buttons[i], b.centerX(), b.centerY()+Ui.halfCap(buttonText), buttonText);
        }
        if (fading) c.restore();
        c.restore();
        c.restore();
    }

}
