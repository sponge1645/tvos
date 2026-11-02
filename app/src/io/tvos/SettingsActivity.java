// TVOS_WEATHER_FADE_V1
package io.tvos;

import io.tvos.settings.SettingsActions;
import io.tvos.settings.SettingsScreen;
import io.tvos.settings.SettingsOrderEditor;
import io.tvos.settings.SettingsDockEditor;
import io.tvos.settings.SettingsBackground;
import io.tvos.settings.SettingsTextRenderer;
import io.tvos.settings.SettingsLogoRenderer;
import io.tvos.settings.SettingsRowRenderer;
import io.tvos.settings.SettingsListRenderer;
import io.tvos.settings.SettingsListRenderer.Kind;
import io.tvos.settings.SettingsListRenderer.Marker;
import io.tvos.settings.SettingsListViewport;
import io.tvos.settings.SettingsTransitions;
import io.tvos.settings.SettingsListWindow;
import io.tvos.settings.SettingsRemoteInput;

import static io.tvos.settings.SettingsScreen.*;
import static io.tvos.SettingsValues.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

/**
 * tvOS's settings (the app's icon, "All Apps", or the gear on the home screen), with a two-column layout:
 * the big symbol and title on the left, the list on the right with the focused row in white. "Default Launcher"
 * opens the tvOS / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private SettingsPreview preview;
    private final BroadcastReceiver volumeStatus = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            Prefs.reloadVolumeHold();
            if (view != null) view.invalidate();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RegionVpnRecovery.request(this);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        preview = new SettingsPreview(this, view, view::resume);
        setContentView(preview.host());
        WifiPermission.requestOnce(this);
    }

    @Override protected void onResume() {
        super.onResume();
        IntentFilter volumeFilter = new IntentFilter(RemoteVolume.STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(volumeStatus, volumeFilter, RemoteVolume.PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(volumeStatus, volumeFilter, RemoteVolume.PERMISSION, null);
        Prefs.reloadVolumeHold();
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
        unregisterReceiver(volumeStatus);
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

    static final class SettingsView extends View {
        private final SettingsListWindow listWindow = new SettingsListWindow(10);

        private final SettingsActivity activity;
        private Click tvosClick; // TVOS_AERIAL_CONTROLS_V1: same sound as HomeView
        private final Shadows rowShadows = new Shadows();
        private final SettingsRowRenderer rowRenderer = new SettingsRowRenderer(rowShadows::draw);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final SettingsCitySearch citySearch;
        private final SettingsAppLoader appLoader;
        private final SettingsValues valueSource;
        private final SettingsPreferenceEditor preferenceEditor;
        private final SettingsDeviceDialogs deviceDialogs;
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final SettingsDockEditor dockEditor = SettingsEditors.dock();
        private int pickFor, pickCurrent;   // pickFor: the remote button (index + 1)
        private final Spring listScroll = new Spring(0.30f, 1f, 0);
        private final Spring mainScroll = new Spring(0.30f, 1f, 0);
        private final List<String[]> cities = new ArrayList<>();
        private final RectF rowRect = new RectF(), focusBox = new RectF();
        private final SettingsTextRenderer textRenderer = new SettingsTextRenderer();
        private final SettingsLogoRenderer logoRenderer = new SettingsLogoRenderer();
        private final SettingsListRenderer.Row rowData = new SettingsListRenderer.Row();
        private final SettingsListRenderer listRenderer = new SettingsListRenderer(rowRenderer, textRenderer,
                new SettingsListRenderer.Check() {
                    @Override public float width(float size) { return Symbols.width(getContext(), "check", size); }
                    @Override public void draw(Canvas c, float x, float y, float size, int ink) {
                        Symbols.draw(getContext(), c, "check", x, y, size, ink);
                    }
                }, this::drawUpdateSpinner);
        private final SettingsOrderEditor categoryEditor = SettingsEditors.categories();
        private final SettingsOrderEditor buttonEditor = SettingsEditors.buttons();
        // TVOS_RAM_GPU_PHASE1: smooth static gradient at half resolution.
        // Saves ~75% of background bitmap RAM and texture upload bandwidth.
        private final SettingsBackground background = new SettingsBackground();
        private final SettingsUpdateDialog updateDialog;
        private final SettingsUpdateProgress updateProgress = new SettingsUpdateProgress();
        private final SettingsModeMonitor modeMonitor;
        private int screen = MAIN, focus, settingsGroup = GENERAL;
        private final SettingsTransitions transitions = new SettingsTransitions();
        private final java.util.function.IntUnaryOperator transitionParent = this::parentScreen;
        private final SettingsTransitions.ScreenPainter transitionPainter = this::drawTransitionScreen;
        private final SettingsListViewport listViewport = new SettingsListViewport();
        private final SettingsListViewport.Content listContent = this::drawMenuTransition;

        private boolean running;
        private final SettingsRemoteInput remoteInput = new SettingsRemoteInput();
        private final SettingsRemoteInput.Actions remoteActions = new SettingsRemoteInput.Actions() {
            @Override public void vertical(int direction, boolean down, boolean first) { moveFocus(direction, down, first); }
            @Override public void horizontal(int direction, boolean down, boolean first) { adjustFocusedRow(direction, down, first); }
            @Override public void confirm() {
                if (spoken.keyClick()) { tvosClick.open(); activate(); }
            }
            @Override public void back(boolean activate, boolean root) {
                if (root) { if (activate) tvosClick.back(); return; }
                if (activate) { tvosClick.back(); backFromScreen(); }
                invalidate();
                spoken.focusMoved();
            }
        };
        private boolean lightAppearance;
        private final Spoken spoken;
        private long lastNanos;
        private int w, h;
        private float k = 1;
        private Paint rowLabel, rowValue;

        private Paint pageTitle;

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            modeMonitor = new SettingsModeMonitor(a, handler, () -> running, () -> screen == REGION,
                    this::announceForAccessibility, this::invalidate);
            preferenceEditor = new SettingsPreferenceEditor(localPrefs(),
                    () -> HomeData.get(getContext()).settingsChanged(), this::invalidate);
            deviceDialogs = new SettingsDeviceDialogs(a, this::invalidate);
            appLoader = new SettingsAppLoader(a.getApplicationContext(), handler, () -> running);
            citySearch = new SettingsCitySearch(handler, () -> running, () -> screen, this::showCityResults, this::invalidate);
            updateDialog = new SettingsUpdateDialog(a, this, updateProgress, this::drawSettingsContent);
            valueSource = new SettingsValues(a, buttonEditor, this::freeActive, this::weatherVisible, citySearch::status);
            tvosClick = new Click(a);
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            updateAppearance(System.currentTimeMillis());
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
                case MEDIA: case HOME_SCREEN: case MOVIE_SERVICE: case GENERAL: case WIDGETS: case SYSTEM: case RESET: case ABOUT: case SCREENSAVER: case REGION: case ACCESSIBILITY: case TOP_PANEL: {
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
                    int at = listWindow.absoluteRow(row);
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
                    int at = listWindow.absoluteRow(row);
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
            SettingsListRenderer.accessibilityBounds(focusBox, row, rowLayout(), mainScroll.value,
                    scrolling(), listWindow.offset(), listScroll.value, k);
            return focusBox;
        }

        private SettingsListRenderer.Layout rowLayout() {
            return screen == MAIN ? SettingsListRenderer.Layout.ROOT
                    : screen == ABOUT ? SettingsListRenderer.Layout.ABOUT : SettingsListRenderer.Layout.STANDARD;
        }

        void resume() {
            if (running) return;
            running = true;
            valueSource.clearAppCache();
            HomeData.get(getContext()).localizeCity(Prefs.language());
            updateDialog.resume();
            lastNanos = 0;
            modeMonitor.start();
            invalidate();
            spoken.readAgain();
        }

        void pause() {
            running = false;
            remoteInput.reset();
            appLoader.cancel();
            updateDialog.pause();
            citySearch.cancel();
            modeMonitor.stop();
            background.clear(); // Free cached bitmap when settings are hidden.
            rowShadows.clear();
            textRenderer.clear();
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

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            rowShadows.clear();
            textRenderer.clear();
            listViewport.resize(w, h, k);
            background.clear();
            updateDialog.resize(k);
            rowLabel = Ui.text(Ui.displaySemibold, 36 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.displayRegular, 36 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            pageTitle = Ui.text(Ui.displayBold, 56 * k, 0x99FFFFFF);
            pageTitle.setTextAlign(Paint.Align.CENTER);
        }

        private boolean busy() { return modeMonitor.busy(); }

        private boolean freeActive() { return modeMonitor.freeActive(); }

        private int rowCount() {
            int fixed = SettingsScreen.fixedRows(screen);
            if (fixed >= 0) return fixed;
            switch (screen) {
                case MOVIE_SERVICE: return MovieService.count();
                case CATEGORY_EDIT: return categoryEditor.size();
                case MENU_BUTTONS: return buttonEditor.size() + 1;
                case HOME_SETTINGS: return settingsGroup == SYSTEM ? 1 : settingLabels().length;
                case PICKER: return listWindow.visibleRows(pickLabels.size());
                case SHELF_EDIT: return listWindow.visibleRows(dockEditor.size());
                case REMOTE_KEYS: return Prefs.KEYS.length + 1;
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
                listScroll.target = listWindow.offset();
                if (localFlag("reduce_motion") || Math.abs(listScroll.target - listScroll.value) > listWindow.capacity())
                    listScroll.snap(listWindow.offset());
                else if (listScroll.step(dt)) postInvalidateOnAnimation();
            }
            stepMainScroll(dt);
            stepRowFocus(dt, up);
            drawSettingsContent(c);
            if (updateDialog.visible()) updateDialog.draw(c, w, h, lightSettings(), localFlag("reduce_transparency"), localFlag("reduce_motion"));
            if (needsFocusFrame(up)) postInvalidateOnAnimation();
        }

        private boolean needsFocusFrame(long now) {
            if (!running || updateDialog.visible()) return false;
            if (!transitions.focusReady(now)) return true;
            for (Spring spring : pop) if (!spring.settled()) return true;
            return false;
        }

        private void drawSettingsContent(Canvas c) {
            background.draw(c, w, h, lightSettings());
            pageTitle.setColor(lightSettings() ? 0xFF738190 : 0xFF5A676D);
            drawPageTitle(c, SystemClock.uptimeMillis());
            logoRenderer.draw(c, k, lightSettings(), Ui.displaySemibold);
            listViewport.draw(c, listContent);
            drawTileDescription(c);
        }

        /** Reset inherited selection before drawing either page of a menu transition. */
        private void syncMenuTransition(long now) {
            if (transitions.sync(screen, now, localFlag("reduce_motion"), updateDialog.visible(), transitionParent))
                for (Spring spring : pop) spring.snap(0);
        }

        private void stepMainScroll(float dt) {
            if (screen != MAIN) return;
            mainScroll.target = Math.max(0, focus - (listWindow.capacity() - 1));
            if (localFlag("reduce_motion")) mainScroll.snap(mainScroll.target);
            else if (mainScroll.step(dt)) postInvalidateOnAnimation();
        }

        private void stepRowFocus(float dt, long now) {
            boolean ready = transitions.focusReady(now);
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
            if (transitions.drawTitle(c, screenTitle(), now, localFlag("reduce_motion"),
                    w / 2f, 111 * k, pageTitle, textRenderer)) postInvalidateDelayed(16);
        }

        /** Resolve once per frame so every surface uses the launcher's same sunrise/sunset policy. */
        private void updateAppearance(long now) {
            lightAppearance = !HomeData.get(getContext()).isNight(now);
        }

        private boolean lightSettings() { return lightAppearance; }

        private String screenTitle() {
            return SettingsContent.screenTitle(screen, settingsGroup, pickFor);
        }

        private String[] menuLabels() {
            return screen == MENU_BUTTONS ? menuButtonLabels() : SettingsContent.menuLabels(screen);
        }
        private android.content.SharedPreferences localPrefs() { return getContext().getSharedPreferences("settings", Context.MODE_PRIVATE); }
        private boolean localFlag(String key) { return preferenceEditor.flag(key); }
        private void setLocalFlag(String key, boolean value) { preferenceEditor.setFlag(key, value); }
        private void cycleDreamSource(int direction) { preferenceEditor.cycleDreamSource(direction); }
        // Two independent switches. Old four-state preference is read as a migration fallback.
        private boolean weatherVisible(boolean top) { return preferenceEditor.weatherVisible(top); }
        private void toggleWeather(boolean top) { preferenceEditor.toggleWeather(top); }
        private boolean vpnTopEnabled() { return Prefs.topButtonEnabled(2); }
        private void toggleAutomaticRegion() { citySearch.cancel(); Prefs.setAutomaticRegion(!Prefs.automaticRegion()); AutoRegion.enter(getContext()); invalidate(); }
        private void setApprovedRowRect(int i) { SettingsListRenderer.approvedBounds(rowRect, i, k); }

        /** Parent hierarchy also determines animation direction. No per-screen animation code. */
        private int parentScreen(int child) {
            return SettingsScreen.parent(child, settingsGroup, pickFor);
        }

        private void drawScreenContent(Canvas c) {
            switch (screen) {
                case MAIN: case MEDIA: case HOME_SCREEN: case MOVIE_SERVICE: case GENERAL: case WIDGETS: case WEATHER: case DATE_TIME:
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
            if (transitions.drawList(c, screen, SystemClock.uptimeMillis(), k, w, h,
                    transitionParent, transitionPainter)) postInvalidateDelayed(16);
        }

        private void drawTransitionScreen(Canvas c, int page) {
            int current = screen;
            screen = page;
            try { drawScreenContent(c); }
            finally { screen = current; }
        }

        private boolean menuChevronVisible(int row) {
            return SettingsScreen.hasChevron(screen, row, screen == REGION && Prefs.automaticRegion());
        }

        private SettingsListRenderer.Row prepareRow(int index, String label, String value) {
            return rowData.set(label, value, index >= 0 && index < pop.length ? pop[index].value : 0f);
        }

        private void drawRow(Canvas c) {
            listRenderer.draw(c, rowRect, k, rowRect.centerY() + Ui.halfCap(rowLabel), lightSettings(), rowLabel, rowValue, rowData);
        }

        private void drawUpdateSpinner(Canvas c, float x, float y, float scale, int ink) {
            boolean reduced = localFlag("reduce_motion");
            updateProgress.drawSpinner(c, x, y, scale, ink, reduced);
            if (running && !reduced) postInvalidateDelayed(80);
        }

        private void drawMenu(Canvas c) {
            String[] labels = menuLabels(), values = valueSource.menu(screen, rowCount());
            for (int pass = 0; pass < labels.length; pass++) {
                int i = SettingsListRenderer.paintRow(pass, 0, labels.length, focus);
                SettingsListRenderer.menuBounds(rowRect, i, rowLayout(), mainScroll.value, k);
                SettingsListRenderer.Row row = prepareRow(i, labels[i], values[i]);
                row.kind = screen == ABOUT && i != 0 ? Kind.DETAIL : Kind.NORMAL;
                row.disabled = screen == REGION && i == 1 && Prefs.automaticRegion();
                boolean chevron = menuChevronVisible(i);
                row.marker = screen == UPDATES && i == 0 && updateProgress.busy() ? Marker.SPINNER
                        : chevron ? Marker.CHEVRON : Marker.NONE;
                row.valueInset = chevron ? 61 : 13;
                row.fitValue = screen == MEDIA || screen == REGION;
                row.showValue = screen != MOVIE_SERVICE;
                if (screen == MOVIE_SERVICE && i == MovieService.index(localPrefs().getString(MovieService.KEY, ""))) row.marker = Marker.SERVICE_CHECK;
                drawRow(c);
            }
        }

        /** Home launcher selection, using the same compact tvOS capsules as other screens. */
        private void drawChoice(Canvas c) {
            String[] options = {"tvOS", AppStrings.get(R.string.settings_built_in)};
            int current = freeActive() ? 0 : 1;
            for (int pass = 0; pass < options.length; pass++) {
                int i = SettingsListRenderer.paintRow(pass, 0, options.length, focus);
                setApprovedRowRect(i);
                SettingsListRenderer.Row row = prepareRow(i, options[i], "");
                row.showValue = false;
                if (i == current) row.marker = Marker.CHOICE_CHECK;
                drawRow(c);
            }
        }

        /** Supporting information belongs below the large tvOS tile, never over the options. */
        private void drawTileDescription(Canvas c) {
            String info = screen == UPDATES ? updateDetailsText()
                    : SettingsContent.description(screen, focus, pickFor, settingsGroup, buttonEditor.size());
            if (screen == UPDATES && updateProgress.active()) {
                updateProgress.draw(c, k, lightSettings());
                return;
            }
            if (info == null) return;
            textRenderer.drawDescription(c, info, k, lightSettings(), Ui.displaySemibold);
        }

        private String updateDetailsText() { return valueSource.updateDetailsText(); }

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
                    else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) tvosClick.open();
                    else if (code == KeyEvent.KEYCODE_BACK) tvosClick.back();
                }
                return updateDialog.onKey(e);
            }
            return remoteInput.onKey(e, screen == MAIN, remoteActions);
        }

        private void moveFocus(int direction, boolean down, boolean first) {
            if (screen == ABOUT) return;
            if (down) {
                boolean scrolling = scrolling();
                int rows = rowCount(), size = scrolling ? listSize() : rows;
                if (first && listWindow.canMove(direction, focus, rows, size, scrolling)) tvosClick.move();
                focus = listWindow.move(direction, focus, rows, size, scrolling);
            }
            invalidate();
            spoken.focusMoved();
        }

        private void adjustFocusedRow(int direction, boolean down, boolean first) {
            if (!down) return;
            if (first && (screen == SHELF_EDIT || screen == CATEGORY_EDIT || canAdjustMenu())) tvosClick.move();
            if (screen == MENU_BUTTONS && focus < buttonEditor.size()) moveMenuButton(direction);
            else if (screen == CATEGORY_EDIT) moveCategory(direction);
            else if (screen == SHELF_EDIT) moveShelfApp(direction);
            else if (canAdjustMenu()) adjustMenu(direction);
            else return;
            invalidate();
        }

        private void backFromScreen() {
            appLoader.cancel();
            SettingsActions.Target target = SettingsActions.back(screen, pickFor, settingsGroup, buttonEditor.size());
            if (target != null) {
                focus = target.row;
                screen = target.screen;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        /** Home Settings: language, temperature, theme, weather, city and categories. */
        private static final int CITY_ROW = 4;

        private int settingIndex() {
            if (settingsGroup == SYSTEM) return randomRow();
            return focus;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps(Runnable ready) {
            final int origin = screen;
            appLoader.load(found -> {
                if (screen != origin) return;
                appPkgs.clear();
                appLabels.clear();
                appPkgs.add("");
                appLabels.add(AppStrings.get(R.string.settings_not_selected));
                for (String[] app : found) {
                    appPkgs.add(app[0]);
                    appLabels.add(app[1]);
                }
                ready.run();
                invalidate();
                spoken.focusMoved();
            });
        }

        private void openTvSettings() {
            if (!AppLaunch.start(activity, new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "TV settings"))
                android.widget.Toast.makeText(activity, AppStrings.get(R.string.settings_could_not_open_tv_settings), android.widget.Toast.LENGTH_SHORT).show();
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
                int i = SettingsListRenderer.paintRow(pass, 0, count, focus);
                boolean vpn = i == buttonEditor.size();
                int id = vpn ? -1 : buttonEditor.get(i);
                setApprovedRowRect(i);
                SettingsListRenderer.Row row = prepareRow(i, vpn ? AppStrings.get(R.string.settings_vpn) : menuButtonLabel(id),
                        vpn ? valueSource.vpnApp() : Prefs.topButtonEnabled(id) ? "#" + Prefs.topButtonPosition(id) : AppStrings.get(R.string.settings_off));
                row.disabled = vpn && !vpnTopEnabled();
                row.fitValue = vpn;
                row.valueInset = vpn ? 61 : 13;
                if (vpn && !row.disabled) row.marker = Marker.CHEVRON;
                row.markerAfterValue = true;
                drawRow(c);
            }
        }

        private void toggleDateTime() { preferenceEditor.toggleDateTime(); }

        /** Only value rows respond to left/right; actions and submenus require OK. */
        private boolean canAdjustMenu() {
            return SettingsActions.canAdjust(screen, focus, buttonEditor.size());
        }

        private void toggleVolumeHold() {
            Context c = getContext();
            if (Prefs.setVolumeHold(!Prefs.volumeHold())) {
                c.sendBroadcast(new Intent(RemoteVolume.CHANGE).setPackage(c.getPackageName()));
            }
            invalidate();
        }

        private void adjustMenu(int dir) {
            switch (SettingsActions.adjustment(screen, focus)) {
                case HOME: adjust(dir); return;
                case VOLUME_HOLD: toggleVolumeHold(); return;
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
                case GENERAL: preferenceEditor.general(focus, dir); return;
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
            preferenceEditor.legacy(settingIndex(), randomRow(), dir);
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
            if (screen != HOME_SETTINGS && screen != GENERAL && screen != REGION) return;
            if (screen == REGION && Prefs.automaticRegion()) return;
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
                case 2: return AppStrings.get(R.string.home_tab_watching);
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
                int i = SettingsListRenderer.paintRow(pass, 0, count, focus);
                int id = categoryEditor.get(i);
                setApprovedRowRect(i);
                prepareRow(i, categoryLabel(id), Prefs.categoryEnabled(id) ? "#" + Prefs.categoryPosition(id) : AppStrings.get(R.string.settings_off));
                drawRow(c);
            }
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            dockEditor.open(HomeApps.shelfPackages(getContext()), HomeApps.shelfCandidates(getContext()));
            listWindow.reset();
            listScroll.snap(0);
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Keep the changed app focused and visible after the model reorders its candidates. */
        private void focusShelfRow(int at) {
            focus = listWindow.keepVisible(at, rowCount(), focus);
        }

        private void toggleShelfApp() {
            focusShelfRow(dockEditor.toggle(listWindow.absoluteRow(focus)));
        }

        private void moveShelfApp(int dir) {
            focusShelfRow(dockEditor.move(listWindow.absoluteRow(focus), dir));
        }

        private void drawShelfEditor(Canvas c) {
            listRenderer.beginShelf(rowLabel);
            int first = listWindow.firstDrawn(listScroll.value), last = listWindow.endDrawn(listScroll.value, listSize());
            for (int pass = 0; pass < last - first; pass++) {
                int at = SettingsListRenderer.paintRow(pass, first, last, listWindow.absoluteRow(focus));
                String[] app = dockEditor.get(at);
                int place = dockEditor.position(app[0]);
                setScrollingRowRect(at);
                SettingsListRenderer.Row row = prepareRow(at - listWindow.offset(), app[1], place >= 0 ? "#" + (place + 1) : AppStrings.get(R.string.settings_off));
                row.kind = Kind.SHELF;
                drawRow(c);
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
            focus = listWindow.openPicker(pickCurrent, pickLabels.size());
            listScroll.snap(listWindow.offset());
            screen = PICKER;
        }

        private void setScrollingRowRect(int at) {
            SettingsListRenderer.scrollingBounds(rowRect, at, listWindow.offset(), listScroll.value, k);
        }

        /** A long list with buffered edge rows and a continuous vertical scroll. */
        private void drawPicker(Canvas c) {
            int first = listWindow.firstDrawn(listScroll.value), last = listWindow.endDrawn(listScroll.value, listSize());
            for (int pass = 0; pass < last - first; pass++) {
                int at = SettingsListRenderer.paintRow(pass, first, last, listWindow.absoluteRow(focus));
                setScrollingRowRect(at);
                SettingsListRenderer.Row row = prepareRow(at - listWindow.offset(), pickLabels.get(at), "");
                row.kind = Kind.PICKER;
                row.showValue = false;
                if (at == pickCurrent) row.marker = Marker.PICKER_CHECK;
                drawRow(c);
            }
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = keys ? keyLabels() : settingsGroup == SYSTEM ? new String[] {settingLabels()[randomRow()]} : settingLabels();
            String[] values = keys ? keyValues(appPkgs, appLabels) : settingsGroup == SYSTEM ? new String[] {valueSource.settings()[randomRow()]} : valueSource.settings();
            for (int pass = 0; pass < labels.length; pass++) {
                int i = SettingsListRenderer.paintRow(pass, 0, labels.length, focus);
                setApprovedRowRect(i);
                SettingsListRenderer.Row row = prepareRow(i, labels[i], values[i]);
                boolean chevron = keys ? i < REMOTE_VOLUME_ROW : (settingsGroup != SYSTEM && i == CITY_ROW);
                row.marker = chevron ? Marker.CHEVRON : Marker.NONE;
                row.valueInset = chevron ? 61 : 13;
                row.markerAfterValue = true;
                drawRow(c);
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
                focus = screen == HOME_SCREEN ? (Prefs.homeContinue() ? 1 : 0) : screen == MOVIE_SERVICE ? MovieService.index(localPrefs().getString(MovieService.KEY, "")) : 0;
            } else switch (action) {
                case HOME_MODE:
                    if (!busy()) {
                        HomeMode.request(c, focus == 0 ? HomeMode.FREE : HomeMode.ORIGINAL);
                        modeMonitor.markPending();
                    }
                    invalidate();
                    return;
                case SELECT_HOME_SCREEN:
                    Prefs.setHomeContinue(focus == 1);
                    screen = GENERAL; focus = 7;
                    break;
                case SELECT_MOVIE_SERVICE:
                    localPrefs().edit().putString(MovieService.KEY, MovieService.id(focus)).apply();
                    screen = MEDIA; focus = 0;
                    break;
                case SELECT_CITY: {
                    String[] city = cities.get(listWindow.absoluteRow(focus));
                    Prefs.setCity(city[1], city[2], city[3], Long.parseLong(city[4]), city[5]);
                    HomeData.get(c).settingsChanged();
                    focus = 1; screen = REGION;
                    break;
                }
                case SELECT_VPN: {
                    String pkg = appPkgs.get(listWindow.absoluteRow(focus));
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
                    Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(listWindow.absoluteRow(focus)));
                    focus = pickFor - 1; screen = REMOTE_KEYS;
                    break;
                case CATEGORY: toggleCategory(); invalidate(); return;
                case SHELF: toggleShelfApp(); invalidate(); return;
                case VOLUME_HOLD: toggleVolumeHold(); invalidate(); return;
                case KEY_PICKER: openPicker(focus + 1); break;
                case CITY_PICKER: askCity(); return;
                case HOME_ADJUST: adjust(1); invalidate(); return;
                case AIRPLAY: launchAirPlay(); return;
                case REMOTES:
                    HomeMode.ensureRemoteService(c, 0);
                    loadApps(() -> { screen = REMOTE_KEYS; focus = 0; });
                    break;
                case SHELF_EDITOR: openShelfEditor(); return;
                case DOWNLOAD: AppDownloads.start(activity, focus); return;
                case MENU_ADJUST: adjustMenu(1); break;
                case CHECK_UPDATE:
                    updateDialog.check();
                    break;
                case AUTO_UPDATE: toggleAutomaticUpdates(); break;
                case MENU_BUTTON: toggleMenuButton(); break;
                case REGION_TOGGLE: toggleAutomaticRegion(); break;
                case VPN_PICKER: loadApps(() -> openPicker(PICK_VPN)); break;
                case DATE_TOGGLE: toggleDateTime(); break;
                case WEATHER_TOGGLE: toggleWeather(focus == 0); break;
                case PREVIEW: pause(); activity.showPreview(); return;
                case CATEGORY_EDITOR: openCategoryEditor(); return;
                case BUTTON_EDITOR: openMenuButtonEditor(); return;
                case HOME_CHOICE: screen = HOME_CHOICE; focus = freeActive() ? 0 : 1; break;
                case TV_SETTINGS: openTvSettings(); break;
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
