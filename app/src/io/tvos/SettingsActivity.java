// TVOS_WEATHER_FADE_V1
package io.tvos;

import static io.tvos.SettingsContent.*;
import static io.tvos.SettingsValues.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * tvOS's settings (the app's icon, "All Apps", or the gear on the home screen), with a two-column layout:
 * the big symbol and title on the left, the list on the right with the focused row in white. "Default Launcher"
 * opens the tvOS / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private SettingsPreview preview;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        preview = new SettingsPreview(this, view, view::resume);
        setContentView(preview.host());
        WifiPermission.requestOnce(this);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.tvos/.SettingsActivity --ez screensaver true (straight to the preview).
        if (!preview.hasDream() && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        preview.close();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview.onKey(event)) return true;
        if (!preview.active() && view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() { preview.show(); }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 10;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 62, ROW_GAP = 12, CHOICE_H = 62;
        private static final float SETTINGS_SHADOW_BLUR = 40, SETTINGS_SHADOW_DROP = 15, SETTINGS_SHADOW_OPACITY = 0.40f;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private Click tvosClick; // TVOS_AERIAL_CONTROLS_V1: same sound as HomeView
        private final Scene scene = new Scene();
        private final Shadows rowShadows = new Shadows();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final SettingsCitySearch citySearch;
        private final SettingsValues valueSource;
        private final SettingsDeviceDialogs deviceDialogs;
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final SettingsDockEditor dockEditor = new SettingsDockEditor();
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private final Spring listScroll = new Spring(0.30f, 1f, 0);
        private final Spring mainScroll = new Spring(0.30f, 1f, 0);
        private final List<String[]> cities = new ArrayList<>();
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private final RectF logoBounds = new RectF();
        private final Paint.FontMetrics logoMetrics = new Paint.FontMetrics();
        private final Paint trackedGlyphPaint = new Paint();
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrowPath = new Path();
        private final TextPaint shelfMeasurePaint = new TextPaint();
        private String serviceValueSource, serviceValueShown;
        private float serviceValueWidth = -1, serviceValueSize = -1;
        private final SettingsOrderEditor categoryEditor = new SettingsOrderEditor(SettingsOrderEditor.Kind.CATEGORIES);
        private final SettingsOrderEditor buttonEditor = new SettingsOrderEditor(SettingsOrderEditor.Kind.MENU_BUTTONS);
        private final TextPaint descriptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private StaticLayout descriptionLayout;
        private String descriptionText;
        private int descriptionWidth;
        private final Matrix logoMatrix = new Matrix();
        private final Path logoCutout = new Path();
        private final Shader darkLogoGradient = new LinearGradient(0, 1, 1, 0,
                0xFF51595B, 0xFF55646B, Shader.TileMode.CLAMP);
        private float logoScale = -1;
        // TVOS_RAM_GPU_PHASE1: smooth static gradient at half resolution.
        // Saves ~75% of background bitmap RAM and texture upload bandwidth.
        private final SettingsBackground background = new SettingsBackground();
        private final SettingsUpdateDialog updateDialog;
        private final SettingsUpdateProgress updateProgress = new SettingsUpdateProgress();
        private JSONObject state = new JSONObject();
        private int screen = MAIN, focus, settingsGroup = GENERAL, returnFocus = 0;
        // Shared, short-lived Canvas transition for all settings submenus.
        private static final long MENU_TRANSITION_MS = 280;
        private int drawnScreen = -1, transitionFrom = -1;
        private long transitionStarted, focusReadyAt;
        private String pageTitleShown, pageTitlePrevious;
        private long pageTitleStarted;
        private String updateInstalledVersion, updateCheckShown;
        private long updateCheckAt = Long.MIN_VALUE;
        private AppLanguage updateCheckLanguage;
        private final Paint listEdgeMask = new Paint();
        {
            listEdgeMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        private boolean running, okDown, backDown, wasBusy;
        private boolean lightAppearance;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, rowLabel, rowValue, rowSub, warn, spinner, white, shadow;

        // Пробная отрисовка с дополнительным межбуквенным интервалом.
        private void drawTrackedText(Canvas canvas, String text,
                                     float x, float y, Paint paint,
                                     float tracking) {
            if (text == null || text.isEmpty()) return;

            float spacing = paint.getTextSize() * tracking;
            int count = text.codePointCount(0, text.length());

            float width = paint.measureText(text)
                    + spacing * Math.max(0, count - 1);

            float cursor = x;
            if (paint.getTextAlign() == Paint.Align.CENTER) {
                cursor -= width / 2f;
            } else if (paint.getTextAlign() == Paint.Align.RIGHT) {
                cursor -= width;
            }

            Paint glyphPaint = trackedGlyphPaint;
            glyphPaint.set(paint);
            glyphPaint.setTextAlign(Paint.Align.LEFT);

            for (int i = 0; i < text.length();) {
                int next = text.offsetByCodePoints(i, 1);
                canvas.drawText(text, i, next, cursor, y, glyphPaint);
                cursor += glyphPaint.measureText(text, i, next) + spacing;
                i = next;
            }
        }

        private Paint pageTitle, logoInk, logoTile, capsule = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                if (screen == REGION) AutoRegion.ensure(getContext());
                state = HomeMode.state(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(NoticeText.render(newest));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            deviceDialogs = new SettingsDeviceDialogs(a, this::invalidate);
            citySearch = new SettingsCitySearch(handler, () -> running, () -> screen, this::showCityResults, this::invalidate);
            updateDialog = new SettingsUpdateDialog(a, this, updateProgress, this::drawSettingsContent);
            valueSource = new SettingsValues(a, buttonEditor, this::freeActive, this::weatherVisible, citySearch::status);
            tvosClick = new Click(a);
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            HomeData.Weather wx = HomeData.get(a).weather;
            updateAppearance(System.currentTimeMillis());
            Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
            scene.setMotion(0.5f, 0.2f);
            scene.applyPalette();
            setFocusable(true);
            setContentDescription("tvOS");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    if (screen == ABOUT && id != 1) return;
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) {
                    if (screen == ABOUT) { if (id == 1) { focus = 0; activate(); } }
                    else { focus = id - 1; activate(); }
                }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    String[] names = menuLabels();
                    return row < names.length ? names[row] : "";
                }
                case MEDIA: case MOVIE_SERVICE: case GENERAL: case WIDGETS: case SYSTEM: case RESET: case ABOUT: case SCREENSAVER: case REGION: case ACCESSIBILITY: case TOP_PANEL: {
                    String[] names = menuLabels(), values = valueSource.menu(screen, rowCount());
                    return row < names.length ? names[row] + (values[row].isEmpty() ? "" : ", " + values[row]) : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "tvOS, " + AppStrings.get(R.string.settings_no_ads_with_the_new_screensaver)
                            : "Built-in, " + AppStrings.get(R.string.settings_the_tv_s_factory_home_screen);
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? AppStrings.get(R.string.settings_in_use) : "") + ", " + (row + 1) + AppStrings.get(R.string.settings_of_2);
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = keys ? keyLabels() : settingsGroup == SYSTEM ? new String[] {settingLabels()[randomRow()]} : settingLabels();
                    String[] values = keys ? keyValues(appPkgs, appLabels) : settingsGroup == SYSTEM ? new String[] {valueSource.settings()[randomRow()]} : valueSource.settings();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? AppStrings.get(R.string.settings_selected) : "")
                            + ", " + (at + 1) + AppStrings.get(R.string.settings_of) + pickLabels.size();
                }
                case MENU_BUTTONS: {
                    if (row == buttonEditor.size()) return AppStrings.get(R.string.settings_vpn) + ", " + valueSource.vpnApp();
                    if (row < 0 || row >= buttonEditor.size()) return "";
                    int id = buttonEditor.get(row);
                    return menuButtonLabel(id) + ", " + (Prefs.topButtonEnabled(id)
                            ? AppStrings.get(R.string.settings_position) + Prefs.topButtonPosition(id) : AppStrings.get(R.string.settings_off));
                }
                case CATEGORY_EDIT: {
                    if (row < 0 || row >= categoryEditor.size()) return "";
                    int id = categoryEditor.get(row);
                    return categoryLabel(id) + ", " + (Prefs.categoryEnabled(id)
                            ? AppStrings.get(R.string.settings_position) + Prefs.categoryPosition(id) : AppStrings.get(R.string.settings_off));
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= dockEditor.size()) return "";
                    String[] app = dockEditor.get(at);
                    int place = dockEditor.position(app[0]);
                    return app[1] + ", " + (place >= 0 ? AppStrings.get(R.string.settings_on_the_shelf_place) + (place + 1)
                            : AppStrings.get(R.string.settings_not_on_the_shelf));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN || screen == MEDIA || screen == MOVIE_SERVICE || screen == GENERAL || screen == WIDGETS || screen == WEATHER || screen == DATE_TIME || screen == MENU_BUTTONS || screen == SYSTEM || screen == RESET || screen == TOP_PANEL || screen == ACCESSIBILITY || screen == REGION || screen == ABOUT || screen == SCREENSAVER) {
                top = LIST_TOP + row * (ROW_H + (screen == MAIN ? MAIN_ROW_GAP : ABOUT_ROW_GAP))
                        + (screen == ABOUT ? (row == 0 ? ABOUT_NAME_Y_OFFSET : ABOUT_DETAILS_Y_OFFSET) : ABOUT_NAME_Y_OFFSET);
                height = ROW_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + ABOUT_NAME_Y_OFFSET + row * (ROW_H + ABOUT_ROW_GAP);
                height = ROW_H;
            } else if (screen == SHELF_EDIT) {
                top = LIST_TOP + ABOUT_NAME_Y_OFFSET + row * (ROW_H + ABOUT_ROW_GAP);
                height = ROW_H;
            } else {
                top = LIST_TOP + ABOUT_NAME_Y_OFFSET + row * (ROW_H + ABOUT_ROW_GAP);
                height = ROW_H;
            }
            if (screen == MAIN) top -= mainScroll.value * (ROW_H + MAIN_ROW_GAP);
            focusBox.set(LIST_X * k, top * k, (LIST_R + ABOUT_NAME_RIGHT_EXTENSION) * k, (top + height) * k);
            if (scrolling()) focusBox.offset(0, (pickScroll - listScroll.value) * (ROW_H + ABOUT_ROW_GAP) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            valueSource.clearAppCache();
            HomeData.get(getContext()).localizeCity(Prefs.language());
            updateDialog.resume();
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            updateDialog.pause();
            citySearch.cancel();
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
            background.clear(); // Free cached bitmap when settings are hidden.
            rowShadows.clear();
            descriptionLayout = null;
            descriptionText = null;
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (tvosClick == null) tvosClick = new Click(activity);
        }

        @Override protected void onDetachedFromWindow() {
            pause();
            if (tvosClick != null) {
                tvosClick.release();
                tvosClick = null;
            }
            super.onDetachedFromWindow();
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving || (!updateDialog.visible() && SystemClock.uptimeMillis() - lastSky >= 100)) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            rowShadows.clear();
            descriptionLayout = null;
            descriptionText = null;
            arrowPaint.setColor(0xFFA1A3A1);
            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeWidth(ABOUT_ARROW_STROKE * k);
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            arrowPath.reset();
            arrowPath.moveTo(-ABOUT_ARROW_LEFT * k, -ABOUT_ARROW_HALF_HEIGHT * k);
            arrowPath.lineTo(ABOUT_ARROW_TIP * k, 0);
            arrowPath.lineTo(-ABOUT_ARROW_LEFT * k, ABOUT_ARROW_HALF_HEIGHT * k);
            float listTop = 148 * k, listSpan = Math.max(1f, h - listTop);
            float topStop = Math.min(0.25f, 38 * k / listSpan);
            float bottomStop = Math.max(topStop, 1f - 64 * k / listSpan);
            listEdgeMask.setShader(new LinearGradient(0, listTop, 0, h,
                    new int[] {0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                    new float[] {0f, topStop, bottomStop, 1f}, Shader.TileMode.CLAMP));
            scene.setSize(w, h);
            background.clear();
            updateDialog.resize(k);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 28 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.displaySemibold, 36 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.displayRegular, 36 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 27 * k, 0x8CFFFFFF);
            warn = Ui.text(Ui.regular, 27 * k, AMBER);
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(SETTINGS_SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
            pageTitle = Ui.text(Ui.displayBold, 56 * k, 0x99FFFFFF);
            pageTitle.setTextAlign(Paint.Align.CENTER);
            logoInk = Ui.text(Ui.displaySemibold, 58 * k, 0xFF173344);
            logoInk.setTextAlign(Paint.Align.CENTER);
            logoTile = new Paint(Paint.ANTI_ALIAS_FLAG);
            logoTile.setColor(0xFF8897AC);
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return 10;
                case MEDIA: return 1;
                case MOVIE_SERVICE: return MovieService.count();
                case APPS_DOWNLOADS: return 7;
                case GENERAL: return 8;
                case SCREENSAVER: return 5;
                case WIDGETS: return 2;
                case WEATHER: return 2;
                case DATE_TIME: return 1;
                case SYSTEM: return 4;
                case RESET: return 2;
                case TOP_PANEL: return 3;
                case CATEGORY_EDIT: return categoryEditor.size();
                case ACCESSIBILITY: return 3;
                case REGION: return 2;
                case MENU_BUTTONS: return buttonEditor.size() + 1;
                case UPDATES: return 2;
                case ABOUT: return 8;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingsGroup == SYSTEM ? 1 : settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, dockEditor.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            updateAppearance(System.currentTimeMillis());
            long up = SystemClock.uptimeMillis();
            syncMenuTransition(up);
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            if (scrolling()) {
                listScroll.target = pickScroll;
                if (localFlag("reduce_motion") || Math.abs(listScroll.target - listScroll.value) > PICK_VISIBLE)
                    listScroll.snap(pickScroll);
                else if (listScroll.step(dt)) postInvalidateOnAnimation();
            }
            stepMainScroll(dt);
            stepRowFocus(dt, up);
            float seconds = (up - startedAt) / 1000f;
            if (!updateDialog.visible() && up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            drawSettingsContent(c);
            if (updateDialog.visible()) updateDialog.draw(c, w, h, lightSettings(), localFlag("reduce_transparency"), localFlag("reduce_motion"));
        }

        private void drawSettingsContent(Canvas c) {
            background.draw(c, w, h, lightSettings());
            pageTitle.setColor(lightSettings() ? 0xFF738190 : 0xFF5A676D);
            drawPageTitle(c, SystemClock.uptimeMillis());
            drawSettingsLogo(c);
            drawListViewport(c);
            drawTileDescription(c);
        }

        /** The wordmark is a real cutout: the existing background remains visible through it. */
        private void drawSettingsLogo(Canvas c) {
            if (logoScale != k) {
                logoScale = k;
                logoBounds.set(254 * k, 236 * k, 790 * k, 772 * k);
                logoInk.setTextSize(58 * k);
                float width = logoInk.measureText("tvOS");
                if (width > 0f) logoInk.setTextSize(logoInk.getTextSize() * logoBounds.width() / (2f * width));
                logoInk.getFontMetrics(logoMetrics);
                float baseline = logoBounds.centerY() - (logoMetrics.ascent + logoMetrics.descent) / 2f;
                logoCutout.reset();
                logoInk.getTextPath("tvOS", 0, 4, logoBounds.centerX(), baseline, logoCutout);
                logoMatrix.setScale(logoBounds.width(), logoBounds.height());
                logoMatrix.postTranslate(logoBounds.left, logoBounds.top);
                darkLogoGradient.setLocalMatrix(logoMatrix);
            }
            logoTile.setColor(0xFF8897AC);
            logoTile.setShader(lightSettings() ? null : darkLogoGradient);
            c.save();
            c.clipOutPath(logoCutout);
            c.drawRoundRect(logoBounds, 116 * k, 116 * k, logoTile);
            c.restore();
        }

        /** Reset inherited selection before drawing either page of a menu transition. */
        private void syncMenuTransition(long now) {
            if (drawnScreen == screen) return;
            boolean reduceMotion = localFlag("reduce_motion");
            boolean previous = drawnScreen >= 0;
            transitionFrom = previous && !reduceMotion && !updateDialog.visible()
                    && (parentScreen(screen) == drawnScreen || parentScreen(drawnScreen) == screen)
                    ? drawnScreen : -1;
            transitionStarted = now;
            focusReadyAt = now + (previous && !reduceMotion ? MENU_TRANSITION_MS : 0);
            drawnScreen = screen;
            for (Spring spring : pop) spring.snap(0);
        }

        private void stepMainScroll(float dt) {
            if (screen != MAIN) return;
            mainScroll.target = Math.max(0, focus - (PICK_VISIBLE - 1));
            if (localFlag("reduce_motion")) mainScroll.snap(mainScroll.target);
            else if (mainScroll.step(dt)) postInvalidateOnAnimation();
        }

        private void stepRowFocus(float dt, long now) {
            boolean ready = now >= focusReadyAt;
            boolean reduceMotion = localFlag("reduce_motion");
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = ready && (screen != ABOUT || i == 0)
                        && i == focus && i < rowCount() ? 1 : 0;
                if (reduceMotion) pop[i].snap(pop[i].target);
                else pop[i].step(dt);
            }
        }

        /** Crossfade only the lettering; the background keeps its original brightness. */
        private void drawPageTitle(Canvas c, long now) {
            String next = screenTitle();
            if (pageTitleShown == null) pageTitleShown = next;
            if (!next.equals(pageTitleShown)) {
                pageTitlePrevious = pageTitleShown;
                pageTitleShown = next;
                pageTitleStarted = now;
            }
            float t = localFlag("reduce_motion") ? 1f : Math.min(1f,
                    (now - pageTitleStarted) / (float) MENU_TRANSITION_MS);
            if (pageTitlePrevious == null || t >= 1f) {
                pageTitlePrevious = null;
                drawTrackedText(c, pageTitleShown, w / 2f, 111 * k, pageTitle, 0.02f);
                return;
            }
            float eased = t * t * (3f - 2f * t);
            int alpha = pageTitle.getAlpha();
            pageTitle.setAlpha(Math.round(alpha * (1f - eased)));
            drawTrackedText(c, pageTitlePrevious, w / 2f, 111 * k, pageTitle, 0.02f);
            pageTitle.setAlpha(Math.round(alpha * eased));
            drawTrackedText(c, pageTitleShown, w / 2f, 111 * k, pageTitle, 0.02f);
            pageTitle.setAlpha(alpha);
            postInvalidateDelayed(16);
        }

        /** Fade the list into the untouched background at both vertical viewport edges. */
        private void drawListViewport(Canvas c) {
            float left = 960 * k, top = 148 * k;
            int layer = c.saveLayer(left, top, w, h, null);
            c.clipRect(left, top, w, h);
            if (transitionFrom != -1) drawMenuTransition(c);
            else drawScreenContent(c);
            c.drawRect(left, top, w, h, listEdgeMask);
            c.restoreToCount(layer);
        }

        /** Resolve once per frame so every surface uses the launcher's same sunrise/sunset policy. */
        private void updateAppearance(long now) {
            lightAppearance = !HomeData.get(getContext()).isNight(now);
        }

        private boolean lightSettings() { return lightAppearance; }

        private int normalInk() { return lightSettings() ? 0xE5162730 : 0xF2FFFFFF; }
        private int secondaryInk() { return lightSettings() ? 0xFF758390 : 0xFF6C7B81; }
        private int valueInk(float focusAmount) { return lightSettings() ? blend(secondaryInk(), 0xFF999999, focusAmount) : 0xFF999999; }
        private int arrowInk() { return lightSettings() ? 0x8A34444C : 0x66FFFFFF; }

        private String screenTitle() {
            return SettingsContent.screenTitle(screen, settingsGroup, pickFor);
        }

        private String[] menuLabels() {
            return screen == MENU_BUTTONS ? menuButtonLabels() : SettingsContent.menuLabels(screen);
        }
        private android.content.SharedPreferences localPrefs() { return getContext().getSharedPreferences("settings", Context.MODE_PRIVATE); }
        private boolean localFlag(String key) { return localPrefs().getBoolean(key, false); }
        private void setLocalFlag(String key, boolean value) { localPrefs().edit().putBoolean(key, value).apply(); invalidate(); }
        private void cycleDreamSource(int direction) {
            String[] choices = {"apple", "google", "na"};
            String value = localPrefs().getString("video_dream_source", "google");
            int at = 0;
            for (int i = 0; i < choices.length; i++) if (choices[i].equals(value)) at = i;
            localPrefs().edit().putString("video_dream_source", choices[(at + direction + choices.length) % choices.length]).apply();
            invalidate();
        }
        // Two independent switches. Old four-state preference is read as a migration fallback.
        private boolean weatherVisible(boolean top) {
            if (!Prefs.weather()) return false;
            String key = top ? "weather_show_top" : "weather_show_bottom";
            if (localPrefs().contains(key)) return localPrefs().getBoolean(key, true);
            int old = localPrefs().getInt("weather_placement", 3);
            return top ? (old == 1 || old == 3) : (old == 2 || old == 3);
        }
        private void toggleWeather(boolean top) {
            boolean newTop = top ? !weatherVisible(true) : weatherVisible(true);
            boolean newBottom = top ? weatherVisible(false) : !weatherVisible(false);
            localPrefs().edit()
                    .putBoolean("weather_show_top", newTop)
                    .putBoolean("weather_show_bottom", newBottom)
                    .putInt("weather_placement", (newTop ? 1 : 0) | (newBottom ? 2 : 0))
                    .apply();
            Prefs.setWeather(newTop || newBottom);
            HomeData.get(getContext()).settingsChanged();
            invalidate();
        }
        private boolean vpnTopEnabled() { return Prefs.topButtonEnabled(2); }
        private void toggleAutomaticRegion() { Prefs.setAutomaticRegion(!Prefs.automaticRegion()); AutoRegion.ensure(getContext()); invalidate(); }
        // Legacy display/Wi-Fi information remains available on older TV firmware.
        @SuppressWarnings("deprecation")
        // Approved About-screen geometry (all dimensions are scaled by k).
        private static final float ABOUT_ROW_GAP = 22f, MAIN_ROW_GAP = 22f;
        private static final float ABOUT_NAME_Y_OFFSET = 3f;
        private static final float ABOUT_DETAILS_Y_OFFSET = 13f;
        private static final float ABOUT_NAME_RIGHT_EXTENSION = 6f;
        private static final float ABOUT_ARROW_STROKE = 3.4425f;
        private static final float ABOUT_ARROW_HALF_HEIGHT = 13.77f;
        private static final float ABOUT_ARROW_LEFT = 11.475f;
        private static final float ABOUT_ARROW_TIP = 2.295f;

        // Approved Name-button capsule: geometry stays separate from text hit coordinates.
        private float drawApprovedCapsule(Canvas c, int i) {
            float left = rowRect.left, top = rowRect.top;
            float right = rowRect.right, bottom = rowRect.bottom;
            float labelX = left + rowRect.width() * 0.10f + 10 * k;
            rowRect.set(labelX - 30 * k, top - 5 * k, right + 16 * k, bottom + 5 * k);
            float focusAmount = drawRowBackground(c, i);
            rowRect.set(left, top, right, bottom);
            return focusAmount;
        }

        private void drawApprovedChevron(Canvas c) {
            float x = rowRect.right - 13 * k, y = rowRect.centerY();
            c.save();
            c.translate(x, y);
            c.drawPath(arrowPath, arrowPaint);
            c.restore();
        }

        private void setApprovedRowRect(int i) {
            float y = (LIST_TOP + i * (ROW_H + ABOUT_ROW_GAP) + ABOUT_NAME_Y_OFFSET) * k;
            rowRect.set(LIST_X * k, y, (LIST_R + ABOUT_NAME_RIGHT_EXTENSION) * k, y + ROW_H * k);
        }

        /** Parent hierarchy also determines animation direction. No per-screen animation code. */
        private int parentScreen(int child) {
            switch (child) {
                case MEDIA: case GENERAL: case SCREENSAVER: case REMOTE_KEYS: case ACCESSIBILITY: case APPS_DOWNLOADS:
                case SHELF_EDIT: case TOP_PANEL: case SYSTEM:
                    return MAIN;
                case MOVIE_SERVICE: return MEDIA;
                case ABOUT: return GENERAL;
                case HOME_CHOICE: return SYSTEM;
                case MENU_BUTTONS: case CATEGORY_EDIT: return TOP_PANEL;
                case REGION: return GENERAL;
                case WIDGETS: return TOP_PANEL;
                case WEATHER: case DATE_TIME: return WIDGETS;
                case UPDATES: case RESET: return SYSTEM;
                case HOME_SETTINGS: return settingsGroup == SYSTEM ? TOP_PANEL : settingsGroup;
                case PICKER: return pickFor == PICK_CITY ? REGION
                        : pickFor == PICK_VPN ? MENU_BUTTONS : REMOTE_KEYS;
                default: return -1;
            }
        }

        private void drawScreenContent(Canvas c) {
            switch (screen) {
                case MAIN: case MEDIA: case MOVIE_SERVICE: case GENERAL: case WIDGETS: case WEATHER: case DATE_TIME:
                case SYSTEM: case RESET: case TOP_PANEL:
                case ACCESSIBILITY: case APPS_DOWNLOADS: case REGION: case ABOUT: case SCREENSAVER:
                case UPDATES: drawMenu(c); break;
                case HOME_CHOICE: drawChoice(c); break;
                case PICKER: drawPicker(c); break;
                case SHELF_EDIT: drawShelfEditor(c); break;
                case CATEGORY_EDIT: drawCategoryEditor(c); break;
                case MENU_BUTTONS: drawMenuButtonEditor(c); break;
                default: drawList(c); break;
            }
        }

        /** Animate the right-hand list separately from the title crossfade and fixed background/logo. */
        private void drawMenuTransition(Canvas c) {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - transitionStarted)
                    / (float) MENU_TRANSITION_MS);
            if (t >= 1f) {
                transitionFrom = -1;
                drawScreenContent(c);
                return;
            }
            float eased = t * t * (3f - 2f * t);
            boolean forward = parentScreen(screen) == transitionFrom;
            float direction = forward ? -1f : 1f;
            int target = screen;
            int previous = transitionFrom;
            // Clip to list area to avoid painting across the fixed tvOS logo.
            c.save();
            c.clipRect(960 * k, 140 * k, w, h);
            c.save();
            c.translate(direction * 105f * k * eased, 0);
            int oldLayer = c.saveLayerAlpha(960 * k, 140 * k, w, h,
                    Math.max(0, Math.min(255, Math.round(255 * (1f - eased)))));
            screen = previous;
            drawScreenContent(c);
            screen = target;
            c.restoreToCount(oldLayer);
            c.restore();
            c.save();
            c.translate(-direction * 90f * k * (1f - eased), 0);
            int newLayer = c.saveLayerAlpha(960 * k, 140 * k, w, h,
                    Math.max(0, Math.min(255, Math.round(255 * eased))));
            drawScreenContent(c);
            c.restoreToCount(newLayer);
            c.restore();
            c.restore();
            postInvalidateDelayed(16);
        }

        private boolean menuChevronVisible(int row) {
            switch (screen) {
                case SCREENSAVER: return row != 4;
                case ACCESSIBILITY: return row != 2;
                case MOVIE_SERVICE: case APPS_DOWNLOADS: case RESET: return false;
                case UPDATES: return row == 1;
                case SYSTEM: return row != 3;
                case REGION: return row == 0 || !Prefs.automaticRegion();
                default: return true;
            }
        }

        private void drawMenu(Canvas c) {
            String[] labels = menuLabels(), values = valueSource.menu(screen, rowCount());
            for (int pass = 0; pass < labels.length; pass++) {
                int i = paintRow(pass, 0, labels.length, focus);
                float y = (LIST_TOP + i * (ROW_H + (screen == MAIN ? MAIN_ROW_GAP : ABOUT_ROW_GAP))
                        + (screen == ABOUT ? (i == 0 ? ABOUT_NAME_Y_OFFSET : ABOUT_DETAILS_Y_OFFSET) : ABOUT_NAME_Y_OFFSET)) * k;
                if (screen == MAIN) y -= mainScroll.value * (ROW_H + MAIN_ROW_GAP) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                if (screen != ABOUT || i == 0) rowRect.right += ABOUT_NAME_RIGHT_EXTENSION * k;
                // Only the first About row (device name) is an actionable capsule.
                float f;
                if (screen != ABOUT || i == 0) {
                    f = drawApprovedCapsule(c, i);
                } else {
                    f = screen == ABOUT ? 0f : drawRowBackground(c, i);
                }
                rowLabel.setColor(screen == REGION && i == 1 && Prefs.automaticRegion() ? 0x66808080 : blend(normalInk(), INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                drawTrackedText(c, labels[i], rowRect.left + rowRect.width() * 0.10f + 10 * k, base, rowLabel, 0.02f);
                float right = rowRect.right - 30 * k;
                if (screen != ABOUT || i == 0) {
                    if (screen == UPDATES && i == 0 && updateProgress.busy()) {
                        boolean reduced = localFlag("reduce_motion");
                        updateProgress.drawSpinner(c, rowRect.right - 43 * k, rowRect.centerY(), k,
                                f > 0.5f ? 0xFF777777 : secondaryInk(), reduced);
                        if (running && !reduced) postInvalidateDelayed(80);
                    } else if (menuChevronVisible(i)) drawApprovedChevron(c);
                    if (screen == MOVIE_SERVICE) {
                        if (i == MovieService.index(localPrefs().getString(MovieService.KEY, "")))
                            Symbols.draw(getContext(), c, "check", rowRect.right - 43 * k, rowRect.centerY(), 24 * k,
                                    blend(normalInk(), INK, f));
                    } else {
                        rowValue.setColor(screen == REGION && i == 1 && Prefs.automaticRegion() ? 0x66808080 : valueInk(f));
                        drawTrackedText(c, screen == MEDIA || screen == REGION ? fitServiceValue(labels[i], values[i]) : values[i],
                                rowRect.right - 61 * k, base, rowValue, 0.02f);
                    }
                } else {
                    rowValue.setColor(valueInk(f));
                    drawTrackedText(c, values[i], right + 20 * k, base, rowValue, 0.02f);
                }
            }
        }

        /** Keep the service in its right-aligned column, with a visible gap after the label. */
        private String fitServiceValue(String label, String value) {
            float labelX = rowRect.left + rowRect.width() * 0.10f + 10 * k;
            float labelWidth = rowLabel.measureText(label) + rowLabel.getTextSize() * .02f
                    * Math.max(0, label.codePointCount(0, label.length()) - 1);
            float available = Math.max(0, rowRect.right - 61 * k - labelX - labelWidth - 32 * k);
            float size = rowValue.getTextSize();
            if (value.equals(serviceValueSource) && available == serviceValueWidth && size == serviceValueSize)
                return serviceValueShown;
            serviceValueSource = value; serviceValueWidth = available; serviceValueSize = size;
            String shown = value;
            int end = value.length();
            while (rowValue.measureText(shown) + size * .02f
                    * Math.max(0, shown.codePointCount(0, shown.length()) - 1) > available) {
                if (end == 0) { shown = ""; break; }
                end = value.offsetByCodePoints(end, -1);
                shown = value.substring(0, end) + "…";
            }
            return serviceValueShown = shown;
        }

        /** Stable row order with the focused row last, without extra layers or allocations. */
        private static int paintRow(int pass, int first, int end, int active) {
            if (active < first || active >= end) return first + pass;
            if (pass == end - first - 1) return active;
            int row = first + pass;
            return row >= active ? row + 1 : row;
        }

        /** Theme-colored capsules; focused row lifts into an opaque white pill. */
        private float drawRowBackground(Canvas c, int i) {
            float f = i >= 0 && i < pop.length ? Ui.clamp(pop[i].value) : 0f;
            float radius = rowRect.height() / 2f;
            capsule.setColor(lightSettings() ? 0xFFD1E3FB : 0xFF31444B);
            c.drawRoundRect(rowRect, radius, radius, capsule);
            if (f > 0.01f) {
                float scale = 1f + 0.012f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * scale / 2f,
                        cy - rowRect.height() * scale / 2f,
                        cx + rowRect.width() * scale / 2f,
                        cy + rowRect.height() * scale / 2f);
                float r = tmp.height() / 2f;
                // Downward shadow; the filter and legacy cached mask use the same blur.
                tmp.offset(0, SETTINGS_SHADOW_DROP * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, SETTINGS_SHADOW_OPACITY * f));
                rowShadows.draw(c, tmp, r, SETTINGS_SHADOW_BLUR * k, shadow);
                tmp.offset(0, -SETTINGS_SHADOW_DROP * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        /** Home launcher selection, using the same compact tvOS capsules as other screens. */
        private void drawChoice(Canvas c) {
            String[] options = {"tvOS", AppStrings.get(R.string.settings_built_in)};
            int current = freeActive() ? 0 : 1;
            for (int pass = 0; pass < options.length; pass++) {
                int i = paintRow(pass, 0, options.length, focus);
                setApprovedRowRect(i);
                float f = drawApprovedCapsule(c, i);
                rowLabel.setColor(blend(normalInk(), INK, f));
                drawTrackedText(c, options[i], rowRect.left + rowRect.width() * 0.10f + 10 * k,
                        rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel, 0.02f);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2,
                            rowRect.centerY(), 26 * k, blend(arrowInk(), INK, f));
                }
            }
        }

        /** Supporting information belongs below the large tvOS tile, never over the options. */
        // Preserve existing StaticLayout wrapping/metrics pending a separately verified migration.
        @SuppressWarnings("deprecation")
        private void drawTileDescription(Canvas c) {
            String info = screen == UPDATES ? updateDetailsText()
                    : SettingsContent.description(screen, focus, pickFor, settingsGroup, buttonEditor.size());
            if (screen == UPDATES && updateProgress.active()) {
                updateProgress.draw(c, k, lightSettings());
                return;
            }
            if (info == null) return;
            descriptionPaint.setColor(lightSettings() ? 0xB33A5761 : 0xFF888D90);
            int width = Math.round(740 * k);
            if (descriptionLayout == null || !info.equals(descriptionText) || width != descriptionWidth) {
                descriptionPaint.setTypeface(Ui.displaySemibold);
                descriptionPaint.setTextSize(30 * k);
                descriptionLayout = new StaticLayout(info, descriptionPaint, width,
                        android.text.Layout.Alignment.ALIGN_CENTER, 1.18f, 0, false);
                descriptionText = info;
                descriptionWidth = width;
            }
            c.save();
            c.translate(152 * k, 837 * k);
            descriptionLayout.draw(c);
            c.restore();
        }

        private String updateDetailsText() {
            if (updateInstalledVersion == null) updateInstalledVersion = SettingsUpdateDialog.versionName(getContext());
            long checkedAt = localPrefs().getLong("self_update_attempt", 0);
            if (updateCheckShown == null || checkedAt != updateCheckAt || updateCheckLanguage != AppLanguage.current()) {
                updateCheckAt = checkedAt;
                updateCheckLanguage = AppLanguage.current();
                updateCheckShown = formatLastUpdateCheck(checkedAt, AppLanguage.current());
            }
            return updateInstalledVersion + "\n" + updateCheckShown;
        }

        private static String formatLastUpdateCheck(long checkedAt, AppLanguage language) {
            if (checkedAt <= 0) return AppStrings.get(R.string.settings_last_check_never, language);
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                    AppStrings.get(R.string.settings_last_check_date_pattern, language),
                    language.locale);
            return String.format(language.locale,
                    AppStrings.get(R.string.settings_last_check, language),
                    format.format(new java.util.Date(checkedAt)));
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private void toggleAutomaticUpdates() {
            boolean enabled = !localPrefs().getBoolean("auto_update", false);
            setLocalFlag("auto_update", enabled);
            if (enabled) SelfUpdate.get(getContext()).maybeAutomatic(true);
        }

        boolean onKey(KeyEvent e) {
            if (updateDialog.visible()) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    int code = e.getKeyCode();
                    if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) tvosClick.move();
                    else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK) tvosClick.open();
                }
                return updateDialog.onKey(e);
            }
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (screen == ABOUT) return true;

                    if (down && e.getRepeatCount() == 0 && (focus > 0 || (scrolling() && pickScroll > 0))) tvosClick.move();
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (screen == ABOUT) return true;

                    if (down && e.getRepeatCount() == 0 && (focus < rowCount() - 1 || (scrolling() && pickScroll + rowCount() < listSize()))) tvosClick.move();
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && e.getRepeatCount() == 0 && (screen == SHELF_EDIT || screen == CATEGORY_EDIT || canAdjustMenu())) tvosClick.move();
                    if (down && screen == MENU_BUTTONS && focus < buttonEditor.size()) {
                        moveMenuButton(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && screen == CATEGORY_EDIT) {
                        moveCategory(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && canAdjustMenu()) {
                        adjustMenu(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) { tvosClick.open(); activate(); }
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            tvosClick.open();
                            backFromScreen();
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        private void backFromScreen() {
            SettingsActions.Target target = SettingsActions.back(screen, pickFor, settingsGroup, buttonEditor.size());
            if (target != null) {
                focus = target.row;
                screen = target.screen;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** Home Settings: language, temperature, theme, weather, city and categories. */
        private static final int CITY_ROW = 4;

        private int settingIndex() {
            if (settingsGroup == SYSTEM) return randomRow();
            return focus;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            PackageManager pm = getContext().getPackageManager();
            List<String[]> found = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName()) || !seen.add(pkg)) continue;
                found.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            appLabels.add(AppStrings.get(R.string.settings_not_selected));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        private void launchAirPlay() {
            final String packageName = "io.github.jqssun.airplay";
            PackageManager pm = getContext().getPackageManager();
            Intent launch = HomeApps.launchIntent(pm, packageName);
            if (launch != null) {
                try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (AppLaunch.start(getContext(), launch, "AirPlay launch")) return;
                } catch (RuntimeException e) {
                    AppLog.unexpected("AirPlay launch preparation", e);
                }
            }
            android.widget.Toast.makeText(getContext(),
                    AppStrings.get(R.string.settings_airplay_is_not_installed_or_unavailable),
                    android.widget.Toast.LENGTH_SHORT).show();
        }

        private String[] menuButtonLabels() {
            String[] labels = new String[buttonEditor.size() + 1];
            for (int i = 0; i < buttonEditor.size(); i++) labels[i] = menuButtonLabel(buttonEditor.get(i));
            labels[buttonEditor.size()] = AppStrings.get(R.string.settings_vpn);
            return labels;
        }

        private String menuButtonLabel(int id) {
            switch (id) {
                case 0: return AppStrings.get(R.string.settings_search);
                case 1: return AppStrings.get(R.string.settings_tv);
                case 2: return "VPN";
                case 3: return AppStrings.get(R.string.settings_settings);
                case 4: return AppStrings.get(R.string.settings_clock);
                default: return AppStrings.get(R.string.settings_network);
            }
        }

        private void rebuildMenuButtonRows(int selectedId) {
            focus = buttonEditor.rebuild(selectedId);
        }
        private void openMenuButtonEditor() {
            rebuildMenuButtonRows(-1);
            focus = 0;
            screen = MENU_BUTTONS;
        }
        private void toggleMenuButton() {
            focus = buttonEditor.toggle(focus);
        }
        private void moveMenuButton(int direction) {
            focus = buttonEditor.move(focus, direction);
        }
        private void drawMenuButtonEditor(Canvas c) {
            int count = buttonEditor.size() + 1;
            for (int pass = 0; pass < count; pass++) {
                int row = paintRow(pass, 0, count, focus);
                setApprovedRowRect(row);
                float f = drawApprovedCapsule(c, row);
                boolean disabled = row == buttonEditor.size() && !vpnTopEnabled();
                rowLabel.setColor(disabled ? 0x66808080 : blend(normalInk(), INK, f));
                rowValue.setColor(disabled ? 0x66808080 : valueInk(f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                boolean vpnApp = row == buttonEditor.size();
                int id = vpnApp ? -1 : buttonEditor.get(row);
                drawTrackedText(c, vpnApp ? AppStrings.get(R.string.settings_vpn) : menuButtonLabel(id),
                        rowRect.left + rowRect.width() * 0.10f + 10 * k, base, rowLabel, 0.02f);
                if (vpnApp) drawTrackedText(c, fitServiceValue(AppStrings.get(R.string.settings_vpn), valueSource.vpnApp()),
                        rowRect.right - 61 * k, base, rowValue, 0.02f);
                if (!vpnApp) drawTrackedText(c, Prefs.topButtonEnabled(id) ? "#" + Prefs.topButtonPosition(id) : AppStrings.get(R.string.settings_off),
                        rowRect.right - 61 * k, base, rowValue, 0.02f);
                if (!disabled) drawApprovedChevron(c);
            }
        }

        private void toggleDateTime() {
            String key = "show_date";
            setLocalFlag(key, !localPrefs().getBoolean(key, true));
        }

        /** Only value rows respond to left/right; actions and submenus require OK. */
        private boolean canAdjustMenu() {
            return SettingsActions.canAdjust(screen, focus, buttonEditor.size());
        }

        private void adjustMenu(int dir) {
            switch (SettingsActions.adjustment(screen, focus)) {
                case HOME: adjust(dir); return;
                case WEATHER: toggleWeather(focus == 0); return;
                case DATE: toggleDateTime(); return;
                case DREAM_SOURCE: cycleDreamSource(dir); return;
                case DREAM_CLOCK: setLocalFlag("aerial_show_clock", !localPrefs().getBoolean("aerial_show_clock", false)); return;
                case DREAM_LOCATION: setLocalFlag("aerial_show_location", !localPrefs().getBoolean("aerial_show_location", false)); return;
                case DREAM_WEATHER: setLocalFlag("screensaver_show_weather", !localPrefs().getBoolean("screensaver_show_weather", false)); return;
                case AUTO_UPDATE: toggleAutomaticUpdates(); return;
                case TRANSPARENCY: setLocalFlag("reduce_transparency", !localFlag("reduce_transparency")); return;
                case MOTION: setLocalFlag("reduce_motion", !localFlag("reduce_motion")); return;
                case REGION_MODE: toggleAutomaticRegion(); return;
                case MENU_BUTTON: moveMenuButton(dir); return;
                case GENERAL:
                    if (focus == 1) { int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme()); Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]); }
                    else if (focus == 2) Prefs.setLanguage(AppLanguage.step(Prefs.language(), dir).code);
                    else if (focus == 5) { int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature()); Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]); }
                    else if (focus == 6) { Prefs.setHour24(!Prefs.hour24()); }
                    else if (focus == 7) { setLocalFlag("navigation_clicks", !localPrefs().getBoolean("navigation_clicks", true)); }
                    HomeData.get(getContext()).settingsChanged();
                    return;
                default: return;
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            int selected = settingIndex();
            if (selected == randomRow()) { Prefs.setRandomMovie(!Prefs.randomMovie()); HomeData.get(getContext()).settingsChanged(); return; }
            switch (selected) {
                case 0: {
                    Prefs.setLanguage(AppLanguage.step(Prefs.language(), dir).code);
                    break;
                }
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case 3: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                default: return;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if (citySearch.searching()) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(AppStrings.get(R.string.settings_city_name_hint));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(AppStrings.get(R.string.settings_city))
                    .setView(field)
                    .setPositiveButton(AppStrings.get(R.string.settings_find_city), (d, which) -> citySearch.search(field.getText().toString()))
                    .setNegativeButton(AppStrings.get(R.string.settings_cancel), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                citySearch.search(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void showCityResults(List<String[]> result) {
            if (screen != HOME_SETTINGS && screen != GENERAL) return;
            cities.clear();
            cities.addAll(result);
            openPicker(PICK_CITY);
            spoken.focusMoved();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? dockEditor.size() : pickLabels.size();
        }

        private String categoryLabel(int id) {
            switch (id) {
                case 0: return AppStrings.get(R.string.settings_new);
                case 1: return AppStrings.get(R.string.settings_movies);
                case 3: return AppStrings.get(R.string.settings_shows);
                default: return AppStrings.get(R.string.settings_random_movie);
            }
        }

        private void rebuildCategoryRows(int selectedId) {
            focus = categoryEditor.rebuild(selectedId);
        }

        private void openCategoryEditor() {
            rebuildCategoryRows(-1);
            focus = 0;
            screen = CATEGORY_EDIT;
        }

        private void toggleCategory() {
            focus = categoryEditor.toggle(focus);
        }

        private void moveCategory(int direction) {
            focus = categoryEditor.move(focus, direction);
        }

        private void drawCategoryEditor(Canvas c) {
            int count = categoryEditor.size();
            for (int pass = 0; pass < count; pass++) {
                int row = paintRow(pass, 0, count, focus);
                int id = categoryEditor.get(row);
                setApprovedRowRect(row);
                float f = drawApprovedCapsule(c, row);
                rowLabel.setColor(blend(normalInk(), INK, f));
                rowValue.setColor(valueInk(f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                drawTrackedText(c, categoryLabel(id), rowRect.left + rowRect.width() * 0.10f + 10 * k,
                        base, rowLabel, 0.02f);
                drawTrackedText(c, Prefs.categoryEnabled(id) ? "#" + Prefs.categoryPosition(id) : AppStrings.get(R.string.settings_off),
                        rowRect.right - 61 * k, base, rowValue, 0.02f);
                drawApprovedChevron(c);
            }
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            dockEditor.open(HomeApps.shelfPackages(getContext()), HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            listScroll.snap(0);
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Keep the changed app focused and visible after the model reorders its candidates. */
        private void focusShelfRow(int at) {
            if (at < 0) return;
            int visible = rowCount();
            if (at < pickScroll) pickScroll = at;
            else if (at >= pickScroll + visible) pickScroll = at - visible + 1;
            focus = at - pickScroll;
        }

        private void toggleShelfApp() {
            focusShelfRow(dockEditor.toggle(pickScroll + focus));
        }

        private void moveShelfApp(int dir) {
            focusShelfRow(dockEditor.move(pickScroll + focus, dir));
        }

        private void drawShelfEditor(Canvas c) {
            // The centered screenTitle() is the only heading.
            shelfMeasurePaint.set(rowLabel);
            int first = scrollingFirstRow(), last = scrollingEndRow();
            for (int pass = 0; pass < last - first; pass++) {
                int at = paintRow(pass, first, last, pickScroll + focus);
                int i = at - pickScroll;
                String[] app = dockEditor.get(at);
                int place = dockEditor.position(app[0]);
                setScrollingRowRect(at);
                float f = drawApprovedCapsule(c, i);
                rowLabel.setColor(blend(normalInk(), INK, f));
                rowValue.setColor(valueInk(f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                drawTrackedText(c, TextUtils.ellipsize(app[1], shelfMeasurePaint, 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + rowRect.width() * 0.10f + 10 * k, base, rowLabel, 0.02f);
                drawTrackedText(c, place >= 0 ? "#" + (place + 1) : AppStrings.get(R.string.settings_off), rowRect.right - 61 * k, base, rowValue, 0.02f);
                drawApprovedChevron(c);
            }
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else {
                pickLabels.addAll(appLabels);
                if (!pickLabels.isEmpty()) pickLabels.set(0, AppStrings.get(R.string.settings_not_configured));
                pickCurrent = appPkgs.indexOf(which == PICK_VPN ? localPrefs().getString("vpn_app", "") : Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            listScroll.snap(pickScroll);
            focus = at - pickScroll;
            screen = PICKER;
        }

        // Include rows on both sides of the logical window: they must physically enter the fade mask.
        private int scrollingFirstRow() {
            return Math.max(0, (int) Math.floor(listScroll.value) - 1);
        }

        private int scrollingEndRow() {
            return Math.min(listSize(), (int) Math.ceil(listScroll.value) + PICK_VISIBLE + 1);
        }

        private void setScrollingRowRect(int absoluteRow) {
            setApprovedRowRect(absoluteRow - pickScroll);
            rowRect.offset(0, (pickScroll - listScroll.value) * (ROW_H + ABOUT_ROW_GAP) * k);
        }

        /** A long list with buffered edge rows and a continuous vertical scroll. */
        private void drawPicker(Canvas c) {
            // The centered screenTitle() is the only heading.
            int first = scrollingFirstRow(), last = scrollingEndRow();
            for (int pass = 0; pass < last - first; pass++) {
                int at = paintRow(pass, first, last, pickScroll + focus);
                int i = at - pickScroll;
                setScrollingRowRect(at);
                float f = drawApprovedCapsule(c, i);
                rowLabel.setColor(blend(normalInk(), INK, f));
                float labelX = rowRect.left + rowRect.width() * 0.10f + 10 * k;
                float labelRight = rowRect.right - 24 * k;
                if (at == pickCurrent) labelRight -= 38 * k; // reserve room for the checkmark
                String label = pickLabels.get(at);
                float available = Math.max(0f, labelRight - labelX);
                float tracking = rowLabel.getTextSize() * 0.02f;
                float textWidth = rowLabel.measureText(label)
                        + tracking * Math.max(0, label.codePointCount(0, label.length()) - 1);
                if (textWidth > available) {
                    final String dots = "…";
                    int end = label.length();
                    do {
                        end = label.offsetByCodePoints(end, -1);
                        String candidate = label.substring(0, end) + dots;
                        textWidth = rowLabel.measureText(candidate)
                                + tracking * Math.max(0, candidate.codePointCount(0, candidate.length()) - 1);
                        if (textWidth <= available || end == 0) {
                            label = candidate;
                            break;
                        }
                    } while (end > 0);
                }
                drawTrackedText(c, label, labelX, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel, 0.02f);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, blend(0xFFFFFFFF, INK, f));
                }
            }
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = keys ? keyLabels() : settingsGroup == SYSTEM ? new String[] {settingLabels()[randomRow()]} : settingLabels();
            String[] values = keys ? keyValues(appPkgs, appLabels) : settingsGroup == SYSTEM ? new String[] {valueSource.settings()[randomRow()]} : valueSource.settings();
            // The screen title is already drawn centrally; no legacy subtitle.
            for (int pass = 0; pass < labels.length; pass++) {
                int i = paintRow(pass, 0, labels.length, focus);
                setApprovedRowRect(i);
                float f = drawApprovedCapsule(c, i);
                rowLabel.setColor(blend(normalInk(), INK, f));
                rowValue.setColor(valueInk(f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                drawTrackedText(c, labels[i], rowRect.left + rowRect.width() * 0.10f + 10 * k, base, rowLabel, 0.02f);
                drawTrackedText(c, values[i], rowRect.right - 61 * k, base, rowValue, 0.02f);
                drawApprovedChevron(c);
            }

        }

        private void activate() {
            final int screenBeforeAction = screen;
            SettingsActions.Action action = SettingsActions.activation(screen, focus, pickFor,
                    (screen == HOME_SETTINGS && settingIndex() == CITY_ROW) || (screen == REGION && Prefs.automaticRegion()), buttonEditor.size(),
                    vpnTopEnabled());
            if (action == SettingsActions.Action.NAME) {
                if (focus == 0) deviceDialogs.askDeviceName();
                return;
            }
            Context c = getContext();
            if (action.targetScreen >= 0) {
                screen = action.targetScreen;
                focus = screen == MOVIE_SERVICE ? MovieService.index(localPrefs().getString(MovieService.KEY, "")) : 0;
            } else switch (action) {
                case HOME_MODE:
                    if (!busy()) {
                        HomeMode.request(c, focus == 0 ? HomeMode.FREE : HomeMode.ORIGINAL);
                        pendingAt = SystemClock.uptimeMillis();
                    }
                    invalidate();
                    return;
                case SELECT_MOVIE_SERVICE:
                    localPrefs().edit().putString(MovieService.KEY, MovieService.id(focus)).apply();
                    screen = MEDIA; focus = 0;
                    break;
                case SELECT_CITY: {
                    String[] city = cities.get(pickScroll + focus);
                    Prefs.setCity(city[1], city[2], city[3], Long.parseLong(city[4]), city[5]);
                    HomeData.get(c).settingsChanged();
                    focus = 1; screen = REGION;
                    break;
                }
                case SELECT_VPN: {
                    String pkg = appPkgs.get(pickScroll + focus);
                    localPrefs().edit().putString("vpn_app", pkg).apply();
                    if (!pkg.isEmpty()) {
                        java.util.List<String> shelf = Prefs.shelf();
                        if (shelf == null) shelf = new java.util.ArrayList<>(HomeApps.shelfPackages(c));
                        if (shelf.remove("llc.itdev.incy")) Prefs.setShelf(shelf);
                    }
                    focus = buttonEditor.size(); screen = MENU_BUTTONS;
                    break;
                }
                case SELECT_KEY:
                    Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(pickScroll + focus));
                    focus = pickFor - 1; screen = REMOTE_KEYS;
                    break;
                case CATEGORY: toggleCategory(); invalidate(); return;
                case SHELF: toggleShelfApp(); invalidate(); return;
                case KEY_PICKER: openPicker(focus + 1); break;
                case CITY_PICKER: askCity(); return;
                case HOME_ADJUST: adjust(1); invalidate(); return;
                case AIRPLAY: launchAirPlay(); return;
                case REMOTES:
                    HomeMode.ensureRemoteService(c, 0); loadApps(); screen = REMOTE_KEYS; focus = 0;
                    break;
                case SHELF_EDITOR: openShelfEditor(); return;
                case DOWNLOAD: AppDownloads.start(activity, focus); return;
                case DATE_SETTINGS: activity.startActivity(new Intent(Settings.ACTION_DATE_SETTINGS)); return;
                case MENU_ADJUST: adjustMenu(1); break;
                case CHECK_UPDATE:
                    updateDialog.check();
                    break;
                case AUTO_UPDATE: toggleAutomaticUpdates(); break;
                case MENU_BUTTON: toggleMenuButton(); break;
                case REGION_TOGGLE: toggleAutomaticRegion(); break;
                case VPN_PICKER: loadApps(); openPicker(PICK_VPN); break;
                case DATE_TOGGLE: toggleDateTime(); break;
                case WEATHER_TOGGLE: toggleWeather(focus == 0); break;
                case PREVIEW: pause(); activity.showPreview(); return;
                case CATEGORY_EDITOR: openCategoryEditor(); return;
                case BUTTON_EDITOR: openMenuButtonEditor(); return;
                case HOME_CHOICE: screen = HOME_CHOICE; focus = freeActive() ? 0 : 1; break;
                case TV_SETTINGS: activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break;
                case RESET_CONFIRM:
                    if (focus == 0) deviceDialogs.confirmClearCache();
                    else if (focus == 1) deviceDialogs.confirmResetTvos();
                    return;
                case RESTART: {
                    Intent restart = new Intent(activity, HomeActivity.class);
                    restart.putExtra("io.tvos.RESTART_HOME", true);
                    restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    activity.startActivity(restart);
                    activity.finish();
                    return;
                }
                default: break;
            }
            // Reset springs only when navigating; toggles retain the focused capsule.
            if (screen != screenBeforeAction) {
                for (Spring s : pop) s.snap(0);
            }
            invalidate();
            spoken.focusMoved();
        }
    }
}
