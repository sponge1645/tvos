// TVOS_WEATHER_FADE_V1
package io.tvos;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Canvas;
import android.graphics.Bitmap;
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
 * tvOS's settings (the app's icon, "All Apps", or the gear on the home screen), in the style of Apple TV's
 * settings: the big symbol and title on the left, the list on the right with the focused row in white. "Default Launcher"
 * opens the tvOS / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private android.widget.FrameLayout previewHost;
    private View previewCover;
    private boolean previewTransition;
    private VideoDream preview;   // "Screen Saver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        previewHost = new android.widget.FrameLayout(this);
        previewHost.setBackgroundColor(android.graphics.Color.BLACK);
        previewHost.addView(view, new android.widget.FrameLayout.LayoutParams(-1, -1));
        previewCover = new View(this);
        previewCover.setBackgroundColor(android.graphics.Color.BLACK);
        previewCover.setAlpha(0f);
        previewCover.setVisibility(View.GONE);
        previewHost.addView(previewCover, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(previewHost);
        WifiPermission.requestOnce(this);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.tvos/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
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
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (previewTransition) return true;
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) preview.fadeExit(this::finishPreview);
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        if (preview != null || previewTransition) return;
        previewTransition = true;
        previewCover.animate().cancel();
        previewCover.setVisibility(View.VISIBLE);
        previewCover.setAlpha(0f);
        // Keep settings on screen until they have faded completely to black.
        previewCover.animate().alpha(1f).setDuration(VideoDream.TRANSITION_MS)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    preview = new VideoDream(this);
                    previewHost.addView(preview, 1, new android.widget.FrameLayout.LayoutParams(-1, -1));
                    preview.start();
                    // VideoDream starts black and reveals only ready content, with the same fade.
                    previewCover.setVisibility(View.GONE);
                    previewTransition = false;
                }).start();
    }

    private void finishPreview() {
        if (preview == null || previewTransition) return;
        previewTransition = true;
        // Replace the black dream with settings underneath another fully opaque black cover.
        previewCover.animate().cancel();
        previewCover.setAlpha(1f);
        previewCover.setVisibility(View.VISIBLE);
        preview.stop();
        previewHost.removeView(preview);
        preview = null;
        view.setVisibility(View.VISIBLE);
        view.resume();
        previewCover.animate().alpha(0f).setDuration(VideoDream.TRANSITION_MS)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    previewCover.setVisibility(View.GONE);
                    previewTransition = false;
                }).start();
    }

    /** Forced Activity pause cancels either leg; no delayed callback may reopen a preview. */
    private void closePreview() {
        if (preview == null && !previewTransition) return;
        previewCover.animate().cancel();
        previewTransition = false;
        if (preview != null) {
            preview.stop();
            previewHost.removeView(preview);
            preview = null;
        }
        previewCover.setVisibility(View.GONE);
        previewCover.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, GENERAL = 6, WIDGETS = 7, SYSTEM = 8, ABOUT = 9, SCREENSAVER = 10, WEATHER = 11, DATE_TIME = 12, MENU_BUTTONS = 13, UPDATES = 14, TOP_PANEL = 15, ACCESSIBILITY = 16, RESET = 17, VPN_SETTINGS = 18, APPS_DOWNLOADS = 19;
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 6;
        private static final int PICK_VISIBLE = 10;
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 62, ROW_GAP = 12, CHOICE_H = 62;
        private static final int AMBER = 0xFFFFC266, INK = 0xFF1C1C1E;

        private final SettingsActivity activity;
        private Click tvosClick; // TVOS_AERIAL_CONTROLS_V1: same sound as HomeView
        private final Scene scene = new Scene();
        private final Shadows rowShadows = new Shadows();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private static final java.util.concurrent.ThreadPoolExecutor CITY_IO = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>());
        private UiRequest cityRequest;
        private int citySerial;
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor: the apps it can hold (selected ones first, in order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private final Spring listScroll = new Spring(0.30f, 1f, 0);
        private static final int PICK_CITY = 0, PICK_VPN = -1;
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private final RectF logoBounds = new RectF();
        private final Paint.FontMetrics logoMetrics = new Paint.FontMetrics();
        private final Paint trackedGlyphPaint = new Paint();
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrowPath = new Path();
        private final TextPaint shelfMeasurePaint = new TextPaint();
        private final TextPaint descriptionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private StaticLayout descriptionLayout;
        private String descriptionText;
        private int descriptionWidth;
        private final Matrix capsuleMatrix = new Matrix();
        private final Shader lightCapsuleGradient = new LinearGradient(0, 0, 1, 0,
                new int[]{0x96ECFCFF, 0xA8DFF6FA, 0x92E8F8FD},
                new float[]{0f, 0.54f, 1f}, Shader.TileMode.CLAMP);
        private final Shader darkCapsuleGradient = new LinearGradient(0, 0, 1, 0,
                new int[]{0xB3475A63, 0xB13B484E, 0xB43B4145},
                new float[]{0f, 0.54f, 1f}, Shader.TileMode.CLAMP);
        // TVOS_RAM_GPU_PHASE1: smooth static gradient at half resolution.
        // Saves ~75% of background bitmap RAM and texture upload bandwidth.
        private Bitmap cachedBackground;
        private boolean cachedBackgroundLight;
        private final Paint backgroundBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private void releaseBackground() {
            if (cachedBackground != null) {
                cachedBackground.recycle();
                cachedBackground = null;
            }
        }
        private final Paint popupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint popupHeading, popupBody, popupButtonText;
        private final RectF popupPanel = new RectF(), popupButton = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus, settingsGroup = GENERAL, returnFocus = 0;
        // Shared, short-lived Canvas transition for all settings submenus.
        private static final long MENU_TRANSITION_MS = 280;
        private int drawnScreen = -1, transitionFrom = -1;
        private long transitionStarted, focusReadyAt;
        private String pageTitleShown, pageTitlePrevious;
        private long pageTitleStarted;
        private String updateInstalledVersion, updateCheckShown;
        private long updateCheckAt = Long.MIN_VALUE;
        private boolean updateCheckRussian;
        private final Paint listEdgeMask = new Paint();
        {
            listEdgeMask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        private boolean running, okDown, backDown, wasBusy;
        private boolean lightAppearance;
        private final Runnable clearCityStatus = () -> { cityStatus = ""; if (running) invalidate(); };
        private long updatePopupStarted = 0;
        private boolean updatePopupClosing = false;
        private int updatePopup = 0, updatePopupFocus = 0; // 0 hidden, 1 current, 2 available, 3 error, 4 checking, 5 downloading, 6 ready
        private boolean updateRequested, installWhenReady;
        private final SelfUpdate.Listener updateListener = this::onSelfUpdate;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt, lastSky;
        private int w, h;
        private float k = 1;
        private Paint title, subtitle, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;

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

        private Paint pageTitle, logoInk, logoTile, atmosphere = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG), capsule = new Paint(Paint.ANTI_ALIAS_FLAG);
        private TextPaint notice;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
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
                case GENERAL: case WIDGETS: case SYSTEM: case RESET: case ABOUT: case SCREENSAVER: case VPN_SETTINGS: case ACCESSIBILITY: case MENU_BUTTONS: case TOP_PANEL: {
                    String[] names = menuLabels(), values = menuValues();
                    return row < names.length ? names[row] + (values[row].isEmpty() ? "" : ", " + values[row]) : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "tvOS, " + Ui.tr("sem anúncios, com a tela de descanso nova", "no ads, with the new screensaver")
                            : "Built-in, " + Ui.tr("o Início de fábrica da TV", "the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". Em uso", ". In use") : "") + ", " + (row + 1) + Ui.tr(" de 2", " of 2");
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = keys ? keyLabels() : settingsGroup == SYSTEM ? new String[] {settingLabels()[detectivesRow()], settingLabels()[randomRow()]} : settingsGroup == WIDGETS ? widgetLabels() : settingLabels();
                    String[] values = keys ? keyValues() : settingsGroup == SYSTEM ? new String[] {settingValues()[detectivesRow()], settingValues()[randomRow()]} : settingsGroup == WIDGETS ? widgetValues() : settingValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", escolhido", ", selected") : "")
                            + ", " + (at + 1) + Ui.tr(" de ", " of ") + pickLabels.size();
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    return app[1] + ", " + (place >= 0 ? Ui.tr("na prateleira, posição ", "on the shelf, place ") + (place + 1)
                            : Ui.tr("fora da prateleira", "not on the shelf"));
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN || screen == GENERAL || screen == WIDGETS || screen == WEATHER || screen == DATE_TIME || screen == MENU_BUTTONS || screen == SYSTEM || screen == RESET || screen == TOP_PANEL || screen == ACCESSIBILITY || screen == VPN_SETTINGS || screen == ABOUT || screen == SCREENSAVER) {
                top = LIST_TOP + row * (ROW_H + ABOUT_ROW_GAP)
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
            focusBox.set(LIST_X * k, top * k, (LIST_R + ABOUT_NAME_RIGHT_EXTENSION) * k, (top + height) * k);
            if (scrolling()) focusBox.offset(0, (pickScroll - listScroll.value) * (ROW_H + ABOUT_ROW_GAP) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            SelfUpdate.get(getContext()).addListener(updateListener);
            onSelfUpdate();
            SelfUpdate.get(getContext()).maybeAutomatic(false);
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            SelfUpdate.get(getContext()).removeListener(updateListener);
            citySerial++;
            if (cityRequest != null) cityRequest.cancel();
            cityRequest = null;
            handler.removeCallbacks(clearCityStatus);
            cityStatus = "";
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
            releaseBackground(); // Free cached bitmap when settings are hidden.
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
            if (moving || (updatePopup == 0 && SystemClock.uptimeMillis() - lastSky >= 100)) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            rowShadows.clear();
            descriptionLayout = null;
            descriptionText = null;
            arrowPaint.setColor(ABOUT_SECONDARY_COLOR);
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
            releaseBackground();
            popupHeading = Ui.text(Ui.displaySemibold, 47*k, 0xFFFFFFFF);
            popupHeading.setTextAlign(Paint.Align.CENTER);
            popupBody = Ui.text(Ui.regular, 30*k, 0xFFE5E9F3);
            popupBody.setTextAlign(Paint.Align.CENTER);
            popupButtonText = Ui.text(Ui.displaySemibold, 30*k, 0xFFFFFFFF);
            popupButtonText.setTextAlign(Paint.Align.CENTER);
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 28 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 24 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.displaySemibold, 36 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.displayRegular, 36 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 27 * k, 0x8CFFFFFF);
            header = Ui.text(Ui.semibold, 21 * k, 0x73FFFFFF);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 27 * k, 0xD9FFFFFF);
            cellValue = Ui.numbers(Ui.regular, 27 * k, 0xD9FFFFFF);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.regular, 27 * k, AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 27 * k, 0xB3FFFFFF));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            pageTitle = Ui.text(Ui.displayBold, 56 * k, 0x99FFFFFF);
            pageTitle.setTextAlign(Paint.Align.CENTER);
            logoInk = Ui.text(Ui.displaySemibold, 58 * k, 0xFF173344);
            logoInk.setTextAlign(Paint.Align.CENTER);
            logoTile = new Paint(Paint.ANTI_ALIAS_FLAG);
            logoTile.setColor(0x70B9C8CD);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.4f * k);
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
                case APPS_DOWNLOADS: return 7;
                case GENERAL: return 8;
                case SCREENSAVER: return 6;
                case WIDGETS: return 3;
                case WEATHER: return 2;
                case DATE_TIME: return 1;
                case SYSTEM: return 3;
                case RESET: return 2;
                case TOP_PANEL: return 2;
                case ACCESSIBILITY: return 4;
                case VPN_SETTINGS: return 2;
                case MENU_BUTTONS: return 5;
                case UPDATES: return 2;
                case ABOUT: return 8;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingsGroup == SYSTEM ? 2 : settingsGroup == WIDGETS ? widgetLabels().length : settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
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
            stepRowFocus(dt, up);
            float seconds = (up - startedAt) / 1000f;
            if (updatePopup == 0 && up - lastSky >= 100) {
                lastSky = up;
                scene.advance(seconds);
            }
            drawCachedBackground(c);

            // tvOS-inspired settings layout: quiet title and a large rounded app tile.
            pageTitle.setColor(lightSettings() ? 0x66607985 : 0x73FFFFFF);
            logoInk.setColor(lightSettings() ? 0xFFECFFFF : 0xFF173344);
            logoTile.setColor(lightSettings() ? 0x9A678B96 : 0x70B9C8CD);
            drawPageTitle(c, up);
            RectF logo = logoBounds;
            logo.set(254 * k, 236 * k, 790 * k, 772 * k);
            c.drawRoundRect(logo, 116 * k, 116 * k, logoTile);
            rim.setColor(0x50FFFFFF);
            c.drawRoundRect(logo, 116 * k, 116 * k, rim);
            // Centered tvOS wordmark: approximately half the tile width.
            logoInk.setTextSize(58 * k);
            float wordmarkWidth = logoInk.measureText("tvOS");
            if (wordmarkWidth > 0f) {
                logoInk.setTextSize(logoInk.getTextSize() * (logo.width() / 2f) / wordmarkWidth);
            }
            Paint.FontMetrics wordmarkMetrics = logoMetrics;
            logoInk.getFontMetrics(wordmarkMetrics);
            float wordmarkBaseline = logo.centerY() - (wordmarkMetrics.ascent + wordmarkMetrics.descent) / 2f;
            c.drawText("tvOS", logo.centerX(), wordmarkBaseline, logoInk);

            drawListViewport(c);
            drawTileDescription(c);
            if (updatePopup != 0) drawUpdatePopup(c);
        }

        /** Reset inherited selection before drawing either page of a menu transition. */
        private void syncMenuTransition(long now) {
            if (drawnScreen == screen) return;
            boolean reduceMotion = localFlag("reduce_motion");
            boolean previous = drawnScreen >= 0;
            transitionFrom = previous && !reduceMotion && updatePopup == 0
                    && (parentScreen(screen) == drawnScreen || parentScreen(drawnScreen) == screen)
                    ? drawnScreen : -1;
            transitionStarted = now;
            focusReadyAt = now + (previous && !reduceMotion ? MENU_TRANSITION_MS : 0);
            drawnScreen = screen;
            for (Spring spring : pop) spring.snap(0);
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
        private int secondaryInk() { return lightSettings() ? 0x99182731 : 0x8CFFFFFF; }
        private int arrowInk() { return lightSettings() ? 0x8A34444C : 0x66FFFFFF; }

        /** Render the static gradient background once per size/theme, not on every focus change. */
        private void drawCachedBackground(Canvas c) {
            boolean light = lightSettings();
            if (cachedBackground == null || cachedBackgroundLight != light) {
                releaseBackground();
                // Only the smooth atmospheric gradient is downsampled; text,
                // icons, buttons and glass remain at the native resolution.
                int bw = Math.max(1, (w + 1) / 2);
                int bh = Math.max(1, (h + 1) / 2);
                try {
                    cachedBackground = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    Canvas bc = new Canvas(cachedBackground);
                    bc.scale((float) bw / w, (float) bh / h);
                    drawTvOsBackground(bc);
                    cachedBackgroundLight = light;
                } catch (OutOfMemoryError oom) {
                    releaseBackground();
                    // Avoid a crash on exceptionally memory-constrained TVs.
                    drawTvOsBackground(c);
                    return;
                }
            }
            c.drawBitmap(cachedBackground, null,
                    new Rect(0, 0, w, h), backgroundBitmapPaint);
        }

        /** Deep blue / petrol / charcoal atmosphere from the tvOS-style reference. */
        private void drawTvOsBackground(Canvas c) {
            if (lightSettings()) {
                // Airy cyan/blue with a faint lavender and coral bloom, like tvOS light mode.
                atmosphere.setShader(new LinearGradient(0, 0, w, h,
                        0xFFB6EAF9, 0xFFE4F8FA, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, atmosphere);
                atmosphere.setShader(new RadialGradient(w * 0.12f, h * 0.40f, w * 0.68f,
                        new int[]{0xC5D8FFFF, 0x70C3F3FB, 0x00FFFFFF},
                        new float[]{0f, .52f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, atmosphere);
                atmosphere.setShader(new RadialGradient(w * 0.57f, h * 1.03f, w * 0.55f,
                        new int[]{0xA9F6BFC7, 0x55E7C8E6, 0x00FFFFFF},
                        new float[]{0f, .50f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, atmosphere);
                atmosphere.setShader(new RadialGradient(w * 0.91f, h * 0.22f, w * 0.50f,
                        new int[]{0xA6B2E9FF, 0x4CB5E9F7, 0x00FFFFFF},
                        new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, atmosphere);
                atmosphere.setShader(null);
                return;
            }
            atmosphere.setShader(new LinearGradient(0, 0, w, h,
                    0xFF16323F, 0xFF131A1F, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, atmosphere);
            atmosphere.setShader(new RadialGradient(w * 0.62f, h * 0.05f, w * 0.73f,
                    new int[]{0xA6266774, 0x4C1E4451, 0x00151C21},
                    new float[]{0f, .44f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, atmosphere);
            atmosphere.setShader(new RadialGradient(w * 0.06f, h * 0.66f, w * 0.7f,
                    new int[]{0x70304C5C, 0x2B28434D, 0x00131B20},
                    new float[]{0f, .5f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, atmosphere);
            atmosphere.setShader(new LinearGradient(0, h * .28f, 0, h,
                    0x0010161B, 0xA80A0F13, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, atmosphere);
            atmosphere.setShader(null);
        }

        private String screenTitle() {
            switch (screen) {
                case GENERAL: return Ui.ru ? "Основные" : "General";
                case WIDGETS: return Ui.ru ? "Виджеты" : "Widgets";
                case WEATHER: return tr("Погода", "Weather");
                case DATE_TIME: return tr("Дата", "Date");
                case SYSTEM: return Ui.ru ? "Система" : "System";
                case RESET: return tr("Сброс", "Reset");
                case TOP_PANEL: return tr("Верхняя панель", "Menu Bar");
                case ACCESSIBILITY: return tr("Универсальный доступ", "Accessibility");
                case APPS_DOWNLOADS: return tr("Приложения", "Apps");
                case MENU_BUTTONS: return tr("Кнопки", "Buttons");
                case UPDATES: return tr("Обновления ПО", "Software Updates");
                case ABOUT: return Ui.ru ? "Об этом устройстве" : "About";
                case SCREENSAVER: return tr("Заставка", "Screen Saver");
                case HOME_CHOICE: return tr("Домашний экран", "Default Launcher");
                case VPN_SETTINGS: return "VPN";
                case HOME_SETTINGS: return settingsGroup == WIDGETS ? (Ui.ru ? "Котировки" : "Quotes") : settingsGroup == SYSTEM ? (Ui.ru ? "Категории" : "Categories") : (Ui.ru ? "Основные" : "General");
                case REMOTE_KEYS: return tr("Пульты и устройства", "Remotes and Devices");
                case SHELF_EDIT: return Ui.ru ? "Приложения в Dock" : "Dock";
                case PICKER: return pickFor == PICK_VPN ? "VPN" : pickFor == PICK_CITY ? (Ui.ru ? "Регион" : "Region") : (Ui.ru ? "Выбор приложения" : "Choose app");
                default: return Ui.ru ? "Настройки" : "Settings";
            }
        }

        private String[] menuLabels() {
            switch (screen) {
                case GENERAL: return new String[] {tr("Об этом устройстве", "About"), tr("Оформление", "Appearance"), tr("Язык", "Language"), tr("Регион", "Region"), tr("Дата и время", "Date & Time"), tr("Температура", "Temperature"), tr("Формат времени", "Time Format"), tr("Щелчки при навигации", "Navigation Clicks")};
                case WIDGETS: return new String[] {tr("Погода", "Weather"), tr("Котировки", "Quotes"), tr("Дата", "Date")};
                case DATE_TIME: return new String[] {tr("Показывать на верхней панели", "Show in Menu Bar")};
                case WEATHER: return new String[] {tr("Показывать на верхней панели", "Show in Menu Bar"), tr("Показывать на нижней панели", "Show on bottom panel")};
                case MENU_BUTTONS: return new String[] {tr("Поиск", "Search"), tr("ТВ", "TV"), "VPN", tr("Настройки", "Settings"), tr("Часы", "Clock")};
                case UPDATES: return new String[] {tr("Обновление ПО", "Update Software"), tr("Автоматическое обновление", "Automatically Update")};
                case SYSTEM: return new String[] {tr("Обновления ПО", "Software Updates"), tr("Сброс", "Reset"), tr("Перезагрузить tvOS", "Restart tvOS")};
                case RESET: return new String[] {tr("Очистить кеш", "Clear cache"), tr("Сброс tvOS", "Reset tvOS")};
                case TOP_PANEL: return new String[] {tr("Категории", "Categories"), tr("Кнопки", "Buttons")};
                case APPS_DOWNLOADS: return new String[] {tr("Загрузить AirPlay", "Download AirPlay"), tr("Загрузить Apple Music", "Download Apple Music"), tr("Загрузить TizenTube", "Download TizenTube"), tr("Загрузить INCY", "Download INCY"), tr("Загрузить Seena", "Download Seena"), tr("Загрузить Lampa", "Download Lampa"), tr("Загрузить KinoPub", "Download KinoPub")};
                case ACCESSIBILITY: return new String[] {tr("Уменьшение прозрачности", "Reduce Transparency"), tr("Уменьшение анимаций", "Reduce Motion"), tr("Домашний экран", "Default Launcher"), tr("Настройки ТВ", "TV Settings")};
                case VPN_SETTINGS: return new String[] {tr("Показывать на верхней панели", "Show in Menu Bar"), tr("VPN-служба", "VPN service")};
                case SCREENSAVER: return new String[] {tr("Источник", "Current Selection"), tr("Начало после", "Start After"), tr("Показывать часы", "Show Clock"), tr("Показывать место съёмки", "Show Location"), tr("Показывать погоду", "Show Weather"), tr("Предпросмотр", "Preview")};
                case ABOUT: return new String[] {tr("Имя", "Name"), tr("Модель", "Model"), tr("Серийный номер", "Serial Number"), "tvOS", tr("ОС", "OS"), tr("Разрешение", "Resolution"), tr("Имя сети Wi-Fi", "Wi-Fi Network Name"), tr("IP-адрес", "IP Address")};
                default: return new String[] {tr("Основные", "General"), tr("Заставка", "Screen Saver"), "AirPlay", tr("Пульты и устройства", "Remotes and Devices"), tr("Универсальный доступ", "Accessibility"), tr("Приложения", "Apps"), tr("Приложения в Dock", "Dock"), tr("Верхняя панель", "Menu Bar"), tr("Виджеты", "Widgets"), tr("Система", "System")};
            }
        }
        private android.content.SharedPreferences localPrefs() { return getContext().getSharedPreferences("settings", Context.MODE_PRIVATE); }
        private boolean localFlag(String key) { return localPrefs().getBoolean(key, false); }
        private void setLocalFlag(String key, boolean value) { localPrefs().edit().putBoolean(key, value).apply(); invalidate(); }
        private String delayValue(String key, int fallback) { int n = localPrefs().getInt(key, fallback); return n == 0 ? tr("Выкл.", "Off") : n + tr(" мин", " min"); }
        private void cycleDelay(String key, int fallback, int direction) {
            int[] choices = "dream_delay".equals(key) ? new int[] {5, 10, 15, 30, 60, 0} : new int[] {0, 2, 5, 10, 15, 30, 60}; int current = localPrefs().getInt(key, fallback), at = 0;
            for (int i = 0; i < choices.length; i++) if (choices[i] == current) at = i;
            localPrefs().edit().putInt(key, choices[(at + direction + choices.length) % choices.length]).apply();
        }
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
        private String tr(String ru, String en) { return Ui.ru ? ru : en; }
        private boolean vpnTopEnabled() { return localPrefs().getBoolean("top_button_2", false); }
        private void toggleVpnTop() { setLocalFlag("top_button_2", !vpnTopEnabled()); }
        private String[] menuValues() {
            String[] v = new String[rowCount()];
            java.util.Arrays.fill(v, "");
            if (screen == GENERAL) {
                String theme = Prefs.theme();
                v[1] = "light".equals(theme) ? tr("Светлое", "Light") : "dark".equals(theme) ? tr("Тёмное", "Dark") : tr("Авто", "Automatic");
                v[2] = settingValues()[0];
                v[3] = Prefs.city();
                v[4] = "";
                v[5] = settingValues()[1];
                v[6] = Prefs.hour24() ? tr("24 ч", "24 h") : tr("12 ч", "12 h");
                v[7] = onOff(localPrefs().getBoolean("navigation_clicks", true));
                
            } else if (screen == UPDATES) {
                v[1] = onOff(localPrefs().getBoolean("auto_update", false));
            } else if (screen == ACCESSIBILITY) {
                v[0] = onOff(localFlag("reduce_transparency"));
                v[1] = onOff(localFlag("reduce_motion"));
                v[2] = freeActive() ? "tvOS" : tr("Встроенный", "Built-in");
            } else if (screen == VPN_SETTINGS) {
                v[0] = onOff(vpnTopEnabled());
                String vpn = localPrefs().getString("vpn_app", "");
                if (vpn.isEmpty()) v[1] = tr("Не выбрано", "Not selected");
                else { try { v[1] = String.valueOf(getContext().getPackageManager().getApplicationLabel(getContext().getPackageManager().getApplicationInfo(vpn, 0))); } catch (Exception ignored) { v[1] = tr("Не найдено", "Not found"); } }
            } else if (screen == MENU_BUTTONS) {
                for (int i = 0; i < 5; i++) v[i] = i == 2 ? "" : onOff(localPrefs().getBoolean("top_button_" + i, i != 2));
            } else if (screen == SCREENSAVER) {
                String src = localPrefs().getString("video_dream_source", "google");
                v[0] = "apple".equals(src) ? "Apple" : "na".equals(src) ? "National Geographic" : "Google";
                v[1] = localPrefs().getInt("dream_delay", 5) == 0 ? tr("Никогда", "Never") : delayValue("dream_delay", 5);
                v[2] = onOff(localPrefs().getBoolean("aerial_show_clock", false));
                v[3] = onOff(localPrefs().getBoolean("aerial_show_location", false));
                v[4] = onOff(localPrefs().getBoolean("screensaver_show_weather", false));
            } else if (screen == WIDGETS) {
                // Quotes is a submenu; its switch is shown inside HOME_SETTINGS.
                // Date is a submenu; its switch is shown inside DATE_TIME.
            } else if (screen == DATE_TIME) {
                v[0] = onOff(localPrefs().getBoolean("show_date", true));
            } else if (screen == WEATHER) {
                v[0] = onOff(weatherVisible(true)); v[1] = onOff(weatherVisible(false));
            } else if (screen == ABOUT) {
                v[0] = android.os.Build.MODEL == null || android.os.Build.MODEL.trim().isEmpty()
                        ? tr("Неизвестно", "Unknown") : android.os.Build.MODEL.trim();
                v[1] = tr("Недоступен", "Unavailable");
                try {
                    String serial = android.os.Build.getSerial();
                    if (serial != null && !serial.isEmpty() && !"unknown".equalsIgnoreCase(serial)) v[1] = serial;
                } catch (Exception ignored) {}
                try {
                    v[2] = getContext().getPackageManager()
                            .getPackageInfo(getContext().getPackageName(), 0).versionName;
                } catch (Exception e) { v[2] = "—"; }
                v[3] = "Android " + android.os.Build.VERSION.RELEASE;
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                activity.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
                v[4] = dm.widthPixels + " × " + dm.heightPixels;
                v[5] = tr("Недоступно", "Unavailable");
                v[6] = "—";
                v[7] = localPrefs().getString("device_name", "ТВ");
                try {
                    java.util.Enumeration<java.net.NetworkInterface> nets = java.net.NetworkInterface.getNetworkInterfaces();
                    while (nets != null && nets.hasMoreElements()) {
                        java.net.NetworkInterface net = nets.nextElement();
                        if (!net.isUp() || net.isLoopback()) continue;
                        java.util.Enumeration<java.net.InetAddress> ips = net.getInetAddresses();
                        while (ips.hasMoreElements()) {
                            java.net.InetAddress ip = ips.nextElement();
                            if (ip instanceof java.net.Inet4Address && !ip.isLoopbackAddress()) {
                                v[6] = ip.getHostAddress();
                                break;
                            }
                        }
                        if (!"—".equals(v[6])) break;
                    }
                } catch (Exception ignored) {}
                try {
                    android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                            getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
                    android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                    if (ni != null && ni.isConnected()) {
                        if (ni.getType() == android.net.ConnectivityManager.TYPE_ETHERNET) v[5] = "Ethernet";
                        else if (ni.getType() == android.net.ConnectivityManager.TYPE_WIFI) {
                            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                                    getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                            android.net.wifi.WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                            String ssid = wi == null ? null : wi.getSSID();
                            v[5] = ssid == null || ssid.contains("unknown ssid") || ssid.equals("<unknown ssid>")
                                    ? tr("Wi-Fi (имя недоступно)", "Wi-Fi (name unavailable)") : ssid.replace("\"", "");
                        }
                    }
                } catch (Exception ignored) {}
                // Show device name first, preserving all other values.
                String deviceName = v[7];
                System.arraycopy(v, 0, v, 1, 7);
                v[0] = deviceName;
            }
            return v;
        }
        // Approved About-screen geometry (all dimensions are scaled by k).
        private static final float ABOUT_ROW_GAP = 22f;
        private static final float ABOUT_NAME_Y_OFFSET = 3f;
        private static final float ABOUT_DETAILS_Y_OFFSET = 13f;
        private static final float ABOUT_NAME_RIGHT_EXTENSION = 6f;
        private static final int ABOUT_SECONDARY_COLOR = 0xFFA1A3A1;
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
                case GENERAL: case SCREENSAVER: case REMOTE_KEYS: case ACCESSIBILITY: case APPS_DOWNLOADS:
                case SHELF_EDIT: case TOP_PANEL: case WIDGETS: case SYSTEM:
                    return MAIN;
                case ABOUT: return GENERAL;
                case HOME_CHOICE: return ACCESSIBILITY;
                case MENU_BUTTONS: return TOP_PANEL;
                case VPN_SETTINGS: return MENU_BUTTONS;
                case WEATHER: case DATE_TIME: return WIDGETS;
                case UPDATES: case RESET: return SYSTEM;
                case HOME_SETTINGS: return settingsGroup == SYSTEM ? TOP_PANEL : settingsGroup;
                case PICKER: return pickFor == PICK_CITY ? GENERAL
                        : pickFor == PICK_VPN ? VPN_SETTINGS : REMOTE_KEYS;
                default: return -1;
            }
        }

        private void drawScreenContent(Canvas c) {
            switch (screen) {
                case MAIN: case GENERAL: case WIDGETS: case WEATHER: case DATE_TIME:
                case MENU_BUTTONS: case SYSTEM: case RESET: case TOP_PANEL:
                case ACCESSIBILITY: case APPS_DOWNLOADS: case VPN_SETTINGS: case ABOUT: case SCREENSAVER:
                case UPDATES: drawMenu(c); break;
                case HOME_CHOICE: drawChoice(c); break;
                case PICKER: drawPicker(c); break;
                case SHELF_EDIT: drawShelfEditor(c); break;
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
                case SCREENSAVER: return row != 5;
                case ACCESSIBILITY: return row != 3;
                case APPS_DOWNLOADS: case RESET: return false;
                case UPDATES: return row != 0;
                case SYSTEM: return row != 2;
                case VPN_SETTINGS: return row != 0 && (vpnTopEnabled() || row != 1);
                default: return true;
            }
        }

        private void drawMenu(Canvas c) {
            String[] labels = menuLabels(), values = menuValues();
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + i * (ROW_H + ABOUT_ROW_GAP)
                        + (screen == ABOUT ? (i == 0 ? ABOUT_NAME_Y_OFFSET : ABOUT_DETAILS_Y_OFFSET) : ABOUT_NAME_Y_OFFSET)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                if (screen != ABOUT || i == 0) rowRect.right += ABOUT_NAME_RIGHT_EXTENSION * k;
                // Only the first About row (device name) is an actionable capsule.
                float f;
                if (screen != ABOUT || i == 0) {
                    f = drawApprovedCapsule(c, i);
                } else {
                    f = screen == ABOUT ? 0f : drawRowBackground(c, i);
                }
                rowLabel.setColor(screen == VPN_SETTINGS && i == 1 && !vpnTopEnabled() ? 0x66808080 : blend(normalInk(), INK, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                drawTrackedText(c, labels[i], rowRect.left + rowRect.width() * 0.10f + 10 * k, base, rowLabel, 0.02f);
                float right = rowRect.right - 30 * k;
                if (screen != ABOUT || i == 0) {
                    if (menuChevronVisible(i)) drawApprovedChevron(c);
                    rowValue.setColor(screen == VPN_SETTINGS && i == 1 && !vpnTopEnabled() ? 0x66808080 : ABOUT_SECONDARY_COLOR);
                    drawTrackedText(c, values[i], rowRect.right - 61 * k, base, rowValue, 0.02f);
                } else {
                    rowValue.setColor(blend(secondaryInk(), 0x8C1C1C1E, f));
                    drawTrackedText(c, values[i], right + 20 * k, base, rowValue, 0.02f);
                }
            }
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("RECOMENDAÇÕES", "RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("Sem chave do TMDB: veja o README (config.properties).", "No TMDB key: see the README (config.properties).");
            else if (!error.isEmpty()) line = Ui.tr("Erro: ", "Error: ") + error + ".";
            else if (count > 0) line = Ui.tr("Ligadas · " + count + " títulos nos apps da TV, 3 novos a cada hora.",
                    "On · " + count + " titles from the TV's apps, 3 new ones every hour.");
            else line = Ui.tr("Buscando os títulos…", "Fetching titles…");
            c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + 42 * k, notice);
            version.setTextAlign(Paint.Align.LEFT);
            c.drawText(Ui.tr("Usa a API do TMDB, sem endosso ou certificação do TMDB.",
                    "This product uses the TMDB API but is not endorsed or certified by TMDB."), x, y + 78 * k, version);
            version.setTextAlign(Paint.Align.CENTER);
            return y + 78 * k;
        }

        /** tvOS-style soft graphite capsules; focused row lifts into an opaque white pill. */
        private float drawRowBackground(Canvas c, int i) {
            float f = i >= 0 && i < pop.length ? Ui.clamp(pop[i].value) : 0f;
            float radius = rowRect.height() / 2f;
            // Light mode uses pale frosted cyan pills; dark mode keeps graphite.
            Shader gradient = lightSettings() ? lightCapsuleGradient : darkCapsuleGradient;
            float gradientLength = (float) Math.hypot(rowRect.width(), rowRect.height());
            capsuleMatrix.setSinCos(rowRect.height() / gradientLength, rowRect.width() / gradientLength);
            capsuleMatrix.postScale(gradientLength, gradientLength);
            capsuleMatrix.postTranslate(rowRect.left, rowRect.top);
            gradient.setLocalMatrix(capsuleMatrix);
            capsule.setShader(gradient);
            c.drawRoundRect(rowRect, radius, radius, capsule);
            capsule.setShader(null);
            if (f > 0.01f) {
                float scale = 1f + 0.012f * f;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * scale / 2f,
                        cy - rowRect.height() * scale / 2f,
                        cx + rowRect.width() * scale / 2f,
                        cy + rowRect.height() * scale / 2f);
                float r = tmp.height() / 2f;
                // A restrained lift shadow, rather than a glass highlight.
                tmp.offset(0, 7 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, 0.34f * f));
                rowShadows.draw(c, tmp, r, 22 * k, shadow);
                tmp.offset(0, -7 * k * f);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(tmp, r, r, white);
                rowRect.set(tmp);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = LIST_X * k + 30 * k, colA = LIST_R * k - 220 * k, colB = LIST_R * k - 30 * k;
            c.drawText(Ui.tr("COMPARAÇÃO", "COMPARISON"), x, y, header);
            cellValue.setColor(0x73FFFFFF);
            c.drawText(Ui.tr("Original", "Original"), colA, y, cellValue);
            c.drawText("tvOS", colB, y, cellValue);
            cellValue.setColor(0xD9FFFFFF);
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Botão Início", "Home button"), "homeMs"}, {Ui.tr("Memória livre", "Free memory"), "memAvailableMB"},
                    {Ui.tr("Memória do Início", "Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * rows.length) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = LIST_X * k + 30 * k;
            c.drawText(Ui.tr("AVISOS", "NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + (Ui.ru ? Russian.text(o.optString("text")) : o.optString("text"));
                c.drawText(TextUtils.ellipsize(line, notice, 790 * k, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("Nenhum ainda.", "None yet."), x, y + 42 * k, notice);
        }

        /** Home launcher selection, using the same compact tvOS capsules as other screens. */
        private void drawChoice(Canvas c) {
            String[] options = {"tvOS", tr("Встроенный", "Built-in")};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < options.length; i++) {
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
        private void drawTileDescription(Canvas c) {
            String info = null;
            switch (screen) {
                case UPDATES:
                    info = updateDetailsText();
                    break;
                case HOME_CHOICE:
                    info = tr("tvOS — без рекламы, с новой заставкой. Встроенный — заводской главный экран телевизора. Переключение занимает несколько секунд; экран может мигать.",
                            "tvOS has no ads and a new screen saver. Built-in uses the TV's original home screen. Switching can take a few seconds.");
                    break;
                case SHELF_EDIT:
                    info = tr("Выберите до восьми приложений для Dock. OK — добавить или убрать. Стрелки влево и вправо меняют порядок.",
                            "Choose up to eight Dock apps. OK adds or removes an app. Left and right change its order.");
                    break;
                case PICKER:
                    info = tr("Нажмите OK для выбора. Назад — отмена.", "Press OK to select. Back to cancel.");
                    break;
                case HOME_SETTINGS:
                    info = tr("OK или стрелки изменяют выбранный параметр.", "Press OK or the arrow keys to change a setting.");
                    if (screen == HOME_SETTINGS && settingsGroup == SYSTEM)
                        info = tr("Включайте и выключайте разделы главного экрана.", "Enable or disable home screen sections.");
                    break;
                case TOP_PANEL:
                    if (focus == 1) info = tr("Настройка кнопок быстрого доступа на верхней панели.", "Configure quick-access buttons in the Menu Bar.");
                    break;
                case MENU_BUTTONS:
                    info = tr("Настройте кнопки быстрого доступа на верхней панели.", "Configure quick-access buttons in the Menu Bar.");
                    break;
                case RESET:
                    info = tr("Очистка кеша не удаляет настройки. Сброс tvOS удалит все данные приложения.", "Clearing cache preserves settings. Reset tvOS erases all app data.");
                    break;
                case REMOTE_KEYS:
                    info = tr("Выберите назначение кнопок пульта. Некоторые кнопки могут управляться самим телевизором.",
                            "Choose remote button actions. Some buttons may be controlled by the TV.");
                    break;
            }
            if (info == null) return;
            descriptionPaint.setColor(lightSettings() ? 0xB33A5761 : 0xB3C9D5D9);
            int width = Math.round(740 * k);
            if (descriptionLayout == null || !info.equals(descriptionText) || width != descriptionWidth) {
                descriptionPaint.setTypeface(Ui.regular);
                descriptionPaint.setTextSize(25 * k);
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
            if (updateInstalledVersion == null) updateInstalledVersion = versionName(getContext());
            long checkedAt = localPrefs().getLong("self_update_attempt", 0);
            if (updateCheckShown == null || checkedAt != updateCheckAt || updateCheckRussian != Ui.ru) {
                updateCheckAt = checkedAt;
                updateCheckRussian = Ui.ru;
                updateCheckShown = formatLastUpdateCheck(checkedAt, Ui.ru);
            }
            return updateInstalledVersion + "\n" + updateCheckShown;
        }

        private static String formatLastUpdateCheck(long checkedAt, boolean russian) {
            if (checkedAt <= 0) return russian ? "Последняя проверка: ещё не выполнялась" : "Last check: Never";
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                    russian ? "d MMMM yyyy 'г. в' HH:mm:ss" : "MMMM d, yyyy 'at' HH:mm:ss",
                    russian ? new java.util.Locale("ru", "RU") : java.util.Locale.US);
            return (russian ? "Последняя проверка: " : "Last check: ")
                    + format.format(new java.util.Date(checkedAt));
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return v + (Ui.ru ? " мс" : " ms");
            if (key.equals("memAvailableMB") && v >= 1024) return String.format(Ui.locale(), Ui.ru ? "%.2f ГБ" : "%.2f GB", v / 1024.0);
            return v + (Ui.ru ? " МБ" : " MB");
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("agora", "now");
            if (minutes < 60) return Ui.tr("há " + minutes + " min", minutes + " min ago");
            if (minutes < 48 * 60) return Ui.tr("há " + (minutes / 60) + " h", (minutes / 60) + " h ago");
            return Ui.tr("há " + (minutes / 1440) + " dias", (minutes / 1440) + " days ago");
        }

        private static String versionName(Context c) {
            try {
                return "tvOS " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "tvOS";
            }
        }

        private void toggleAutomaticUpdates() {
            boolean enabled = !localPrefs().getBoolean("auto_update", false);
            setLocalFlag("auto_update", enabled);
            if (enabled) SelfUpdate.get(getContext()).maybeAutomatic(true);
        }

        private void onSelfUpdate() {
            if (!running || !updateRequested) return;
            SelfUpdate updater = SelfUpdate.get(getContext());
            SelfUpdate.Status update = updater.status();
            int mode = update.kind == SelfUpdate.CHECKING ? 4
                    : update.kind == SelfUpdate.DOWNLOADING ? 5
                    : update.kind == SelfUpdate.AVAILABLE ? 2
                    : update.kind == SelfUpdate.READY ? 6
                    : update.kind == SelfUpdate.ERROR ? 3 : 1;
            if (updatePopup != mode || updatePopupClosing) showUpdatePopup(mode);
            else invalidate();
            if (update.kind == SelfUpdate.READY && installWhenReady) {
                installWhenReady = false;
                updater.install(activity);
            }
        }

        private void showUpdatePopup(int mode) {
            updatePopup = mode;
            updatePopupFocus = 0;
            updatePopupClosing = false;
            updatePopupStarted = SystemClock.uptimeMillis();
            invalidate();
        }

        private void closeUpdatePopup() {
            updateRequested = false;
            installWhenReady = false;
            updatePopupClosing = true;
            updatePopupStarted = SystemClock.uptimeMillis();
            invalidate();
        }

        private boolean updatePopupKey(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_UP) return true;
            int code = e.getKeyCode();
            if (updatePopupClosing) return true;
            if (code == KeyEvent.KEYCODE_BACK) { closeUpdatePopup(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (updatePopup == 2 || updatePopup == 6) updatePopupFocus = code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
                invalidate(); return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (updatePopup == 2 && updatePopupFocus == 0) {
                    installWhenReady = true;
                    SelfUpdate.get(getContext()).download();
                } else if (updatePopup == 6 && updatePopupFocus == 0) {
                    SelfUpdate.get(getContext()).install(activity);
                } else closeUpdatePopup();
                return true;
            }
            return true;
        }

        private void drawUpdatePopup(Canvas c) {
            long elapsed = SystemClock.uptimeMillis() - updatePopupStarted;
            float t = Math.min(1f, elapsed / 260f);
            float ease = 1f - (1f-t)*(1f-t)*(1f-t);
            float alpha = updatePopupClosing ? 1f-ease : ease;
            if (updatePopupClosing && t >= 1f) {
                updatePopup = 0;
                updatePopupClosing = false;
                return;
            }
            if (t < 1f) postInvalidateDelayed(16);
            c.save();
            Paint p = popupPaint;
            p.reset();
            p.setAntiAlias(true);
            // Match the underlying settings theme: warm translucent dimming in
            // light mode, near-black dimming in dark mode.
            p.setColor(lightSettings() ? 0xFF62645B : 0xFF000000);
            p.setAlpha(Math.round((lightSettings() ? 105 : 175) * alpha));
            c.drawRect(0, 0, w, h, p);
            float left = 470*k, right = 1450*k;
            float top = ((updatePopup == 2 || updatePopup == 6) ? 205 : 310)*k;
            float bottom = ((updatePopup == 2 || updatePopup == 6) ? 875 : 765)*k;
            float cx = (left+right)/2f, cy = (top+bottom)/2f;
            float scale = 0.94f + 0.06f*ease;
            if (updatePopupClosing) scale = 1f - 0.06f*ease;
            c.save();
            c.scale(scale, scale, cx, cy);
            boolean fading = alpha < 0.999f;
            if (fading) c.saveLayerAlpha(left-70*k, top-70*k, right+70*k, bottom+70*k, Math.round(255*alpha));
            RectF panel = popupPanel;
            panel.set(left, top, right, bottom);
            // Flat, frosted Apple TV panel: no heavy shadow or bright gloss strip.
            // The subtle vertical tint echoes the light/dark settings backgrounds.
            p.clearShadowLayer();
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(left, top, left, bottom,
                    lightSettings() ? 0xF2DEE4E1 : 0xF21F2223,
                    lightSettings() ? 0xF2BFCFD0 : 0xF2232526,
                    Shader.TileMode.CLAMP));
            c.drawRoundRect(panel, 48*k, 48*k, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f*k);
            p.setColor(lightSettings() ? 0x55FFFFFF : 0x24FFFFFF);
            c.drawRoundRect(panel, 48*k, 48*k, p);
            p.setStyle(Paint.Style.FILL);
            Paint heading = popupHeading;
            heading.setColor(lightSettings() ? 0xFF202631 : 0xFFFFFFFF);
            Paint body = popupBody;
            body.setColor(lightSettings() ? 0xFF364050 : 0xFFE5E9F3);
            c.drawText(tr("Обновление tvOS", "tvOS Update"), cx, top+100*k, heading);
            String[] lines;
            SelfUpdate.Status update = SelfUpdate.get(getContext()).status();
            String availableVersion = update.release == null ? "" : update.release.versionName;
            if (updatePopup == 2) lines = Ui.ru ? new String[] {
                "Доступно обновление до версии " + availableVersion + ".", "Загрузить и установить его сейчас?",
                "Установку потребуется подтвердить на ТВ.", "Не отключайте ТВ во время загрузки", "и установки обновления."
            } : new String[] {"Update " + availableVersion + " is available.", "Download and install it now?",
                "Confirm installation on your TV.", "Do not turn off your TV during download", "or installation."};
            else if (updatePopup == 3) lines = new String[] {tr("Не удалось обновить tvOS", "Could not update tvOS"),
                    update.message.length() <= 44 ? update.message : update.message.substring(0, 44),
                    update.message.length() <= 44 ? "" : update.message.substring(44)};
            else if (updatePopup == 4) lines = new String[] {tr("Проверка обновлений…", "Checking for updates…")};
            else if (updatePopup == 5) lines = new String[] {tr("Загрузка обновления…", "Downloading update…"),
                    update.percent < 0 ? "" : update.percent + "%"};
            else if (updatePopup == 6) lines = new String[] {tr("Обновление загружено", "Update downloaded"),
                    tr("Версия ", "Version ") + availableVersion};
            else lines = new String[] {tr("tvOS актуальна", "tvOS is up to date"), versionName(getContext()).replace("tvOS ", "")};
            float lineY = top+(updatePopup == 1 ? 203 : 167)*k;
            for (String line : lines) { c.drawText(line, cx, lineY, body); lineY += 49*k; }
            String[] buttons = updatePopup == 2 ? (Ui.ru ? new String[] {"Загрузить и установить", "Обновить позже"} : new String[] {"Download and Install", "Update Later"})
                    : updatePopup == 6 ? (Ui.ru ? new String[] {"Установить", "Позже"} : new String[] {"Install", "Later"}) : new String[] {"OK"};
            float buttonTop = bottom - (buttons.length*78+45)*k;
            Paint buttonText = popupButtonText;
            for (int i=0; i<buttons.length; i++) {
                RectF b = popupButton;
                b.set(left+65*k, buttonTop+i*82*k, right-65*k, buttonTop+(i*82+68)*k);
                boolean selected = i == updatePopupFocus || buttons.length == 1;
                // Apple TV-style buttons: static fills, no shadows or per-frame focus animation.
                p.clearShadowLayer();
                p.setShader(null);
                p.setStyle(Paint.Style.FILL);
                p.setColor(selected ? 0xF5F7F8FA : 0x709BA4AB);
                c.drawRoundRect(b, 17*k, 17*k, p);
                buttonText.setColor(selected ? 0xFF171A1F : 0xDB252A30);
                c.drawText(buttons[i], b.centerX(), b.centerY()+Ui.halfCap(buttonText), buttonText);
            }
            if (fading) c.restore();
            c.restore();
            c.restore();
        }

        boolean onKey(KeyEvent e) {
            if (updatePopup != 0) {
                if (e.getAction() == KeyEvent.ACTION_UP) {
                    int code = e.getKeyCode();
                    if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) tvosClick.move();
                    else if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BACK) tvosClick.open();
                }
                return updatePopupKey(e);
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
                    if (down && e.getRepeatCount() == 0 && (screen == SHELF_EDIT || canAdjustMenu())) tvosClick.move();
                    if (down && screen == SHELF_EDIT) {
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
                            if (screen == PICKER && pickFor == PICK_CITY) { focus = 3; screen = GENERAL; }
                            else if (screen == PICKER && pickFor == PICK_VPN) { focus = 1; screen = VPN_SETTINGS; }
                            else if (screen == PICKER) { focus = pickFor - 1; screen = REMOTE_KEYS; }
                            else if (screen == HOME_CHOICE) { screen = ACCESSIBILITY; focus = 2; }
                            else if (screen == SCREENSAVER) { screen = MAIN; focus = 1; }
                            else if (screen == ABOUT) { screen = GENERAL; focus = 0; }
                            else if (screen == REMOTE_KEYS) { screen = MAIN; focus = 3; }
                            else if (screen == MENU_BUTTONS) { screen = TOP_PANEL; focus = 1; }
                            else if (screen == VPN_SETTINGS) { screen = MENU_BUTTONS; focus = 2; }
                            else if (screen == UPDATES) { screen = SYSTEM; focus = 0; }
                            else if (screen == HOME_SETTINGS) { screen = settingsGroup == SYSTEM ? TOP_PANEL : settingsGroup; focus = settingsGroup == SYSTEM ? 0 : settingsGroup == WIDGETS ? 1 : 0; }
                            else if (screen == RESET) { screen = SYSTEM; focus = 1; }
                            else if (screen == WEATHER) { screen = WIDGETS; focus = 0; }
                            else if (screen == DATE_TIME) { screen = WIDGETS; focus = 2; }
                            else if (screen == SHELF_EDIT) { screen = MAIN; focus = 6; }
                            else if (screen == APPS_DOWNLOADS) { screen = MAIN; focus = 5; }
                            else {
                                // Determine the parent row BEFORE replacing the child screen.
                                int child = screen;
                                focus = child == GENERAL ? 0 : child == ACCESSIBILITY ? 4 : child == TOP_PANEL ? 7 : child == WIDGETS ? 8 : child == SYSTEM ? 9 : 0;
                                screen = MAIN;
                            }
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

        /** Only app-owned cache; preserves preferences, databases and downloaded user data. */
        private static boolean deleteCacheContents(java.io.File dir) {
            if (dir == null || !dir.exists()) return true;
            boolean ok = true;
            java.io.File[] children = dir.listFiles();
            if (children == null) return false;
            for (java.io.File child : children) {
                if (child.isDirectory() && !java.nio.file.Files.isSymbolicLink(child.toPath()))
                    ok &= deleteCacheContents(child);
                if (!child.delete()) ok = false;
            }
            return ok;
        }

        private void confirmClearCache() {
            new AlertDialog.Builder(activity)
                    .setTitle(tr("Очистить кеш?", "Clear cache?"))
                    .setMessage(tr("Удалятся временные постеры, изображения и кеш заставок. Настройки сохранятся.",
                            "Temporary posters, images and screensaver cache will be deleted. Settings are preserved."))
                    .setNegativeButton(tr("Отмена", "Cancel"), null)
                    .setPositiveButton(tr("Очистить", "Clear"), (dialog, which) -> {
                        boolean ok = deleteCacheContents(activity.getCacheDir());
                        java.io.File external = activity.getExternalCacheDir();
                        if (external != null) ok &= deleteCacheContents(external);
                        android.widget.Toast.makeText(activity,
                                ok ? tr("Кеш очищен", "Cache cleared") : tr("Не весь кеш удалось очистить", "Some cache could not be cleared"),
                                android.widget.Toast.LENGTH_SHORT).show();
                    }).show();
        }

        private void confirmResetTvos() {
            new AlertDialog.Builder(activity)
                    .setTitle(tr("Сбросить tvOS?", "Reset tvOS?"))
                    .setMessage(tr("Все настройки, выбранные приложения и данные tvOS будут удалены. Отменить действие нельзя.",
                            "All tvOS settings, selected apps and app data will be erased. This cannot be undone."))
                    .setNegativeButton(tr("Отмена", "Cancel"), null)
                    .setPositiveButton(tr("Сбросить tvOS", "Reset tvOS"), (dialog, which) -> {
                        android.app.ActivityManager am = (android.app.ActivityManager)
                                activity.getSystemService(Context.ACTIVITY_SERVICE);
                        if (am == null || !am.clearApplicationUserData())
                            android.widget.Toast.makeText(activity,
                                    tr("Не удалось сбросить tvOS", "Could not reset tvOS"),
                                    android.widget.Toast.LENGTH_LONG).show();
                    }).show();
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] LANGUAGES = {"ru", "en"}, UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** Home Settings: language, temperature, theme, weather, quotes, then one row per coin. */
        private static final int CITY_ROW = 4, COIN_ROWS = 6;
        private int detectivesRow() { return dollar() ? 10 : 9; }
        private int randomRow() { return detectivesRow() + 1; }

        private boolean dollar() {
            return "BRL".equalsIgnoreCase(Config.CURRENCY.trim());
        }

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Idioma", "Language"));
            l.add(Ui.tr("Temperatura", "Temperature"));
            l.add(Ui.tr("Tema", "Theme"));
            l.add(Ui.tr("Widget do clima", "Weather widget"));
            l.add((Ui.ru ? "Город" : Ui.tr("Cidade", "City")));
            l.add(Ui.ru ? "Показывать на нижней панели" : "Show on bottom panel");
            l.add("Bitcoin");
            l.add("Ethereum");
            l.add("Solana");
            if (dollar()) l.add(Ui.tr("Dólar", "Dollar"));
            l.add(Ui.ru ? "Детективы" : "Detectives");
            l.add(Ui.ru ? "Случайный фильм" : "Random movie");
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("ru") ? "Русский" : "English";
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("Automática", "Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Claro", "Light") : theme.equals("dark") ? Ui.tr("Escuro", "Dark") : Ui.tr("Automático", "Automatic");
            v[3] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[5] = onOff(Prefs.quotes());
            // No quotes widget, nowhere to show prices: the coins read as off (and stay locked) until it is back on.
            for (int i = COIN_ROWS; i < detectivesRow(); i++) v[i] = onOff(Prefs.quotes() && Prefs.coin(i - COIN_ROWS));
            v[detectivesRow()] = onOff(Prefs.detectives());
            v[randomRow()] = onOff(Prefs.randomMovie());
            return v;
        }

        private String[] widgetLabels() {
            String[] all = settingLabels();
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            result.add(all[5]);
            for (int i = COIN_ROWS; i < detectivesRow(); i++) result.add(all[i]);
            return result.toArray(new String[0]);
        }
        private String[] widgetValues() {
            String[] all = settingValues();
            java.util.ArrayList<String> result = new java.util.ArrayList<>();
            result.add(all[5]);
            for (int i = COIN_ROWS; i < detectivesRow(); i++) result.add(all[i]);
            return result.toArray(new String[0]);
        }
        private int settingIndex() {
            if (settingsGroup == SYSTEM) return focus == 0 ? detectivesRow() : randomRow();
            if (settingsGroup == WIDGETS) return focus == 0 ? 5 : COIN_ROWS + focus - 1;
            return focus;
        }
        private static String onOff(boolean on) {
            return on ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }

        private String[] keyLabels() {
            return new String[] {Ui.tr("Botão Netflix", "Netflix Button"), Ui.tr("Botão YouTube", "YouTube Button"),
                    Ui.tr("Botão Prime Video", "Prime Video Button"), Ui.tr("Botão TCL Channel", "TCL Channel Button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Não instalado", "Not installed");
            }
            return v;
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
            appLabels.add(tr("Не выбрано", "Not selected"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        private void launchAirPlay() {
            final String packageName = "io.github.jqssun.airplay";
            PackageManager pm = getContext().getPackageManager();
            Intent launch = pm.getLeanbackLaunchIntentForPackage(packageName);
            if (launch == null) launch = pm.getLaunchIntentForPackage(packageName);
            if (launch != null) {
                try {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(launch);
                    return;
                } catch (Exception ignored) { }
            }
            android.widget.Toast.makeText(getContext(),
                    tr("AirPlay не установлен или недоступен", "AirPlay is not installed or unavailable"),
                    android.widget.Toast.LENGTH_SHORT).show();
        }

        private void toggleMenuButton() {
            String key = "top_button_" + focus;
            setLocalFlag(key, !localPrefs().getBoolean(key, focus != 2));
        }

        private void toggleDateTime() {
            String key = "show_date";
            setLocalFlag(key, !localPrefs().getBoolean(key, true));
        }

        /** Only value rows respond to left/right; actions and submenus require OK. */
        private boolean canAdjustMenu() {
            switch (screen) {
                case HOME_SETTINGS: case WEATHER: case DATE_TIME: return true;
                case GENERAL: return focus == 1 || focus == 2 || focus == 5 || focus == 6 || focus == 7;
                case SCREENSAVER: return focus < 5;
                case UPDATES: return focus == 1;
                case ACCESSIBILITY: return focus < 2;
                case VPN_SETTINGS: return focus == 0;
                case MENU_BUTTONS: return focus != 2;
                default: return false;
            }
        }

        private void adjustMenu(int dir) {
            if (screen == HOME_SETTINGS) { adjust(dir); return; }
            if (screen == WIDGETS && focus == 2) { return; }
            if (screen == WEATHER) { toggleWeather(focus == 0); return; }
            if (screen == DATE_TIME) { toggleDateTime(); return; }
            if (screen == SCREENSAVER) {
                if (focus == 0) cycleDreamSource(dir);
                else if (focus == 1) cycleDelay("dream_delay", 5, dir);
                else if (focus == 2) setLocalFlag("aerial_show_clock", !localPrefs().getBoolean("aerial_show_clock", false));
                else if (focus == 3) setLocalFlag("aerial_show_location", !localPrefs().getBoolean("aerial_show_location", false));
                else if (focus == 4) setLocalFlag("screensaver_show_weather", !localPrefs().getBoolean("screensaver_show_weather", false));
                return;
            }
            if (screen == UPDATES) { if (focus == 1) toggleAutomaticUpdates(); return; }
            if (screen == ACCESSIBILITY) { if (focus == 0) setLocalFlag("reduce_transparency", !localFlag("reduce_transparency")); else if (focus == 1) setLocalFlag("reduce_motion", !localFlag("reduce_motion")); return; }
            if (screen == VPN_SETTINGS) { if (focus == 0) toggleVpnTop(); return; }
            if (screen == MENU_BUTTONS) { if (focus != 2) toggleMenuButton(); return; }
            if (screen == GENERAL) {
                if (focus == 1) { int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme()); Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]); }
                else if (focus == 2) { int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language()); Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.length) % LANGUAGES.length]); }
                else if (focus == 5) { int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature()); Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]); }
                else if (focus == 6) { Prefs.setHour24(!Prefs.hour24()); }
                else if (focus == 7) { setLocalFlag("navigation_clicks", !localPrefs().getBoolean("navigation_clicks", true)); }
                HomeData.get(getContext()).settingsChanged();
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
            if (selected == detectivesRow()) { Prefs.setDetectives(!Prefs.detectives()); HomeData.get(getContext()).settingsChanged(); return; }
            if (selected == randomRow()) { Prefs.setRandomMovie(!Prefs.randomMovie()); HomeData.get(getContext()).settingsChanged(); return; }
            switch (selected) {
                case 0: {
                    int at = java.util.Arrays.asList(LANGUAGES).indexOf(Prefs.language());
                    Prefs.setLanguage(LANGUAGES[(at + dir + LANGUAGES.length) % LANGUAGES.length]);
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
                case 5: Prefs.setQuotes(!Prefs.quotes()); break;

                default:
                    if (!Prefs.quotes()) return;
                    Prefs.setCoin(selected - COIN_ROWS, !Prefs.coin(selected - COIN_ROWS));
                    break;
            }
            HomeData.get(getContext()).settingsChanged();
        }

        private void askCity() {
            if ((Ui.ru ? "Поиск…" : Ui.tr("Procurando…", "Searching…")).equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint((Ui.ru ? "Введите название города" : Ui.tr("Digite o nome da cidade", "Type the city's name")));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle((Ui.ru ? "Город" : Ui.tr("Cidade", "City")))
                    .setView(field)
                    .setPositiveButton((Ui.ru ? "Найти" : Ui.tr("Buscar", "Search")), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton((Ui.ru ? "Отмена" : Ui.tr("Cancelar", "Cancel")), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchCity(String name) {
            if (!running || name.trim().isEmpty()) return;
            if (cityRequest != null) cityRequest.cancel();
            handler.removeCallbacks(clearCityStatus);
            final int serial = ++citySerial;
            final int originScreen = screen;
            UiRequest request = new UiRequest(CITY_IO);
            cityRequest = request;
            cityStatus = (Ui.ru ? "Поиск…" : Ui.tr("Procurando…", "Searching…"));
            invalidate();
            request.start(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? (Ui.ru ? "Город не найден." : Ui.tr("Nenhuma cidade com esse nome.", "No city with that name.")) : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = (Ui.ru ? "Ошибка поиска города: " : Ui.tr("Não deu para buscar agora: ", "Couldn't search now: ")) + e.getMessage();
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                if (!request.active()) return;
                handler.post(() -> {
                    if (!request.active() || !running || serial != citySerial) return;
                    cityRequest = null;
                    if (screen != originScreen) { cityStatus = ""; return; }
                    cityStatus = message;
                    if (!result.isEmpty() && (screen == HOME_SETTINGS || screen == GENERAL)) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(clearCityStatus, 4000);
                });
            });
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : pickLabels.size();
        }

        // MARK: - Shelf editor

        private void openShelfEditor() {
            shelfOrder.clear();
            shelfOrder.addAll(HomeApps.shelfPackages(getContext()));
            rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            pickScroll = 0;
            listScroll.snap(0);
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            // The centered screenTitle() is the only heading.
            shelfMeasurePaint.set(rowLabel);
            for (int at = scrollingFirstRow(); at < scrollingEndRow(); at++) {
                int i = at - pickScroll;
                String[] app = shelfRows.get(at);
                int place = shelfOrder.indexOf(app[0]);
                setScrollingRowRect(at);
                float f = drawApprovedCapsule(c, i);
                rowLabel.setColor(blend(normalInk(), INK, f));
                rowValue.setColor(ABOUT_SECONDARY_COLOR);
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                drawTrackedText(c, TextUtils.ellipsize(app[1], shelfMeasurePaint, 520 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + rowRect.width() * 0.10f + 10 * k, base, rowLabel, 0.02f);
                drawTrackedText(c, place >= 0 ? "#" + (place + 1) : Ui.tr("Fora", "Off"), rowRect.right - 61 * k, base, rowValue, 0.02f);
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
            for (int at = scrollingFirstRow(); at < scrollingEndRow(); at++) {
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
            String[] labels = keys ? keyLabels() : settingsGroup == SYSTEM ? new String[] {settingLabels()[detectivesRow()], settingLabels()[randomRow()]} : settingsGroup == WIDGETS ? widgetLabels() : settingLabels();
            String[] values = keys ? keyValues() : settingsGroup == SYSTEM ? new String[] {settingValues()[detectivesRow()], settingValues()[randomRow()]} : settingsGroup == WIDGETS ? widgetValues() : settingValues();
            // The screen title is already drawn centrally; no legacy subtitle.
            for (int i = 0; i < labels.length; i++) {
                setApprovedRowRect(i);
                float f = drawApprovedCapsule(c, i);
                rowLabel.setColor(blend(normalInk(), INK, f));
                boolean locked = !keys && settingsGroup == WIDGETS && i >= 1 && !Prefs.quotes();
                rowValue.setColor(locked ? 0x88A1A3A1 : ABOUT_SECONDARY_COLOR);
                if (locked) rowLabel.setColor(blend(lightSettings() ? 0x77304450 : 0x73FFFFFF, 0x731C1C1E, f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                drawTrackedText(c, labels[i], rowRect.left + rowRect.width() * 0.10f + 10 * k, base, rowLabel, 0.02f);
                drawTrackedText(c, values[i], rowRect.right - 61 * k, base, rowValue, 0.02f);
                drawApprovedChevron(c);
            }

        }


        private void askDeviceName() {
            final android.widget.EditText input =
                    new android.widget.EditText(getContext());
            input.setSingleLine(true);
            input.setText(localPrefs().getString("device_name", "ТВ"));
            input.selectAll();

            new android.app.AlertDialog.Builder(getContext())
                    .setTitle(tr("Имя", "Name"))
                    .setView(input)
                    .setNegativeButton(tr("Отмена", "Cancel"), null)
                    .setPositiveButton(tr("Сохранить", "Save"), (dialog, which) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) name = "ТВ";
                        localPrefs().edit()
                                .putString("device_name", name).apply();
                        invalidate();
                    })
                    .show();

            input.requestFocus();
        }

        private void activate() {
            final int screenBeforeAction = screen;
            if (screen == ABOUT) {
                if (focus == 0) askDeviceName();
                return;
            }
            Context c = getContext();
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                if (!busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                // Keep the user on Home screen selection. The old GENERAL/focus=7
                // jump incorrectly opened Navigation clicks when tvOS was already active.
                invalidate();
                return;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                focus = 3;
                screen = GENERAL;
            } else if (screen == PICKER && pickFor == PICK_VPN) {
                String pkg = appPkgs.get(pickScroll + focus);
                localPrefs().edit().putString("vpn_app", pkg).apply();
                if (!pkg.isEmpty()) {
                    java.util.List<String> shelf = Prefs.shelf();
                    if (shelf == null) shelf = new java.util.ArrayList<>(HomeApps.shelfPackages(c));
                    if (shelf.remove("llc.itdev.incy")) Prefs.setShelf(shelf);
                }
                focus = 1; screen = VPN_SETTINGS;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS && settingIndex() == CITY_ROW) {
                askCity();
                return;
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (screen == MAIN) {
                if (focus == 0) { screen = GENERAL; focus = 0; }
                else if (focus == 1) { screen = SCREENSAVER; focus = 0; }
                else if (focus == 2) {
                    launchAirPlay();
                    return;
                }
                else if (focus == 3) { HomeMode.ensureRemoteService(c, 0); loadApps(); screen = REMOTE_KEYS; focus = 0; }
                else if (focus == 4) { screen = ACCESSIBILITY; focus = 0; }
                else if (focus == 5) { screen = APPS_DOWNLOADS; focus = 0; }
                else if (focus == 6) { openShelfEditor(); return; }
                else if (focus == 7) { screen = TOP_PANEL; focus = 0; }
                else if (focus == 8) { screen = WIDGETS; focus = 0; }
                else if (focus == 9) { screen = SYSTEM; focus = 0; }

            } else if (screen == APPS_DOWNLOADS) {
                AppDownloads.start(activity, focus);
                return;
            } else if (screen == GENERAL) {
                if (focus == 3) { askCity(); return; }
                if (focus == 0) { screen = ABOUT; focus = 0; }
                else if (focus == 4) { activity.startActivity(new Intent(Settings.ACTION_DATE_SETTINGS)); return; }
                else { adjustMenu(1); }
            } else if (screen == WIDGETS) {
                if (focus == 2) { screen = DATE_TIME; focus = 0; }
                else if (focus == 0 || focus == 1) {
                    if (focus == 0) { screen = WEATHER; focus = 0; }
                    else { returnFocus = focus; settingsGroup = WIDGETS; screen = HOME_SETTINGS; focus = 0; }
                }
            } else if (screen == UPDATES) {
                if (focus == 0) {
                    updateRequested = true;
                    SelfUpdate updater = SelfUpdate.get(getContext());
                    if (updater.status().kind != SelfUpdate.READY) updater.check(false);
                    onSelfUpdate();
                } else toggleAutomaticUpdates();
            } else if (screen == MENU_BUTTONS) {
                if (focus == 2) { screen = VPN_SETTINGS; focus = 0; }
                else toggleMenuButton();
            } else if (screen == VPN_SETTINGS) {
                if (focus == 0) toggleVpnTop();
                else if (vpnTopEnabled()) { loadApps(); openPicker(PICK_VPN); }
            } else if (screen == DATE_TIME) {
                toggleDateTime();
            } else if (screen == WEATHER) {
                toggleWeather(focus == 0);
            } else if (screen == SCREENSAVER) {
                if (focus == 0) cycleDreamSource(1);
                else if (focus == 1) cycleDelay("dream_delay", 5, 1);
                else if (focus == 2) setLocalFlag("aerial_show_clock", !localPrefs().getBoolean("aerial_show_clock", false));
                else if (focus == 3) setLocalFlag("aerial_show_location", !localPrefs().getBoolean("aerial_show_location", false));
                else if (focus == 4) setLocalFlag("screensaver_show_weather", !localPrefs().getBoolean("screensaver_show_weather", false));
                else if (focus == 5) { pause(); activity.showPreview(); return; }
            } else if (screen == TOP_PANEL) {
                if (focus == 0) { settingsGroup = SYSTEM; screen = HOME_SETTINGS; focus = 0; }
                else if (focus == 1) { screen = MENU_BUTTONS; focus = 0; }
            } else if (screen == ACCESSIBILITY) {
                if (focus == 0) setLocalFlag("reduce_transparency", !localFlag("reduce_transparency"));
                else if (focus == 1) setLocalFlag("reduce_motion", !localFlag("reduce_motion"));
                else if (focus == 2) { screen = HOME_CHOICE; focus = freeActive() ? 0 : 1; }
                else if (focus == 3) { activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            } else if (screen == RESET) {
                if (focus == 0) confirmClearCache();
                else if (focus == 1) confirmResetTvos();
                return;
            } else if (screen == SYSTEM) {
                if (focus == 0) { screen = UPDATES; focus = 0; }
                else if (focus == 1) { screen = RESET; focus = 0; }
                else if (focus == 2) {
                    // Restart the actual HomeActivity, not this SettingsActivity.
                    Intent restart = new Intent(activity, HomeActivity.class);
                    restart.putExtra("io.tvos.RESTART_HOME", true);
                    restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    activity.startActivity(restart);
                    activity.finish();
                    return;
                }

            }
            // Reset focus springs only when navigating to a different screen.
            // Toggling a setting must preserve the focused capsule's animation state.
            if (screen != screenBeforeAction) {
                for (Spring s : pop) s.snap(0);
            }
            invalidate();
            spoken.focusMoved();
        }
    }
}
