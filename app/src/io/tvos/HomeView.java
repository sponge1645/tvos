package io.tvos;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.text.InputType;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import java.util.LinkedHashMap;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf: 16:9 TV banners, up to 224 x 126, shrinking proportionally when more apps fit.
    // The glass bar has 40 of padding, tiles are 48 apart and focused tiles grow 20%.
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    /** Blur radii of the shadow and shadowDeep paints (Shadows needs them to draw the same blur on older TVs). */
    private static final float SHADOW_BLUR = 26, DEEP_BLUR = 30;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, SEARCH_GLASS = 8;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; all recommendation cards use a 5-column portrait grid.
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final float L_DATE_BASE = 330, L_CLOCK_BASE = 540, L_WEATHER_BASE = 626, L_QUOTES_BASE = 694;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final String[] TABS = {"Novidades", "Filmes", "Detetives", "Séries", "🎲"};
    private static final String[] TABS_RU = {"Новинки", "Фильмы", "Детективы", "Сериалы", "🎲"};
    private static final String[] TABS_EN = {"New", "Movies", "Detectives", "Shows", "🎲"};
    private static final String[] TABS_ES = {"Novedades", "Películas", "Detectives", "Series", "🎲"};
    private static final String[] TABS_DE = {"Neuheiten", "Filme", "Krimis", "Serien", "🎲"};
    private static final String[] TAB_KIND = {null, "movie", "detective", "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Spoken spoken = new Spoken(this, new Spoken.Source() {
        @Override public void items(List<Integer> out) { a11yItems(out); }
        @Override public String text(int id) { return a11yText(id); }
        @Override public boolean bounds(int id, Rect out) { return a11yBounds(id, out); }
        @Override public int focused() { return a11yFocused(); }
        @Override public void focus(int id) { a11yFocus(id); }
        @Override public void click(int id) { activate(); }
        @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }
    });
    // UI-thread drawing scratch: independent paints avoid carrying state between helpers.
    private final Paint headerDateScratch = new Paint();
    private final Paint headerWeatherScratch = new Paint();
    private final Paint searchHeadingScratch = new Paint();
    private final Paint.FontMetrics headerMetricsScratch = new Paint.FontMetrics();
    private final Paint.FontMetrics clockMetricsScratch = new Paint.FontMetrics();
    private final TextPaint ellipsizeScratch = new TextPaint();
    private final Paint rouletteTitleScratch = new Paint();
    private final Paint rouletteHintScratch = new Paint();
    private final Paint rouletteWinnerSheenScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rouletteWinnerRimScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rouletteEdgeScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marqueeMaskScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PorterDuffXfermode textEdgeMode = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
    private float rouletteEdgeLeft = Float.NaN, rouletteEdgeRight = Float.NaN;
    private final RectF ratingBadgeScratch = new RectF();
    private final RectF ratingInsetScratch = new RectF();
    private final RectF avatarShouldersScratch = new RectF();
    private final RectF rouletteHintOneScratch = new RectF();
    private final RectF rouletteHintTwoScratch = new RectF();
    private final RectF a11yRect = new RectF();
    // Reused only by drawRoulette on the UI thread; image helpers do not retain them.
    private final RectF roulettePosterRect = new RectF();
    private final RectF rouletteShadowRect = new RectF();
    private final RectF rouletteGhostRect = new RectF();
    private final Paint rouletteGhostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    { rouletteGhostPaint.setAlpha(25); }
    private final RectF rouletteButtonRect = new RectF();
    private final Paint rouletteTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, searchGlassLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private final RectF searchGlassBounds = new RectF();
    private boolean searchGlassVisible;
    private android.app.Dialog searchDialog;
    private int roulettePosterSlot = Integer.MIN_VALUE;
    private boolean searchMode;
    private final Spring searchHeadingOpacity = new Spring(0.42f, 1f, 0);
    private String searchQuery = "";
    private List<Recs.Pick> searchResults = new ArrayList<>();
    private int searchSerial;
    private int workEpoch;
    private UiRequest searchRequest, rouletteRequest;
    private Runnable pendingSearch;
    private boolean retrySearchOnResume;
    private final java.util.List<Recs.Pick> roulette = new java.util.ArrayList<>();
    private boolean rouletteLoading, rouletteSpinning, rouletteFinished;
    private long rouletteStarted;
    private int rouletteWinner;
    private final java.util.Random rouletteRandom = new java.util.Random();
    private final Runnable rouletteComplete = () -> {
        if (!this.running || tab != 4 || !rouletteSpinning) return;
        rouletteSpinning = false;
        rouletteFinished = true;
        // The winning poster becomes the focused, actionable card.
        zone = Z_CARDS;
        cardIndex = 0;
        retarget(false);
        heroDirty = true;
        wake();
    };

    private int widgetIndex;    // with TalkBack on, the drawer's weather (0) or quotes (1) card can take the focus
    private List<Recs.Pick> cards = new ArrayList<>();
    private int cardPage = 0; // five portrait posters per page
    private boolean largePostersWithoutWidgets = false;
    private int marqueeCard = -1, marqueePage = -1, marqueeTab = -1;
    private long marqueeSince;
    private boolean marqueeNeeded; // Set by drawing the focused title/metadata, using their actual available widths.
    private final Spring[] topFocus = new Spring[11];
    private final Spring rouletteButtonFocus = new Spring(0.26f, 0.85f, 0);
    private final RectF[] topRects = new RectF[11];
    private final Spring[] cardFocus = new Spring[8];
    private final RectF[] cardRects = new RectF[8];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private final NetworkIcon networkIcon = new NetworkIcon();
    private final NetworkStatus networkStatus;
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip, lDate, lClock, lWeather, lClockQuote;
    private Shader lightGradient;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("com.android.vending", new int[] {0xFF52ABEF, 0xFF2D6AD4, 0});
        TILE_STYLE.put("com.yandex.devices.appstore", new int[] {0xFF52ABEF, 0xFF2D6AD4, 0});
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFF1C1C1E, 0xFF09090A, 0});
        TILE_STYLE.put("io.gh.reisxd.tizentube.cobalt", new int[] {0xFFFFFFFF, 0xFFFFFFFF, 0});
        TILE_STYLE.put("watch.kino", new int[] {0xFFFFFFFF, 0xFFFFFFFF, 0});
        TILE_STYLE.put("com.seena.app", new int[] {0xFF007AFF, 0xFF007AFF, 0});
        TILE_STYLE.put("top.rootu.lampa", new int[] {0xFF313130, 0xFF161613, 0});
        TILE_STYLE.put("tv-settings", new int[] {0xFFA5A6AB, 0xFF656368, 0});
        TILE_STYLE.put("com.applemusicktv", new int[] {0xFFEB576D, 0xFFEA333C, 0});
        TILE_STYLE.put("com.android.tv.settings", new int[] {0xFFA5A6AB, 0xFF656368, 0});
        TILE_STYLE.put("io.github.jqssun.airplay", new int[] {0xFFF79E1F, 0xFFFF6933, 0});
        TILE_STYLE.put("llc.itdev.incy", new int[] {0xFF024824, 0xFF0B1B11, 0});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    // Lightweight Dock editing: hold OK, move with left/right, OK saves, Back restores.
    private static final long DOCK_HOLD_MS = 500L;
    private boolean dockEditing, dockHoldArmed, dockDropArmed;
    private java.util.List<HomeApps.Item> dockBefore;
    private Spring[] dockSlide = new Spring[0];
    private final Spring dockLift = new Spring(0.3f, 0.72f, 0);
    private int dockLiftIndex = -1;
    private long dockWiggleAt;
    private boolean dockAnimating;
    private static final long DOCK_WIGGLE_MS = 300L;

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "", weatherLine = "";
    private long weatherLineAt = -1;
    private int weatherLinePrefs = -1;
    private String weatherLineLanguage = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;
    private void playDockEditClick() { click.open(); }

    private final Runnable dockHold = () -> {
        if (dockHoldArmed && okDown && !sheetOpen && zone == Z_SHELF && !shelf.isEmpty()) {
            dockHoldArmed = false;
            dockEditing = true;
            dockDropArmed = false;
            dockBefore = new java.util.ArrayList<>(shelf);
            dockLiftIndex = shelfIndex;
            dockWiggleAt = SystemClock.uptimeMillis();
            dockLift.target = 1;
            dockAnimating = true;
            playDockEditClick();
            showToast("← → – переместить. ОК – сохранить. Назад – отменить.");
            tilesDirty = true;
            wake();
        }
    };

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final ActionSurface actionSurface = new ActionSurface(scene, shadows);
    private final ProfileAvatar profileAvatar = new ProfileAvatar(getContext());
    private int profileAvatarIndex = -1;
    // BitmapShader retains its Bitmap. A strong value would keep this WeakHashMap's key alive.
    private final Map<Bitmap, java.lang.ref.WeakReference<BitmapShader>> imageShaders = new WeakHashMap<>();
    private final Matrix imageMatrix = new Matrix();
    private final Paint imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dockHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path dockHighlightPath = new Path();
    private final RectF dockHighlightArc = new RectF();
    private float dockHighlightW = -1, dockHighlightH = -1, dockHighlightRadius = -1, dockHighlightDensity = -1;
    // Reused only on the UI drawing thread to avoid Paint allocations per frame.
    private final Paint ratingTextScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ratingFillScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ratingSheenScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ratingRimScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockCapsuleScratch = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final TextPaint fitPaint = new TextPaint();
    private final RectF gridCell = new RectF();
    private LinearGradient[] tileShaders = new LinearGradient[0];
    private LinearGradient tileGlow, cardSheen;
    private float tileShaderH, cardSheenH;
    private LinearGradient[] lightSheens = new LinearGradient[0], lightRims = new LinearGradient[0];
    private float[] lightShaderW = new float[0];
    private android.graphics.ColorFilter veilFilter;
    private float veilAmount = -1;
    private int veilColor;
    private HomeData.Weather textWeather;
    private long textPricesAt = -1, textMinute = -1;
    private int textPrefs = -1;
    private String textLanguage;
    private String weatherTitle, nowTemp, highLow, sunriseText, sunsetText, staleText;
    private final String[] hourText = new String[6], hourTempText = new String[6];
    private final String[] priceText = new String[HomeData.COINS.length], changeText = new String[HomeData.COINS.length];

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                case SEARCH_GLASS: drawSearchGlass(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final Runnable reloadSoon = this::reload;

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reloadSoon(); }
        @Override public void onInputAdded(String inputId) { reloadSoon(); }
        @Override public void onInputRemoved(String inputId) { reloadSoon(); }
    };

    private void reloadSoon() {
        handler.removeCallbacks(reloadSoon);
        handler.postDelayed(reloadSoon, 500);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            wake();
            data.refreshIfStale();
            recs.refreshIfStale();
            SelfUpdate.get(getContext()).maybeAutomatic(false);
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        searchGlassLayer = new Layer(a, SEARCH_GLASS);
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, searchGlassLayer}) {
            v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // TalkBack reads the items (Spoken)
            addView(v);
        }
        setContentDescription("tvOS");
        searchGlassLayer.setVisibility(INVISIBLE);
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < topFocus.length; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < cardFocus.length; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            cardRects[i] = new RectF();
        }
        networkStatus = new NetworkStatus(a, () -> {
            statusDirty = heroDirty = true;
            status.invalidate();
            spoken.readAgain();
            wake();
        });
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        int nextAvatar = ProfileAvatar.selected(getContext());
        if (nextAvatar != profileAvatarIndex) profileAvatar.clear();
        profileAvatarIndex = nextAvatar;
        if (running) return;
        running = true;
        if (topButtonEnabled(5)) networkStatus.start();
        checkNight(false);
        syncVisibleTabs();
        // SettingsActivity runs separately; the old button rectangles remain cached.
        // Repack the visible top actions immediately after returning from settings.
        layoutLight();
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
        if (tab == 4 && tabEnabled(4) && roulette.isEmpty() && !rouletteLoading) loadRoulette();
        if (retrySearchOnResume && searchMode) requestSearch(searchQuery, ++searchSerial);
        retrySearchOnResume = false;
    }

    void pause() {
        if (!running) return;
        running = false;
        networkStatus.stop();
        workEpoch++;
        retrySearchOnResume = searchMode && (searchRequest != null || pendingSearch != null);
        searchSerial++;
        cancelSearchWork();
        cancelRouletteWork();
        handler.removeCallbacks(dockHold);
        dockHoldArmed = false;
        okDown = false;
        if (dockEditing) finishDockEdit(false);
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        recs.setPosterWindow(java.util.Collections.emptyList());
        roulettePosterSlot = Integer.MIN_VALUE;
        imageShaders.clear();
        crispLogos.clear();
        shadows.clear();
        weatherSnapshot.clear();
        quotesSnapshot.clear();
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        if (searchDialog != null) searchDialog.dismiss();
        click.release();
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        wake();
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
        } else {
            sheet.snap(0);
            drawer.snap(0);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (searchMode) { cancelSearchWork(); searchMode = false; searchSerial++; searchResults.clear(); }
        if (Prefs.firstCategory() >= 0 && tab != Prefs.firstCategory()) selectTab(Prefs.firstCategory());
        updateCards();
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "search", Ui.ru ? "Поиск" : "Search", "search"));
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV"), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "vpn", "VPN", "vpn"));
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Configurações", "Settings"), "settings"));
    }

    private boolean topButtonEnabled(int index) { return Prefs.topButtonEnabled(index); }
    private boolean clockButtonEnabled() { return topButtonEnabled(4); }

    private boolean topControlVisible(int id) {
        return id < 5 ? !searchMode && picksOn() && tabEnabled(id) : topButtonEnabled(id - 5);
    }

    /** Tabs and actions follow their saved visual order, independently of stable IDs. */
    private int nextTopControl(int current, int direction) {
        int previous = -1;
        int[] categories = Prefs.categoryOrder(), buttons = Prefs.topButtonOrder();
        for (int position = 0; position < categories.length + buttons.length; position++) {
            int id = position < categories.length ? categories[position] : 5 + buttons[position - categories.length];
            if (!topControlVisible(id)) continue;
            if (id == current && direction < 0) return previous < 0 ? current : previous;
            if (previous == current && direction > 0) return id;
            previous = id;
        }
        return current;
    }

    private int availableTopButton(int preferred) {
        if (preferred < 5 || (preferred <= 10 && topButtonEnabled(preferred - 5))) return preferred;
        for (int id : Prefs.topButtonOrder()) if (topButtonEnabled(id)) return 5 + id;
        return picksOn() ? tab : 0;
    }

    /** Poster shortcuts use the first/last visible action in the saved layout. */
    private int posterCornerTarget(boolean rightmost) {
        int first = -1, last = -1;
        for (int id : Prefs.topButtonOrder()) if (topButtonEnabled(id)) {
            if (first < 0) first = 5 + id;
            last = 5 + id;
        }
        return last >= 0 ? (rightmost ? last : first) : picksOn() ? tab : 0;
    }

    private static boolean tabEnabled(int i) {
        return Prefs.categoryEnabled(i);
    }

    private void syncVisibleTabs() {
        int first = Prefs.firstCategory();
        if (!tabEnabled(tab) && first >= 0) selectTab(first);
        if (first < 0) cancelRouletteWork();
        if (topIndex < 5 && !tabEnabled(topIndex)) topIndex = first >= 0 ? tab : availableTopButton(5);
        if (first < 0 && zone == Z_CARDS) zone = Z_SHELF;
        if (topIndex >= 5 && !topButtonEnabled(topIndex - 5)) topIndex = availableTopButton(5);
        if (zone == Z_TOP && !topControlVisible(topIndex)) zone = Z_SHELF;
    }

    private static String[] tabs() {
        return Ui.ru ? TABS_RU : Ui.es ? TABS_ES : Ui.de ? TABS_DE : Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    @Override public void onRecs() {
        wake();
        updatePicks();
    }

    private void invalidateAll() {
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(790 * kk));
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        measure(searchGlassLayer, width, height);
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        searchGlassLayer.layout(0, 0, width, height);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.displaySemibold, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        for (Paint p : new Paint[] {labelPaint, titlePaint, metaPaint, clockPaint}) p.setShadowLayer(22 * k, 0, 2 * k, 0x59000000);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        lClock = Ui.numbers(Ui.light, 200 * k, L_INK);
        lClock.setLetterSpacing(-0.01f);
        lWeather = Ui.text(Ui.medium, 34 * k, L_DIM);
        lClockQuote = Ui.numbers(Ui.medium, 28 * k, L_DIM);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        tipPaint.setShadowLayer(16 * k, 0, 1 * k, 0x99000000);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        quotePaint.setShadowLayer(14 * k, 0, 1 * k, 0x99000000);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 31.2f * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        shadowSoft.setMaskFilter(new BlurMaskFilter(12 * k, BlurMaskFilter.Blur.NORMAL));
        shadowDeep.setMaskFilter(new BlurMaskFilter(DEEP_BLUR * k, BlurMaskFilter.Blur.NORMAL));
        halo.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();
        // Re-layout only when a top-panel widget is enabled or disabled.
        boolean wantLargePosters = noTopWidgets();
        if (largePostersWithoutWidgets != wantLargePosters) {
            layoutLight();
            heroDirty = true;
        }

        // Restart the title marquee whenever focus moves to a different poster.
        int focusedPoster = !sheetOpen && picksOn() && tab != 4 && zone == Z_CARDS
                && cardIndex < cards.size() ? cardIndex : -1;
        if (focusedPoster != marqueeCard || cardPage != marqueePage || tab != marqueeTab) {
            marqueeCard = focusedPoster;
            marqueePage = cardPage;
            marqueeTab = tab;
            marqueeSince = up;
            marqueeNeeded = false;
            heroDirty = true;
        }
        boolean marqueeActive = focusedPoster >= 0 && marqueeNeeded && up - marqueeSince >= 1000;
        if (marqueeActive || rouletteSpinning) heroDirty = true;
        boolean dockMoving = step(dockSlide, dt) | dockLift.step(dt);
        if (dockEditing || dockLift.value > 0.001f) dockMoving = true;
        dockAnimating = dockMoving;
        if (!dockEditing && !dockMoving) dockLiftIndex = -1;
        if (dockMoving) tilesDirty = true;
        boolean tilesMoving = step(tileFocus, dt) | dockMoving;
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean searchHeadingMoving = tickSearchHeading(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt) | rouletteButtonFocus.step(dt);
        if (topMoving) status.invalidate();
        heroMoving |= topMoving | cardsMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        if (drawerMoving || sheetMoving || motionDirty) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading || searchHeadingMoving) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) sky.invalidate();
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (hero != null && hero.clock) {
                refreshHero(false);
                heroLayer.invalidate();
            }
            heroLayer.invalidate(); // the header date/time changes even when recommendations are enabled
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving || searchHeadingMoving
                || toast != null || !toasts.isEmpty() || rouletteSpinning || dockMoving;
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else {
            // Short text needs only the normal clock tick. Long text wakes at its dwell deadline,
            // then keeps the existing 50 ms marquee cadence without polling during the wait.
            long delay = marqueeActive ? 50L : focusedPoster >= 0 && marqueeNeeded
                    ? Math.max(1L, 1000L - (up - marqueeSince)) : 1000L;
            Choreographer.getInstance().postFrameCallbackDelayed(this, delay);
        }
    }

    private void wake() {
        if (!running || !idle) return;
        idle = false;
        lastNanos = 0;
        Choreographer.getInstance().removeFrameCallback(this);
        Choreographer.getInstance().postFrameCallback(this);
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float home = 1 - s, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float lift = -d * shift;
        show(heroLayer, top, lift);
        show(status, top, lift);
        show(platter, home, lift);
        show(tiles, home, lift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.locale());
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = true;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next) != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        spoken.focusMoved();
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();

        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        platter.invalidate();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return night;
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
        lClock.setColor(tone(L_INK, WHITE));
        lWeather.setColor(tone(L_DIM, 0xFF98989D));
        lClockQuote.setColor(tone(L_DIM, 0xFF98989D));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            if (drawerLayer.getVisibility() == VISIBLE || toast != null) {
                int[] from = frostOf(bgFrom), to = frostOf(bgTo);
                for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
                scene.setFrost(frostMix);
            }
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        amount = Math.round(amount * 255) / 255f;
        if (amount == veilAmount && color == veilColor) return veilFilter;
        veilAmount = amount;
        veilColor = color;
        float keep = 1 - amount;
        return veilFilter = new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean weatherVisible(boolean top) {
        if (!Prefs.weather()) return false;
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        String key = top ? "weather_show_top" : "weather_show_bottom";
        if (prefs.contains(key)) return prefs.getBoolean(key, true);
        int old = prefs.getInt("weather_placement", 3);
        return top ? (old == 1 || old == 3) : (old == 2 || old == 3);
    }
    private boolean weatherTop() { return weatherVisible(true); }
    private boolean weatherBottom() { return weatherVisible(false); }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp) && weatherTop()) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        tileShaders = new LinearGradient[0];
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        if (dockEditing) return; // Do not overwrite a drag with an asynchronous refresh.
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            dockSlide = new Spring[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                dockSlide[i] = new Spring(0.3f, 0.72f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k;
        float full = (CONTENT_R - MARGIN) * k;
        // Match Free My TV: always 16:9, at most 224 x 126, and bottom-aligned when shrinking.
        float tw = Math.min(L_TILE_H * 16f / 9 * k,
                (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float th = tw * 9f / 16;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = (L_PLATTER_TOP + L_TILE_H) * k - th;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty()) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        tmp.set(platterRect);
        tmp.offset(0, 18 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** The quotes row's text and sizes, for one set of coins, prices and text size. */
    private final class Quotes {
        final int[] coins;
        final double[] prices;
        final float size, cap, halfCap;
        final String[] values;
        final float[] glyphs, widths;

        Quotes(int[] coins, double[] all, Paint text) {
            this.coins = coins;
            size = text.getTextSize();
            cap = Ui.cap(text);
            halfCap = Ui.halfCap(text);
            int n = coins.length;
            prices = new double[n];
            values = new String[n];
            glyphs = new float[n];
            widths = new float[n];
            for (int i = 0; i < n; i++) {
                prices[i] = all[coins[i]];
                values[i] = HomeData.compact(prices[i]);
                glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[coins[i]], cap);
                widths[i] = text.measureText(values[i]);
            }
        }

        boolean matches(int[] now, double[] all, float textSize) {
            if (now != coins || textSize != size) return false;
            for (int i = 0; i < coins.length; i++) if (Double.compare(all[coins[i]], prices[i]) != 0) return false;
            return true;
        }
    }

    private Quotes quotes;

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        boolean clock = !picksOn();
        float right = platterRect.isEmpty() ? CONTENT_R * k : platterRect.right - SHELF_PAD * k;
        applyTone();
        Paint text = clock ? lClockQuote : lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 5; i < 9 && !clock; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (!clock) fade = Math.min(fade, 1 - Ui.clamp(topFocus[10].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        int n = HomeData.coinCount();
        if (n == 0) { text.setColor(textColor); return; }
        // Measured once per set of prices: this row is redrawn on every frame of the cards' focus animation, and
        // measuring text there cost ~3 ms a frame on a Mi Box 3.
        int[] coins = HomeData.active();
        Quotes q = quotes;
        if (q == null || !q.matches(coins, data.prices, text.getTextSize())) {
            q = quotes = new Quotes(coins, data.prices, text);
        }
        // On the tab row, at the right.
        float base = clock ? L_QUOTES_BASE * k : (L_TABBAR_TOP + L_TABBAR_H / 2) * k + q.halfCap;
        float cap = q.cap, gap = (clock ? 8 : 6) * k, spacing = (clock ? 32 : 24) * k;
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) total += q.glyphs[i] + gap + q.widths[i];
        float x = clock ? MARGIN * k : right - total;
        int coinColor = clock ? text.getColor() : Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade);
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[coins[i]], x + q.glyphs[i] / 2, base - cap / 2, cap, coinColor);
            x += q.glyphs[i] + gap;
            c.drawText(q.values[i], x, base, text);
            x += q.widths[i] + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (stale.isEmpty()) return;
        if (clock) c.drawText(stale, x - spacing + 24 * k + staleQuote.measureText(stale), base, staleQuote);
        else c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        if (dockLiftIndex >= 0 && dockLiftIndex < shelf.size() && dockLift.value > 0.02f) top = dockLiftIndex;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                float dx = i < dockSlide.length ? dockSlide[i].value : 0;
                float angle = i == dockLiftIndex ? (float)Math.sin((SystemClock.uptimeMillis() - dockWiggleAt) * 2 * Math.PI / DOCK_WIGGLE_MS) * 1.5f * dockLift.value : 0;
                c.save();
                if (dx != 0) c.translate(dx, 0);
                if (angle != 0) c.rotate(angle, tileRects[i].centerX(), tileRects[i].centerY());
                drawShelfTile(c, i);
                c.restore();
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. A fine upper-edge highlight remains visible at rest and brightens gently with focus.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        boolean lifted = i == dockLiftIndex && dockLift.value > 0.001f;
        if (lifted) {
            RectF b = tileRects[i];
            c.save();
            float scale = 1 + 0.08f * dockLift.value;
            c.scale(scale, scale, b.centerX(), b.centerY());
        }
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        // Shadow at the resting icon size, enlarged by the transform (so the blur stays cached): short at rest, deep
        // and lower when focused.
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (4 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        int[] style = TILE_STYLE.get(it.key);
        if (it.logo != null && style != null) {
            if (tileShaders.length != shelf.size() || tileShaderH != bh) {
                tileShaders = new LinearGradient[shelf.size()];
                tileShaderH = bh;
                tileGlow = new LinearGradient(0, -bh / 2, 0, 0, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
            }
            if (tileShaders[i] == null) {
                tileShaders[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, style[0], style[1], Shader.TileMode.CLAMP);
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            tileFill.setShader(tileShaders[i]);
            tileFill.setAlpha(255);
            c.drawRoundRect(tmp, r0, r0, tileFill);
            if (fc > 0.01f) {
                tileFill.setShader(tileGlow);
                tileFill.setAlpha(Math.round(255 * fc));
                c.drawRoundRect(tmp, r0, r0, tileFill);
                tileFill.setAlpha(255);
            }
            tileFill.setShader(null);
            c.restore();
            drawLogo(c, it, cell, s, style[2]);
        } else if (it.logo != null) {
            tileFill.setShader(null);
            tileFill.setColor(0xFF161618);
            c.drawRoundRect(cell, r, r, tileFill);
            drawLogo(c, it, cell, s, 0);
        } else {
            if (it.tile != null) roundImage(c, it.tile, cell, r);
        }
        if (it.logo != null || it.tile != null) {
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            drawDockHighlight(c, bw, bh, r0, fc);
            c.restore();
        }
        if (lifted) c.restore();
    }

    /** Cached upper contour: ordinary Canvas paths/gradients work on Android 9+, without offscreen layers. */
    private void drawDockHighlight(Canvas c, float width, float height, float radius, float focus) {
        if (dockHighlightW != width || dockHighlightH != height
                || dockHighlightRadius != radius || dockHighlightDensity != k) {
            dockHighlightW = width;
            dockHighlightH = height;
            dockHighlightRadius = radius;
            dockHighlightDensity = k;
            // Keep both strokes inside the tile. Follow only the upper corners, with no bottom rim.
            float inset = 1.5f * k;
            float left = -width / 2f + inset, right = width / 2f - inset;
            float top = -height / 2f + inset;
            float r = Math.max(0, Math.min(radius - inset, Math.min(width, height) / 2f - inset));
            dockHighlightPath.rewind();
            dockHighlightPath.moveTo(left, top + r);
            dockHighlightArc.set(left, top, left + 2 * r, top + 2 * r);
            dockHighlightPath.arcTo(dockHighlightArc, 180, 90);
            dockHighlightPath.lineTo(right - r, top);
            dockHighlightArc.set(right - 2 * r, top, right, top + 2 * r);
            dockHighlightPath.arcTo(dockHighlightArc, 270, 90);
            dockHighlightPaint.setStyle(Paint.Style.STROKE);
            dockHighlightPaint.setStrokeCap(Paint.Cap.ROUND);
            dockHighlightPaint.setShader(new LinearGradient(left, 0, right, 0,
                    new int[] {0x00FFFFFF, 0x70FFFFFF, 0xB8FFFFFF, 0x70FFFFFF, 0x00FFFFFF},
                    new float[] {0f, 0.2f, 0.5f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        }
        // Broad, faint shoulder under a fine bright edge; changing focus needs no new shader or bitmap.
        float strength = 0.72f + 0.28f * Ui.clamp(focus);
        dockHighlightPaint.setStrokeWidth(2.8f * k);
        dockHighlightPaint.setAlpha(Math.round(48 * strength));
        c.drawPath(dockHighlightPath, dockHighlightPaint);
        dockHighlightPaint.setStrokeWidth(1.1f * k);
        dockHighlightPaint.setAlpha(Math.round(255 * strength));
        c.drawPath(dockHighlightPath, dockHighlightPaint);
    }

    /**
     * `image` stretched over `cell`, with corners of radius `r`. Android 12+ clips to the rounded shape; on Android 9 to
     * 11 that anti-aliased clip costs the GPU a mask on every frame of a focus animation (measured on a Mi Box 3: most of
     * a 49 ms frame), so there the image is the shader of a rounded rectangle instead, which looks the same.
     */
    private void roundImage(Canvas c, Bitmap image, RectF cell, float r) {
        if (!Ui.LEGACY) {
            clip.reset();
            clip.addRoundRect(cell, r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(image, null, cell, bitmapPaint);
            c.restore();
            return;
        }
        java.lang.ref.WeakReference<BitmapShader> ref = imageShaders.get(image);
        BitmapShader shader = ref == null ? null : ref.get();
        if (shader == null) {
            shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            imageShaders.put(image, new java.lang.ref.WeakReference<>(shader));
        }
        imageMatrix.setScale(cell.width() / image.getWidth(), cell.height() / image.getHeight());
        imageMatrix.postTranslate(cell.left, cell.top);
        shader.setLocalMatrix(imageMatrix);
        imageFill.setShader(shader);
        c.drawRoundRect(cell, r, r, imageFill);
        imageFill.setShader(null);
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and filtered down once for the focused tile. Keep fractional coordinates throughout
     * the spring so settling never switches the logo to a different pixel grid.
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null);
        if (logo == null) return;
        float scale = s / (1 + FOCUS_GROW);
        // Dock logo adjustments relative to v18: KinoPub +10%, TV Settings -10%, TizenTube +5%.
        float brandBoost = ("com.android.vending".equals(it.pkg) || "com.yandex.devices.appstore".equals(it.pkg)) ? 1.2f
                : "com.android.tv.settings".equals(it.pkg) ? 1.323f
                : "com.applemusicktv".equals(it.pkg) ? 1.25f
                : "watch.kino".equals(it.key) ? 1.32f
                : ("tv-settings".equals(it.key) ? 1.512f
                : ("io.gh.reisxd.tizentube.cobalt".equals(it.key) ? 1.05f : 1f));
        float dw = logo.getWidth() * scale * brandBoost, dh = logo.getHeight() * scale * brandBoost;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        // Never snap only at the end: that shifts the logo relative to its continuously scaled tile.
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width());
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
        }
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.585f;
        boolean wx = weatherBottom(), qt = Prefs.quotes() && HomeData.active().length > 0;
        // A card that is off gives its room to the other one.
        weatherCard.set(left, cardsTop, qt ? split - 16 * k : right, cardsTop + CARDS_H * k);
        quotesCard.set(wx ? split + 16 * k : left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            tmp.set(r);
            tmp.offset(0, drop);
            shadow.setColor(Ui.alpha(shadowColor, 1 - dk));
            shadows.draw(c, tmp, radius, SHADOW_BLUR * k, shadow);
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
        cardText.setColor(drawerInk(0.95f));
        cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
        cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
        bigTemp.setColor(drawerInk(1));
        hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
        hourTemp.setColor(drawerInk(0.95f));
        pricePaint.setColor(drawerInk(0.95f));
        float titleBase = ROW_TITLE * k;
        if (drawerInputs > 0) c.drawText(Ui.tr("Entradas", "Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
        if (drawerInputs < drawerItems.size()) c.drawText(Ui.tr("Atalhos", "Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
        if (weatherBottom()) drawWeatherCard(c, weatherCard);
        if (Prefs.quotes() && HomeData.active().length > 0) drawQuotesCard(c, quotesCard);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = 20 * k * s;
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 12 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.45f * f));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (f < 0.99f) drawerGlass(c, cell, r, 0.7f, 0x14000000, 6 * k);
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        Symbols.draw(getContext(), c, it.symbol, cx, cell.top + cell.height() * 0.4f, 37.4f * k * s, ink);
        drawerLabel.setColor(ink);
        drawerLabel.setTextSize(23 * k * s);
        float room = cell.width() - 16 * k, wide = drawerLabel.measureText(it.label);
        // A long name ("Home Settings", an input you renamed) shrinks a little before it gets cut.
        if (wide > room) drawerLabel.setTextSize(Math.max(18 * k * s, 23 * k * s * room / wide));
        fitPaint.set(drawerLabel);
        String label = TextUtils.ellipsize(it.label, fitPaint, room, TextUtils.TruncateAt.END).toString();
        c.drawText(label, cx, cell.top + cell.height() * 0.8f, drawerLabel);
        drawerLabel.setTextSize(23 * k);
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) weatherSnapshot.draw(c, r, drawerCardsKey(), cc -> drawWeather(cc, r));
        else drawWeather(c, r);
    }

    private void drawWeather(Canvas c, RectF r) {
        refreshDrawerText();
        float x = r.left + 34 * k;
        c.drawText(weatherTitle, x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = textWeather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        c.drawText(nowTemp, x + sw + 18 * k - Ui.leftBearing(bigTemp, nowTemp), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(highLow, x, r.top + 258 * k, cardDim);
        float split = r.left + 392 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 30 * k, hw = (r.right - 30 * k - hx) / 6;
        for (int i = 0; i < wx.hours.size() && i < 6; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(hourText[i], hc, r.top + 54 * k, hourPaint);
            String hourSymbol = Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i));
            Bitmap hourIcon = Symbols.get(getContext(), hourSymbol);
            float iconSize = 36 * k;
            float iconHeight = hourIcon == null ? 0 : Symbols.width(getContext(), hourSymbol, iconSize)
                    * hourIcon.getHeight() / hourIcon.getWidth();
            // Center the combined icon + temperature on the card, independently of the hour heading.
            float iconY = r.centerY() - (54 * k - iconHeight / 2) / 2;
            float temperatureBase = hourIcon == null ? r.centerY() + Ui.halfCap(hourTemp) : iconY + 54 * k;
            Symbols.draw(getContext(), c, hourSymbol, hc, iconY, iconSize, drawerInk(0.9f));
            c.drawText(hourTempText[i], hc, temperatureBase, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", sunriseText, sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", sunsetText, sx, base);
    }

    private void refreshDrawerText() {
        HomeData.Weather wx = data.weather;
        boolean settings = Prefs.version != textPrefs || !Ui.language.equals(textLanguage);
        textPrefs = Prefs.version;
        textLanguage = Ui.language;
        if (wx != textWeather || settings) {
            textWeather = wx;
            weatherTitle = Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city();
            nowTemp = HomeData.degrees(wx.temp);
            highLow = Ui.ru ? "Макс. " + HomeData.degrees(wx.max) + "   Мин. " + HomeData.degrees(wx.min)
                    : Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                    : Ui.de ? "H:" + HomeData.degrees(wx.max) + "   T:" + HomeData.degrees(wx.min)
                    : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min);
            for (int i = 0; i < wx.hours.size() && i < 6; i++) {
                hourText[i] = HomeData.hour(wx.hours.get(i));
                hourTempText[i] = HomeData.degrees(wx.hourTemp.get(i));
            }
            sunriseText = Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise);
            sunsetText = Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset);
        }
        long minute = System.currentTimeMillis() / 60_000;
        if (data.pricesAt != textPricesAt || minute != textMinute || settings) {
            textPricesAt = data.pricesAt;
            textMinute = minute;
            String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
            staleText = stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale;
            for (int i = 0; i < HomeData.COINS.length; i++) {
                priceText[i] = HomeData.brl(data.prices[i]);
                changeText[i] = HomeData.change(data.changes[i]);
            }
        }
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        if (Ui.LEGACY) quotesSnapshot.draw(c, r, drawerCardsKey(), cc -> drawPrices(cc, r));
        else drawPrices(c, r);
    }

    /**
     * What the drawer cards show depends on: the data, the settings, the language, day or night, and the minute (the
     * sunrise and sunset, "12 min ago").
     */
    private String drawerCardsKey() {
        return data.weatherAt + "|" + data.pricesAt + "|" + Prefs.version + "|" + Ui.language + "|"
                + Float.floatToIntBits(dark()) + "|" + System.currentTimeMillis() / 60_000;
    }

    /**
     * Android 9 to 11: a drawer card's content (its text and symbols, not its glass) kept as a bitmap. The drawer is
     * redrawn on every frame of its focus animation, and laying that text out again took ~15 ms a frame on a Mi Box 3.
     */
    private static final class Snapshot {
        private Bitmap bitmap;
        private String key = "";

        void clear() { bitmap = null; key = ""; }

        void draw(Canvas c, RectF r, String key, Consumer<Canvas> content) {
            int left = Math.round(r.left), top = Math.round(r.top);
            int w = Math.max(1, Math.round(r.right) - left), h = Math.max(1, Math.round(r.bottom) - top);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                this.key = "";
            }
            if (!key.equals(this.key)) {
                bitmap.eraseColor(0);
                Canvas cc = new Canvas(bitmap);
                cc.translate(-left, -top);
                content.accept(cc);
                this.key = key;
            }
            c.drawBitmap(bitmap, left, top, null);
        }
    }

    private final Snapshot weatherSnapshot = new Snapshot(), quotesSnapshot = new Snapshot();

    private void drawPrices(Canvas c, RectF r) {
        float x = r.left + 34 * k, right = r.right - 34 * k;
        refreshDrawerText();
        c.drawText(Ui.tr("Cotações", "Crypto"), x, r.top + 54 * k, cardTitle);
        c.drawText(staleText, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        int[] coins = HomeData.active();
        for (int j = 0; j < coins.length; j++) {
            int i = coins[j];
            float base = r.top + (110 + 52 * j) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.coinName(i), x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(priceText[i], right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(changeText[i], tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        float rouletteTarget = !sheetOpen && tab == 4 && zone == Z_CARDS ? 1 : 0;
        heroChanged |= rouletteButtonFocus.target != rouletteTarget;
        rouletteButtonFocus.target = rouletteTarget;
        if (snap) rouletteButtonFocus.snap(rouletteTarget);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        statusChanged |= topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        spoken.focusMoved();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        spoken.focusMoved();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("Todos os Apps", "All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            gridCell.set(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, gridCell, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], gridCell.centerX(), gridCell.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            tmp.set(cell);
            tmp.offset(0, 16 * k * f);
            shadow.setColor(Ui.alpha(0xFF000000, 0.55f * fc));
            shadows.draw(c, tmp, r, SHADOW_BLUR * k, shadow);
        }
        if (image != null) roundImage(c, image, cell, r);
        if (f > 0.01f) {
            float bh = base.height();
            if (cardSheen == null || cardSheenH != bh) {
                cardSheen = new LinearGradient(0, -bh / 2, 0, 0, 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                cardSheenH = bh;
            }
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-base.width() / 2, -bh / 2, base.width() / 2, bh / 2);
            sheen.setShader(cardSheen);
            sheen.setAlpha(Math.round(255 * fc));
            c.drawRoundRect(tmp, radius, radius, sheen);
            sheen.setAlpha(255);
            c.restore();
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        announceForAccessibility(text);
        wake();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final int epoch = workEpoch;
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                if (!running || epoch != workEpoch) return;
                setShelf(s);
                setStatusItems(st);
                setDrawerItems(d);
                if (!sheetOpen) setOthers(o);
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        wake();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (dockEditing) return dockEditKey(e, down);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) {
                        okDown = true;
                        dockHoldArmed = !dockEditing && !sheetOpen && zone == Z_SHELF && !shelf.isEmpty();
                        if (dockHoldArmed) handler.postDelayed(dockHold, DOCK_HOLD_MS);
                    }
                } else {
                    handler.removeCallbacks(dockHold);
                    dockHoldArmed = false;
                    if (okDown && !e.isCanceled()) {
                        if (dockEditing) {
                            // The initial long press releases without saving; a second OK drops the tile.
                            if (dockDropArmed) finishDockEdit(true);
                            else dockDropArmed = true;
                        } else if (spoken.keyClick()) activate();
                    }
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private boolean dockEditKey(KeyEvent e, boolean down) {
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shiftDock(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down) {
                    if (!dockDropArmed) dockDropArmed = true; // release of initial hold
                    okDown = false;
                } else if (dockDropArmed && e.getRepeatCount() == 0) {
                    okDown = false;
                    finishDockEdit(true);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) { finishDockEdit(false); click.move(); }
                    backDown = false;
                }
                return true;
            default: return false;
        }
    }

    private void shiftDock(int dx) {
        int i = shelfIndex, j = i + dx;
        if (j < 0 || j >= shelf.size()) return;
        HomeApps.Item item = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, item);
        Spring f = tileFocus[i]; tileFocus[i] = tileFocus[j]; tileFocus[j] = f;
        Spring slide = dockSlide[i]; dockSlide[i] = dockSlide[j]; dockSlide[j] = slide;
        float d = tileRects[i].left - tileRects[j].left;
        dockSlide[j].value += d;
        dockSlide[i].value -= d;
        if (tileShaders.length == shelf.size()) {
            LinearGradient g = tileShaders[i]; tileShaders[i] = tileShaders[j]; tileShaders[j] = g;
        }
        shelfIndex = dockLiftIndex = j;
        retarget(false);
        tilesDirty = true;
        wake();
        click.move();
    }

    private void finishDockEdit(boolean save) {
        if (!dockEditing) return;
        dockEditing = false;
        dockLift.target = 0;
        if (!save && dockBefore != null && dockBefore.size() == shelf.size()) {
            HomeApps.Item selected = shelf.get(shelfIndex);
            int n = shelf.size();
            Spring[] focus = new Spring[n], slide = new Spring[n];
            LinearGradient[] shaders = tileShaders.length == n ? new LinearGradient[n] : tileShaders;
            for (int a = 0; a < n; a++) {
                int b = shelf.indexOf(dockBefore.get(a));
                if (b < 0) { b = a; }
                focus[a] = tileFocus[b];
                slide[a] = dockSlide[b];
                slide[a].value += tileRects[b].left - tileRects[a].left;
                if (shaders != tileShaders) shaders[a] = tileShaders[b];
            }
            shelf = dockBefore;
            tileFocus = focus;
            dockSlide = slide;
            tileShaders = shaders;
            shelfIndex = dockLiftIndex = shelf.indexOf(selected);
            if (shelfIndex < 0) shelfIndex = dockLiftIndex = 0;
        }
        if (save) {
            java.util.List<String> packages = new java.util.ArrayList<>();
            for (HomeApps.Item item : shelf) {
                if ("tv-settings".equals(item.key)) packages.add("io.tvos.virtual.tvsettings");
                else if (item.pkg != null) packages.add(item.pkg);
            }
            Prefs.setShelf(packages);
            click.open();
        }
        dockDropArmed = false;
        dockBefore = null;
        retarget(false);
        tilesDirty = true;
        wake();
    }

    private void back() {
        if (rouletteSpinning) cancelRouletteWork();
        if (dockEditing) { finishDockEdit(false); click.move(); return; }
        if (sheetOpen) {
            closeSheet();
        } else if (searchMode) {
            closeSearch();
        } else if (zone == Z_DRAWER || zone == WIDGETS) {
            closeDrawer();
            retarget(false);
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (rouletteSpinning) return; // Keep remote focus on the running roulette until it stops.
        if (dockEditing) { if (dx != 0) shiftDock(dx); return; }
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dockEditing) {
                    if (dx == 0) return;
                    int to = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (to == shelfIndex) return;
                    HomeApps.Item moved = shelf.remove(shelfIndex);
                    shelf.add(to, moved);
                    shelfIndex = to;
                    tileShaders = new LinearGradient[0];
                    tilesDirty = true;
                    platter.invalidate();
                    click.move();
                    return;
                }
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            case WIDGETS: {
                List<Integer> on = widgets();
                int at = on.indexOf(widgetIndex);
                if (dy < 0 || at < 0) {
                    zone = Z_DRAWER;
                } else if (dx != 0 && at + dx >= 0 && at + dx < on.size()) {
                    widgetIndex = on.get(at + dx);
                } else {
                    return;
                }
                break;
            }
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else if (talkBack() && !widgets().isEmpty()) {
                    // Only with TalkBack: the widgets are there to be read, and this is how it gets to them.
                    zone = WIDGETS;
                    widgetIndex = widgets().get(0);
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (rouletteSpinning) return;
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null) return;
            chooseWatchApp(p);
            return;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 5) {
                if (searchMode) { closeSearch(); return; }
                selectTab(topIndex);
                return;
            }
            if (topIndex >= 5 && !topButtonEnabled(topIndex - 5)) return;
            if (topIndex == 10) {
                click.open();
                openNetworkSettings();
                return;
            }
            if (topIndex == 9) {
                click.open();
                ProfileActivity.open(activity, profileBackground());
                return;
            }
            if (topIndex >= 5 && !topButtonEnabled(topIndex - 5)) return;
            if (topIndex == 5) { openSearch(); return; }
            it = lightButtons.get(topIndex - 5);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            if (tab == 4 && !searchMode) {
                if (rouletteFinished && !roulette.isEmpty()) chooseWatchApp(roulette.get(rouletteWinner));
                else spinRoulette();
                return;
            }
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null) return;
            chooseWatchApp(p);
            return;
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS && "vpn".equals(it.key)) {
            String pkg = activity.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("vpn_app", "");
            if (pkg.isEmpty()) { showToast(Ui.ru ? "Выберите приложение VPN в Настройки → Основные → VPN" : "Choose a VPN app in Settings → General → VPN"); return; }
            Intent launch = HomeApps.launchIntent(activity.getPackageManager(), pkg);
            if (launch == null) { showToast(Ui.ru ? "Приложение VPN не найдено" : "VPN app not found"); return; }
            try { activity.startActivity(launch); } catch (Exception e) { showToast(Ui.ru ? "Не удалось открыть VPN" : "Could not open VPN"); }
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    /** The exact same wallpaper-aware liquid material used by the app Dock and top-right buttons. */
    private void drawSearchGlass(Canvas c) {
        if (!searchGlassVisible || searchGlassBounds.isEmpty()) return;
        applyTone();
        float radius = searchGlassBounds.height() / 2f;
        RectF shadowRect = new RectF(searchGlassBounds);
        shadowRect.offset(0, 9 * k);
        shadow.setColor(tone(0x1F000000, 0x52000000));
        shadows.draw(c, shadowRect, radius, SHADOW_BLUR * k, shadow);
        scene.liquid(c, searchGlassBounds, radius, tone(0xFFFFFFFF, SMOKE),
                tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** Position the wallpaper-aware glass under the transparent Android text-input window. */
    private void syncSearchGlass(View panel) {
        if (!running || searchDialog == null || !searchDialog.isShowing()) return;
        if (panel.getWidth() == 0 || panel.getHeight() == 0) return;
        int[] panelPos = new int[2], homePos = new int[2];
        panel.getLocationOnScreen(panelPos);
        getLocationOnScreen(homePos);
        searchGlassBounds.set(panelPos[0] - homePos[0], panelPos[1] - homePos[1],
                panelPos[0] - homePos[0] + panel.getWidth(),
                panelPos[1] - homePos[1] + panel.getHeight());
        searchGlassVisible = true;
        searchGlassLayer.setVisibility(VISIBLE);
        searchGlassLayer.invalidate();
    }

    /** Fade and shrink the floating search glass before removing its window. */
    private void panelExit(android.app.Dialog dialog) {
        android.view.Window window = dialog.getWindow();
        if (window == null) { dialog.dismiss(); return; }
        android.view.View decor = window.getDecorView();
        decor.animate().cancel();
        searchGlassLayer.animate().cancel();
        searchGlassLayer.animate().alpha(0f).setDuration(220).start();
        decor.animate().alpha(0f).setDuration(220)
                .withEndAction(dialog::dismiss).start();
    }

    private void beginSearch() {
        cancelRouletteWork();
        setSearchInputOpen(true);
        if (!searchMode) {
            searchResults = new ArrayList<>();
            cardPage = cardIndex = 0;
        }
        searchMode = true;
    }

    private void restoreSearchRequest() {
        if (searchResults.isEmpty() && searchRequest == null && pendingSearch == null
                && searchQuery.codePointCount(0, searchQuery.length()) >= 3)
            requestSearch(searchQuery, ++searchSerial);
    }

    /** A translucent in-app search field; Android TV supplies only the text keyboard. */
    private void openSearch() {
        if (searchDialog != null && searchDialog.isShowing()) return;
        beginSearch();
        zone = Z_TOP;
        topIndex = availableTopButton(5);
        layoutLight();
        updateCards();
        retarget(false);
        heroDirty = statusDirty = true;
        wake();

        final android.app.Dialog dialog = new android.app.Dialog(activity) {
            private boolean closing;
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                int code = event.getKeyCode();
                // The IME consumes keys while it is visible. Once it has been hidden,
                // any navigation arrow dismisses the floating search, rather than trapping focus.
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                        || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0
                            && !closing) {
                        closing = true;
                        panelExit(this);
                    }
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            @Override public void onBackPressed() {
                if (closing) return;
                closing = true;
                android.view.Window w = getWindow();
                if (w != null) w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                panelExit(this);
            }
        };
        searchDialog = dialog;
        // HOME is handled by Android, not delivered as a key event to applications.
        // Close the dialog when our activity goes to the background.
        final android.app.Application.ActivityLifecycleCallbacks searchLifecycle =
                new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity a) {
                if (a == activity && dialog.isShowing()) {
                    dialog.dismiss();
                    closeSearch();
                }
            }
            @Override public void onActivityCreated(Activity a, android.os.Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        };
        activity.getApplication().registerActivityLifecycleCallbacks(searchLifecycle);
        final android.widget.LinearLayout panel = new android.widget.LinearLayout(activity);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (22 * activity.getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad / 2, pad, pad / 2);
        // Glass is drawn by the HomeView canvas through scene.liquid(), not a Dialog Drawable.
        // The dialog contains only a transparent text field and the system keyboard.
        panel.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        input.setTextColor(night > 0.5f ? 0xFFFFFFFF : 0xFF20242B);
        input.setHintTextColor(night > 0.5f ? 0xAABBBBBB : 0xFF777777);
        input.setTextSize(21);
        input.setCompoundDrawablePadding(pad / 2);
        input.setHint(Ui.ru ? "Поиск фильмов и сериалов…" : "Search movies and series…");
        input.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        panel.addView(input, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        // Measure the former capsule before removing its vertical padding and themed input minimum.
        // Keep 60% of that height, with enough room for the text at the user's font scale.
        int searchWidth = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.42f);
        panel.measure(View.MeasureSpec.makeMeasureSpec(searchWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        Paint.FontMetricsInt searchMetrics = input.getPaint().getFontMetricsInt();
        int searchHeight = Math.max(Math.round(panel.getMeasuredHeight() * 0.60f),
                searchMetrics.bottom - searchMetrics.top
                        + Math.round(2 * activity.getResources().getDisplayMetrics().density));
        panel.setPadding(pad, 0, pad, 0);
        input.setMinimumHeight(0);
        input.setMinHeight(0);
        input.setIncludeFontPadding(false);
        input.setPadding(input.getPaddingLeft(), 0, input.getPaddingRight(), 0);
        input.setGravity(android.view.Gravity.CENTER_VERTICAL);
        input.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, searchHeight));
        dialog.setContentView(panel);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL);
            window.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.42f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }

        // Restore before attaching the watcher: reopening the same query must not clear or reload its results.
        input.setText(searchQuery);
        input.setSelection(input.length());
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim();
                if (query.equals(searchQuery)) return;
                cancelSearchWork();
                searchQuery = query;
                int serial = ++searchSerial;
                searchResults = new ArrayList<>();
                cardPage = cardIndex = 0;
                layoutLight();
                updateCards();
                heroDirty = statusDirty = true;
                wake();
                if (query.codePointCount(0, query.length()) < 3) return;
                pendingSearch = () -> {
                    pendingSearch = null;
                    requestSearch(query, serial);
                };
                handler.postDelayed(pendingSearch, 400);
            }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });
        restoreSearchRequest();
        input.setOnEditorActionListener((v, actionId, event) -> {
            panelExit(dialog);
            return true;
        });
        panel.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                panel.post(() -> syncSearchGlass(panel)));
        dialog.setOnDismissListener(d -> {
            if (searchDialog == dialog) searchDialog = null;
            activity.getApplication().unregisterActivityLifecycleCallbacks(searchLifecycle);
            searchGlassVisible = false;
            setSearchInputOpen(false);
            searchGlassLayer.animate().cancel();
            searchGlassLayer.setAlpha(1f);
            searchGlassLayer.setScaleX(1f);
            searchGlassLayer.setScaleY(1f);
            searchGlassLayer.setTranslationY(0f);
            searchGlassLayer.setVisibility(INVISIBLE);
            if (pendingSearch != null) {
                Runnable submit = pendingSearch;
                handler.removeCallbacks(submit);
                pendingSearch = null;
                // Done/back may close the keyboard before the debounce expires; keep the requested search.
                if (running && searchMode) submit.run();
            }
            zone = searchResults.isEmpty() ? Z_TOP : Z_CARDS;
            if (zone == Z_TOP) topIndex = availableTopButton(5);
            retarget(false);
            heroDirty = statusDirty = true;
            wake();
        });
        dialog.show();
        window = dialog.getWindow();
        if (window != null) {
            window.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.42f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.y = Math.round(L_BUTTON_CY * k - searchHeight / 2f);
            window.setAttributes(attrs);
        }
        panel.post(() -> {
            if (!running || !dialog.isShowing()) return;
            // Match the toolbar's actual screen coordinates, including window insets.
            int[] panelPos = new int[2], homePos = new int[2];
            panel.getLocationOnScreen(panelPos);
            getLocationOnScreen(homePos);
            int offset = Math.round(homePos[1] + L_BUTTON_CY * k
                    - panelPos[1] - panel.getHeight() / 2f);
            android.view.Window searchWindow = dialog.getWindow();
            if (searchWindow != null && offset != 0) {
                android.view.WindowManager.LayoutParams attrs = searchWindow.getAttributes();
                attrs.y += offset;
                searchWindow.setAttributes(attrs);
            }
            panel.postOnAnimation(() -> {
                if (!dialog.isShowing()) return;
                syncSearchGlass(panel);
                searchGlassLayer.setAlpha(0f);
                searchGlassLayer.animate().alpha(1f).setDuration(260).start();
            });
        });
        android.view.View decor = dialog.getWindow().getDecorView();
        decor.setAlpha(0f);
        decor.animate().alpha(1f).setDuration(260).start();
        input.requestFocus();
    }

    private void cancelSearchWork() {
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        pendingSearch = null;
        if (searchRequest != null) searchRequest.cancel();
        searchRequest = null;
    }

    private void requestSearch(String query, int serial) {
        if (!running || !searchMode || serial != searchSerial
                || query.codePointCount(0, query.length()) < 3) return;
        searchRequest = recs.search(query, found -> {
            if (!running || !searchMode || serial != searchSerial) return;
            searchRequest = null;
            searchResults = found;
            cardPage = cardIndex = 0;
            layoutLight();
            updateCards();
            retarget(false);
            heroDirty = statusDirty = true;
            wake();
        });
    }

    /** Input intent is known before the dialog has its first layout, avoiding a heading flash. */
    private void setSearchInputOpen(boolean open) {
        if (open && !searchMode) searchHeadingOpacity.snap(0);
        searchHeadingOpacity.target = open || !searchMode ? 0 : 1;
        heroDirty = true;
        wake();
    }

    private boolean tickSearchHeading(float dt) {
        if (!searchMode) {
            searchHeadingOpacity.snap(0);
            return false;
        }
        return searchHeadingOpacity.step(dt);
    }

    private void closeSearch() {
        cancelSearchWork();
        searchMode = false;
        searchSerial++;
        searchResults.clear();
        cardPage = cardIndex = 0;
        zone = Z_TOP;
        topIndex = Prefs.firstCategory() >= 0 ? tab : availableTopButton(5);
        if (!topControlVisible(topIndex)) zone = Z_SHELF;
        layoutLight();
        updateCards();
        retarget(false);
        heroDirty = statusDirty = true;
        wake();
    }

    /** Choose a launchable installed TV application. Does not claim the title is available there. */
    private void chooseWatchApp(Recs.Pick pick) {
        PackageManager pm = activity.getPackageManager();
        LinkedHashMap<String, String> apps = new LinkedHashMap<>();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(activity.getPackageName()) || HomeApps.launchIntent(pm, pkg) == null) continue;
            apps.put(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        // Also include launchable phone-style apps installed on some YaOS devices.
        Intent regular = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(regular, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(activity.getPackageName()) || HomeApps.launchIntent(pm, pkg) == null) continue;
            apps.putIfAbsent(pkg, String.valueOf(ri.loadLabel(pm)));
        }
        // WATCH_APPS_ONLY_V4: show only supported video applications.
        java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(
                "top.rootu.lampa", "com.seena.app", "watch.kino", "com.vk.tv"));
        apps.keySet().retainAll(allowed);
        if (apps.isEmpty()) {
            showToast(Ui.ru ? "Не установлены Lampa, Seena, VK Видео или kino.watch" : "No launchable apps found");
            return;
        }
        java.util.List<Map.Entry<String, String>> entries = new ArrayList<>(apps.entrySet());
        entries.sort((a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        String[] labels = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) labels[i] = entries.get(i).getValue();
        new AlertDialog.Builder(activity)
                .setTitle(Ui.ru ? "Открыть приложение" : "Open app")
                .setItems(labels, (dialog, which) -> {
                    Map.Entry<String, String> selected = entries.get(which);
                    if ("top.rootu.lampa".equals(selected.getKey())) {
                        try {
                            Intent link = new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://www.themoviedb.org/"
                                            + ("tv".equals(pick.kind) ? "tv/" : "movie/") + pick.id));
                            link.setPackage("top.rootu.lampa");
                            activity.startActivity(link);
                            return;
                        } catch (Exception ignored) {
                            // Older Lampa builds may not handle TMDB links; use normal launch.
                        }
                    }
                    if ("com.seena.app".equals(selected.getKey())) {
                        // Seena 1.9.0: ACTION_SEARCH with a full query, no delayed relaunch.
                        try {
                            Intent search = new Intent(Intent.ACTION_SEARCH);
                            search.setClassName("com.seena.app", "com.seena.app.MainActivity");
                            search.putExtra(android.app.SearchManager.QUERY, pick.title);
                            search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(search);
                        } catch (Exception e) {
                            android.util.Log.w("FreeMyTV", "Seena search failed", e);
                            showToast(Ui.ru ? "Не удалось открыть поиск Seena" : "Couldn't open Seena search");
                        }
                        return;
                    }
                    if ("com.vk.tv".equals(selected.getKey())) {
                        openVkVideoSearch(pick.title);
                        return;
                    }
                    if ("watch.kino".equals(selected.getKey())) {
                        openKinoPub(pick);
                        return;
                    }
                    HomeApps.Item app = HomeApps.app(selected.getKey(), selected.getValue());
                    if (!HomeApps.open(activity, app, this, 0, 0, 1, 1)) {
                        showToast(Ui.ru ? "Не удалось открыть приложение" : "Couldn't open app");
                    }
                })
                .setNegativeButton(Ui.ru ? "Отмена" : "Cancel", null)
                .show();
    }

    /** Best-effort VK Video title search. The TV app may not expose a public search intent. */
    /** Open VK Video search with the selected movie title; no automation. */
    private void openVkVideoSearch(String title) {
        if (title == null || title.trim().isEmpty()) return;
        try {
            String query = java.net.URLEncoder.encode(title.trim(), "UTF-8");
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("vkvideo://video/search?query=" + query));
            intent.setPackage("com.vk.tv");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            android.util.Log.w("FreeMyTV", "VK Video search unavailable", e);
            showToast(Ui.ru ? "Не удалось открыть поиск VK Видео" : "Couldn't open VK Video search");
        }
    }

    /** KinoPub autocomplete: exact title first; type is a preference, not a hard filter (TV shows can be tagged differently). */
    private void openKinoPub(Recs.Pick pick) {
        final int epoch = workEpoch;
        if (pick == null || pick.title == null || pick.title.trim().isEmpty()) return;
        final String title = pick.title.trim();
        final String kind = pick.kind;
        final String origin = "tv".equals(kind) ? pick.tvOrigin : pick.movieOrigin;
        final Matcher yearMatch = Pattern.compile("(?:19|20)\\d{2}").matcher(origin == null ? "" : origin);
        final String year = yearMatch.find() ? yearMatch.group() : "";
        final SharedPreferences cache = activity.getSharedPreferences("kinopub-ids", Context.MODE_PRIVATE);
        final String cacheKey = kind + ":" + pick.id;
        final int cached = cache.getInt(cacheKey, -1);
        if (cached > 0) {
            launchKinoPub(cached);
            return;
        }
        showToast(Ui.ru ? "Ищем в KinoPub…" : "Searching KinoPub…");
        LOADER.execute(() -> {
            int id = -1;
            // Try both TMDB's localized and original titles. KinoPub may index only one.
            java.util.LinkedHashSet<String> queries = new java.util.LinkedHashSet<>();
            queries.add(title);
            if (pick.originalTitle != null && !pick.originalTitle.trim().isEmpty()) queries.add(pick.originalTitle.trim());
            // Keep candidates by KinoPub ID: the same result may appear in both queries.
            java.util.Map<Integer, Integer> scores = new java.util.HashMap<>();
            final String normalizedTitle = normalizeKinoTitle(title);
            final String normalizedOriginal = normalizeKinoTitle(pick.originalTitle == null ? "" : pick.originalTitle);
            for (String query : queries) {
                try {
                    String endpoint = "https://api.kinopub.link/v1.1/autocomplete?query="
                            + URLEncoder.encode(query, "UTF-8");
                    HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    try {
                        if (conn.getResponseCode() != 200) continue;
                        try (InputStream in = conn.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) != -1 && bytes.size() < 131072) bytes.write(buf, 0, n);
                            JSONArray matches = new JSONArray(bytes.toString("UTF-8"));
                            for (int i = 0; i < matches.length(); i++) {
                                JSONObject item = matches.optJSONObject(i);
                                if (item == null) continue;
                                String type = item.optString("type", "");
                                int candidate = item.optInt("id", -1);
                                if (candidate <= 0) continue;
                                String value = item.optString("value", "");
                                Matcher m = Pattern.compile("\\((\\d{4})\\)\\s*$").matcher(value);
                                String names = m.find() ? value.substring(0, m.start()) : value;
                                int nameScore = 0;
                                for (String part : names.split(" / ")) {
                                    String normalized = normalizeKinoTitle(part);
                                    if (normalized.isEmpty()) continue;
                                    if (normalized.equals(normalizedTitle)) nameScore = Math.max(nameScore, 100);
                                    if (!normalizedOriginal.isEmpty() && normalized.equals(normalizedOriginal))
                                        nameScore = Math.max(nameScore, 100);
                                }
                                // An exact title is mandatory; autocomplete contains many unrelated items.
                                if (nameScore == 0) continue;
                                // Prefer the matching category, but do not exclude shows,
                                // documentaries or other KinoPub categories with an exact title.
                                int score = nameScore;
                                if (("movie".equals(kind) && "movie".equals(type))
                                        || ("tv".equals(kind) && "serial".equals(type))) score += 10;
                                Matcher ym = Pattern.compile("\\((\\d{4})\\)\\s*$").matcher(value);
                                if (!year.isEmpty() && ym.find()) {
                                    int delta = Math.abs(Integer.parseInt(year) - Integer.parseInt(ym.group(1)));
                                    if (delta == 0) score += 20;
                                    else if (delta <= ("tv".equals(kind) ? 3 : 1)) score += 5;
                                    else continue; // Different production with the same name.
                                }
                                Integer old = scores.get(candidate);
                                if (old == null || score > old) scores.put(candidate, score);
                            }
                        }
                    } finally {
                        conn.disconnect();
                    }
                } catch (Exception e) {
                    android.util.Log.w("FreeMyTV", "KinoPub lookup failed for " + query, e);
                }
            }
            int bestScore = -1;
            boolean ambiguous = false;
            for (java.util.Map.Entry<Integer, Integer> candidate : scores.entrySet()) {
                int score = candidate.getValue();
                if (score > bestScore) {
                    bestScore = score;
                    id = candidate.getKey();
                    ambiguous = false;
                } else if (score == bestScore) {
                    ambiguous = true;
                }
            }
            if (ambiguous) id = -1;
            final int resolved = id;
            handler.post(() -> {
                if (!running || epoch != workEpoch) return;
                if (resolved > 0) {
                    cache.edit().putInt(cacheKey, resolved).apply();
                    launchKinoPub(resolved);
                } else {
                    showToast(Ui.ru ? "Точное совпадение в KinoPub не найдено" : "No exact KinoPub match");
                }
            });
        });
    }

    private static String normalizeKinoTitle(String title) {
        return title.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll(" +", " ");
    }

    private void launchKinoPub(int id) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://kino.watch/item/view/" + id));
            intent.setClassName("watch.kino", "watch.kino.activity.InfoActivity");
            activity.startActivity(intent);
        } catch (Exception e) {
            android.util.Log.w("FreeMyTV", "KinoPub card launch failed", e);
            showToast(Ui.ru ? "Не удалось открыть карточку KinoPub" : "Couldn't open KinoPub card");
        }
    }

    // MARK: - TalkBack: every reachable item as a virtual node (Spoken). Id = group * 1000 + index + 1.

    /** The drawer's weather (0) and quotes (1) cards: read by TalkBack, never focused by the remote. */
    private static final int GRID = 9, WIDGETS = 8;

    private static int a11yId(int group, int index) {
        return group * 1000 + index + 1;
    }

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return spoken;
    }

    private void a11yItems(List<Integer> out) {
        if (sheetOpen) {
            for (int i = 0; i < others.size(); i++) out.add(a11yId(GRID, i));
            return;
        }
        if (picksOn() && !searchMode) for (int id : Prefs.categoryOrder())
            if (tabEnabled(id)) out.add(a11yId(Z_TOP, id));
        for (int id : Prefs.topButtonOrder()) if (topButtonEnabled(id)) out.add(a11yId(Z_TOP, 5 + id));
        if (picksOn()) for (int i = 0; i < (tab == 4 ? 1 : Math.min(5, cards.size())); i++) out.add(a11yId(Z_CARDS, i));
        for (int i = 0; i < shelf.size(); i++) out.add(a11yId(Z_SHELF, i));
        for (int i = 0; i < drawerItems.size(); i++) out.add(a11yId(Z_DRAWER, i));
        if (drawerOpen && weatherBottom()) out.add(a11yId(WIDGETS, 0));
        if (drawerOpen && Prefs.quotes() && HomeData.active().length > 0) out.add(a11yId(WIDGETS, 1));
    }

    private String a11yText(int id) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= others.size()) return "";
                return label(others, i) + ", " + (i + 1) + Ui.tr(" de ", " of ") + others.size();
            case Z_TOP:
                if (i == 10) return networkLabel();
                if (i == 9) return Ui.ru ? "Часы и профиль" : "Clock and profile";
                if (i >= 5) return label(lightButtons, i - 5);
                return tabs()[i] + Ui.tr(", aba ", ", tab ") + Prefs.categoryPosition(i) + Ui.tr(" de ", " of ") + Prefs.categoryCount();
            case Z_CARDS: return tab == 4 ? (Ui.ru ? "Случайный фильм" : "Random Movie") : i < cards.size() ? pickText(cards.get(i)) : "";
            case Z_SHELF: return label(shelf, i);
            case Z_DRAWER: return label(drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : quotesText();
            default: return "";
        }
    }

    /**
     * Where the item is now. Items off screen (the closed drawer, the sheet's hidden rows, the top while the drawer is
     * open) get a thin strip at that edge, so TalkBack's arrows can still reach them; going there brings them in.
     */
    private boolean a11yBounds(int id, Rect out) {
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!sheetOpen || i >= others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * k;
                float y = (GRID_TOP + (i / COLUMNS - scroll.target) * GRID_ROW) * k;
                a11yRect.set(x, y, x + GRID_W * k, y + GRID_H * k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= topRects.length || !topControlVisible(i)) return false;
                a11yRect.set(topRects[i]);
                dy = (i < 5 ? heroLayer : status).getTranslationY();
                break;
            case Z_CARDS:
                if (i >= (tab == 4 ? 1 : Math.min(5, cards.size()))) return false;
                if (tab == 4) {
                    setRouletteButtonRect(a11yRect);
                } else {
                    a11yRect.set(cardRects[i]);
                }
                dy = heroLayer.getTranslationY();
                break;
            case Z_SHELF:
                if (i >= tileRects.length) return false;
                a11yRect.set(tileRects[i]);
                dy = tiles.getTranslationY();
                break;
            case Z_DRAWER:
                if (i >= drawerRects.length) return false;
                a11yRect.set(drawerRects[i]);
                dy = drawerOpen ? 0 : h;
                break;
            case WIDGETS:
                if (!drawerOpen) return false;
                a11yRect.set(i == 0 ? weatherCard : quotesCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * k;
        if (a11yRect.top >= h - edge) a11yRect.set(a11yRect.left, h - edge, a11yRect.right, h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    private int a11yFocused() {
        if (sheetOpen) return others.isEmpty() ? -1 : a11yId(GRID, gridIndex);
        switch (zone) {
            case Z_TOP: return a11yId(Z_TOP, topIndex);
            case Z_CARDS: return tab == 4 ? a11yId(Z_CARDS, 0) : cardIndex < cards.size() ? a11yId(Z_CARDS, cardIndex) : -1;
            case Z_SHELF: return shelf.isEmpty() ? -1 : a11yId(Z_SHELF, shelfIndex);
            case Z_DRAWER: return drawerItems.isEmpty() ? -1 : a11yId(Z_DRAWER, drawerIndex);
            case WIDGETS: return a11yId(WIDGETS, widgetIndex);
            default: return -1;
        }
    }

    /** The drawer's widgets that are on (0 weather, 1 quotes), with the drawer open. */
    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (weatherBottom()) out.add(0);
        if (Prefs.quotes() && HomeData.active().length > 0) out.add(1);
        return out;
    }

    /** A spoken-feedback service (TalkBack) is on. */
    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }

    /** TalkBack moved to an item: the remote's focus goes there (opening or closing the drawer on the way). */
    private void a11yFocus(int id) {
        wake();
        if (rouletteSpinning) return;
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (!sheetOpen || i >= others.size()) return;
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        if (sheetOpen) return;
        switch (group) {
            case Z_DRAWER:
                if (i >= drawerItems.size()) return;
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                if (!widgets().contains(i)) return;
                widgetIndex = i;
                break;
            case Z_TOP:
                if (i >= topRects.length || !topControlVisible(i)) return;
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 5) selectTab(i);
                break;
            case Z_CARDS:
                if (i >= (tab == 4 ? 1 : cards.size())) return;
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (i >= shelf.size()) return;
                if (drawerOpen) closeDrawer();
                shelfIndex = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        click.move();
    }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    /** A recommendation: title, details and the app that plays it. */
    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    /** The weather card as TalkBack reads it: what it shows, in the same words and units. */
    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? Ui.tr("Clima", "Weather") : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(Ui.tr("Sem dados do clima agora.", "No weather data right now.")).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay))
                .append(Ui.tr(". Máxima ", ". High ")).append(HomeData.degrees(wx.max))
                .append(Ui.tr(", mínima ", ", low ")).append(HomeData.degrees(wx.min)).append('.');
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) s.append(Ui.tr(" Próximas horas: ", " Next hours: "));
        for (int i = 0; i < hours; i++) {
            s.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i))).append(i < hours - 1 ? ", " : ".");
        }
        if (wx.sunrise != 0) s.append(Ui.tr(" Nascer do sol ", " Sunrise ")).append(Ui.time(wx.sunrise)).append('.');
        if (wx.sunset != 0) s.append(Ui.tr(" Pôr do sol ", " Sunset ")).append(Ui.time(wx.sunset)).append('.');
        return s.toString();
    }

    /** The quotes card as TalkBack reads it: each coin with its price and 24 h change. */
    private String quotesText() {
        StringBuilder s = new StringBuilder(Ui.tr("Cotações", "Crypto"));
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        s.append(", ").append(stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "", "de ") + stale).append('.');
        for (int i : HomeData.active()) {
            s.append(' ').append(HomeData.coinName(i)).append(' ').append(HomeData.brl(data.prices[i]));
            if (!Double.isNaN(data.changes[i])) s.append(", ").append(HomeData.change(data.changes[i]));
            s.append('.');
        }
        return s.toString();
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    /** Date and weather are the only optional left-side top-panel widgets. */
    private boolean noTopWidgets() {
        return !showHeaderPart("show_date") && !weatherTop();
    }

    private void layoutLight() {
        if (lTab == null) return;
        largePostersWithoutWidgets = noTopWidgets();
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k;
        float tabTop = (largePostersWithoutWidgets ? L_BUTTON_CY - L_TABBAR_H / 2f : L_TABBAR_TOP) * k;
        float tabBottom = tabTop + L_TABBAR_H * k;
        float x = MARGIN * k - pad;
        for (int i : Prefs.categoryOrder()) {
            if (!tabEnabled(i)) { topRects[i].setEmpty(); continue; }
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        layoutTopActions(b, right, cy);
        float regularW = ((CONTENT_R - MARGIN) * k - 5 * 18 * k) / 6f;
        // 10% wider and taller while keeping the poster bottom and metadata fixed.
        // The larger posters grow upwards into the unused header-widget space.
        float posterW = regularW * (largePostersWithoutWidgets ? 1.10f : 1f);
        float posterH = posterW * 1.5f;
        float regularH = regularW * 1.5f;
        float top = (L_CARDS_TOP * k + regularH) - posterH;
        float spacing = ((CONTENT_R - MARGIN) * k - 5 * posterW) / 4f;
        float left = MARGIN * k;
        for (int i = 0; i < 5; i++) {
            float px = left + i * (posterW + spacing);
            cardRects[i].set(px, top, px + posterW, top + posterH);
        }
        heroDirty = statusDirty = true;
    }

    private void layoutTopActions(float b, float right, float cy) {
        // Pack variable-width clock and fixed-width actions together in the configured order.
        int[] buttons = Prefs.topButtonOrder();
        for (int position = buttons.length - 1; position >= 0; position--) {
            int id = buttons[position], control = 5 + id;
            if (!topButtonEnabled(id)) { topRects[control].setEmpty(); continue; }
            if (id == 4) {
                Paint clockMeasure = new Paint(Paint.ANTI_ALIAS_FLAG);
                clockMeasure.setTypeface(Ui.displayMedium);
                clockMeasure.setTextSize(27 * k);
                Paint.FontMetrics fm = clockMeasure.getFontMetrics();
                float glyphHeight = fm.descent - fm.ascent;
                float textInset = Math.max(5 * k, (b - glyphHeight) / 2f);
                float avatarDiameter = b - 10 * k;
                float capsuleWidth = textInset + 5 * k + clockMeasure.measureText(clockText) + 10 * k
                        + avatarDiameter + 5 * k + 5 * k;
                topRects[9].set(right - capsuleWidth, cy - b / 2f, right, cy + b / 2f);
                right = topRects[9].left - L_BUTTON_GAP * k;
            } else {
                topRects[control].set(right - b, cy - b / 2, right, cy + b / 2);
                right -= b + L_BUTTON_GAP * k;
            }
        }
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        if (!searchMode && Prefs.firstCategory() < 0) {
            cards.clear();
            recs.setPosterWindow(java.util.Collections.emptyList());
            heroDirty = true;
            return;
        }
        if (tab == 4 && !searchMode) {
            cards.clear();
            if (!rouletteSpinning && !rouletteFinished) {
                roulettePosterSlot = Integer.MIN_VALUE;
                recs.setPosterWindow(java.util.Collections.emptyList());
            }
            cardPage = 0;
            heroDirty = true;
            return;
        }
        List<Recs.Pick> all = searchMode ? searchResults : recs.catalog(TAB_KIND[tab]);
        int perPage = 5;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        cardPage = Math.min(cardPage, pages - 1);
        int from = Math.min(all.size(), cardPage * perPage);
        List<Recs.Pick> list = new ArrayList<>(all.subList(from, Math.min(all.size(), from + perPage)));
        if (!samePicks(list, cards)) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty() && !searchMode) {
                zone = Z_SHELF;
                retarget(false);
            }
        }
        // Current page first, then the next two and previous page. Drop work from the old window.
        int ahead = Math.min(all.size(), from + perPage * 3);
        List<Recs.Pick> posterWindow = new ArrayList<>(all.subList(from, ahead));
        posterWindow.addAll(all.subList(Math.max(0, from - perPage), from));
        recs.setPosterWindow(running ? posterWindow : java.util.Collections.emptyList());
        if (running) for (Recs.Pick pick : posterWindow) recs.poster(pick);
        if (running) {
            if (searchMode || tab <= 2) for (Recs.Pick pick : cards) recs.requestDuration(pick);
            Backdrop b = cards.isEmpty() ? null : recs.backdrop(cards.get(0));
            if (cards.isEmpty() || b != null) showBackdrop(b);
        }
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (searchMode) { cancelSearchWork(); searchMode = false; searchSerial++; searchResults.clear(); }
        if (index == tab || !tabEnabled(index)) return;
        if (tab == 4 || index == 4) {
            cancelRouletteWork();
            rouletteSpinning = false;
            rouletteFinished = false;
            rouletteStarted = 0L;
        }
        tab = index;
        layoutLight();
        cardPage = 0;
        cardIndex = 0;
        updateCards();
        if (tab == 4 && tabEnabled(4) && roulette.isEmpty() && !rouletteLoading) loadRoulette();
        heroDirty = true;
    }

    private void cancelRouletteWork() {
        if (rouletteRequest != null) rouletteRequest.cancel();
        rouletteRequest = null;
        rouletteLoading = false;
        handler.removeCallbacks(rouletteComplete);
        if (rouletteSpinning) {
            rouletteSpinning = false;
            rouletteFinished = false;
            rouletteStarted = 0L;
        }
    }

    private void loadRoulette() {
        if (!running || !tabEnabled(4) || rouletteLoading) return;
        rouletteLoading = true;
        rouletteRequest = recs.loadAcclaimed((results) -> {
            if (!running || tab != 4) return;
            rouletteRequest = null;
            rouletteLoading = false;
            roulette.clear();
            roulette.addAll(results);
            heroDirty = true;
            wake();
        });
    }

    /** Advance a small poster window with the strip, rather than retaining the whole roulette catalog. */
    private void prepareRoulettePosters(int slot) {
        if (!running || roulette.isEmpty() || slot == roulettePosterSlot) return;
        roulettePosterSlot = slot;
        List<Recs.Pick> window = new ArrayList<>(14);
        // Fetch and retain the eventual winner first, even while it is still outside the moving strip.
        window.add(roulette.get(rouletteWinner));
        int center = rouletteWinner - slot, count = roulette.size();
        for (int distance = 0; distance <= 6; distance++) {
            window.add(roulette.get(Math.floorMod(center + distance, count)));
            if (distance != 0) window.add(roulette.get(Math.floorMod(center - distance, count)));
        }
        recs.setPosterWindow(window);
        for (Recs.Pick pick : window) recs.poster(pick);
    }

    private void spinRoulette() {
        if (rouletteSpinning) return;
        if (roulette.isEmpty()) {
            loadRoulette();
            showToast(Ui.ru ? "Загружаем фильмы…" : "Loading movies…");
            return;
        }
        rouletteWinner = rouletteRandom.nextInt(roulette.size());
        roulettePosterSlot = Integer.MIN_VALUE;
        rouletteFinished = false;
        rouletteSpinning = true;
        zone = Z_CARDS;
        cardIndex = 0;
        retarget(false);
        rouletteStarted = SystemClock.uptimeMillis();
        prepareRoulettePosters(57);
        handler.removeCallbacks(rouletteComplete);
        handler.postDelayed(rouletteComplete, 5000);
        heroDirty = true;
        wake();
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();

        float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
        boolean fading = bgFadeAt != 0;
        // Drawer open: the background lightens (by day) or darkens (at night), as a filter on the drawing itself.
        android.graphics.ColorFilter v = veil(tone(0.32f, 0.42f) * Ui.clamp(drawer.value), blend(0xFFFFFFFF, 0xFF000000, night));
        if (bgTo == null || (fading && bgFrom == null)) {
            if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
            if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
            fill.setColorFilter(v);
            fill.setColor(0xFF000000);
            if (night < 0.999f) {
                fill.setShader(lightGradient);
                c.drawRect(screen, fill);
            }
            if (night > 0.001f) {
                fill.setShader(nightGradient);
                fill.setAlpha(Math.round(255 * night));
                c.drawRect(screen, fill);
                fill.setAlpha(255);
            }
            fill.setShader(null);
            fill.setColorFilter(null);
        }
        imagePaint.setColorFilter(v);
        if (fading && bgFrom != null) drawLightImage(c, bgFrom, bgTo == null ? 1 - e : 1);
        if (bgTo != null) drawLightImage(c, bgTo, fading ? e : 1);
        imagePaint.setColorFilter(null);
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        marqueeNeeded = false;
        applyTone();
        if (!picksOn()) {
            drawHeaderDateWeather(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        drawHeaderDateWeather(c);
        // Text tabs (the selected one in bold, in the main text color); with focus on the tabs, the selected one gets
        // the white pill with a shadow and grows a little (the tvOS focus).
        float focus = Ui.clamp(topFocus[tab].value);
        for (int i = 0; i < 5 && !searchMode; i++) {
            if (!tabEnabled(i)) continue;
            RectF r = topRects[i];
            if (i == tab && focus > 0.01f) {
                float s = 1 + 0.05f * focus;
                cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                        r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
                float rr = cell.height() / 2;
                if (focus > 0.01f) {
                    tmp.set(cell);
                    tmp.offset(0, 8 * k * focus);
                    shadow.setColor(Ui.alpha(0xFF000000, tone(0.22f, 0.4f) * focus));
                    shadows.draw(c, tmp, rr, SHADOW_BLUR * k, shadow);
                }
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, focus));
                c.drawRoundRect(cell, rr, rr, fill);
            }
            Paint p = i == tab ? lTabOn : lTab;
            if (i == tab) lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, focus));   // always dark on the white pill
            c.drawText(tabs()[i], r.centerX() - p.measureText(tabs()[i]) / 2, r.centerY() + Ui.halfCap(p), p);
        }
        if (searchMode && searchHeadingOpacity.value > 0.001f) {
            Paint heading = searchHeadingScratch;
            heading.set(lTabOn);
            heading.setColor(Ui.alpha(tone(L_INK, WHITE), Ui.clamp(searchHeadingOpacity.value)));
            c.drawText((Ui.ru ? "Поиск: " : "Search: ") + searchQuery, MARGIN * k,
                    (largePostersWithoutWidgets ? L_BUTTON_CY + 14 : L_TABBAR_TOP + 48) * k, heading);
        }
        if (tab == 4 && !searchMode) {
            drawRoulette(c);
            if (HomeData.coinCount() > 0) drawQuotes(c);
            return;
        }
        int top = -1;
        for (int i = 0; i < cards.size(); i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < cards.size(); i++) if (i != top) drawLightCard(c, i);
        if (top >= 0) drawLightCard(c, top);
        if (HomeData.coinCount() > 0) drawQuotes(c);
    }

    /** Date and weather share one line, centered vertically with right-side controls. */
    private boolean showHeaderPart(String key) {
        return getContext().getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(key, true);
    }

    private void drawHeaderDateWeather(Canvas c) {
        final float x = MARGIN * k;
        final float cy = L_BUTTON_CY * k;
        Paint datePaint = headerDateScratch;
        datePaint.set(lDate);
        datePaint.setTextSize(29 * k);
        datePaint.setColor(tone(L_INK, WHITE));
        Paint.FontMetrics fm = headerMetricsScratch;
        datePaint.getFontMetrics(fm);
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        float nextX = x;
        if (showHeaderPart("show_date")) {
            String date = dateText;
            if (!date.isEmpty()) date = date.substring(0, 1).toUpperCase(Ui.locale()) + date.substring(1);
            c.drawText(date, x - Ui.leftBearing(datePaint, date), baseline, datePaint);
            nextX = x + datePaint.measureText(date) + 30 * k;
        }
        HomeData.Weather wx = data.weather;
        if (!weatherTop() || Double.isNaN(wx.temp)) return;
        Paint weatherPaint = headerWeatherScratch;
        weatherPaint.set(datePaint);
        weatherPaint.setTextSize(27 * k);
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float iconSize = 28 * k;
        float sw = Symbols.width(getContext(), symbol, iconSize);
        Symbols.draw(getContext(), c, symbol, nextX + sw / 2f - 3f,
                cy, iconSize, weatherPaint.getColor());
        c.drawText(HomeData.degrees(wx.temp), nextX + sw + 10 * k, baseline, weatherPaint);
    }

    private void setRouletteButtonRect(RectF out) {
        float cx = (MARGIN + CONTENT_R) * k / 2f;
        float cy = 480 * k;
        out.set(cx - 295 * k, cy - 63 * k, cx + 295 * k, cy + 63 * k);
    }

    private void drawRoulette(Canvas c) {
        final float cx = (MARGIN + CONTENT_R) * k / 2f;
        final float cy = 480 * k;
        if (!rouletteSpinning && !rouletteFinished) {
            setRouletteButtonRect(rouletteButtonRect);
            RectF button = rouletteButtonRect;
            float focus = Ui.clamp(rouletteButtonFocus.value);
            float scale = drawActionSurface(c, button, focus);
            rouletteTextPaint.set(lCardTitle);
            Paint p = rouletteTextPaint;
            p.setTextSize(40.8f * k);
            p.setColor(blend(tone(L_INK, 0xEBFFFFFF), L_INK, focus));
            String label = rouletteLoading ? (Ui.ru ? "Загрузка фильмов…" : "Loading movies…")
                    : (Ui.ru ? "🎲  Случайный фильм" : "🎲  Random Movie");
            int saved = c.save();
            c.scale(scale, scale, cx, cy);
            c.drawText(label, cx - p.measureText(label) / 2, cy + Ui.halfCap(p), p);
            c.restoreToCount(saved);
            return;
        }
        if (roulette.isEmpty()) return;
        final float elapsed = Math.min(1f, (SystemClock.uptimeMillis() - rouletteStarted) / 5000f);
        // 5 seconds at the same average poster speed, with smooth acceleration and braking.
        // Integrate a trapezoidal speed profile to avoid sudden jumps in position.
        final float accel = 0.08f, brake = 0.16f;
        final float cruiseEnd = 1f - brake;
        final float area = 1f - (accel + brake) * 0.5f;
        final float distance;
        if (elapsed < accel) {
            distance = elapsed * elapsed / (2f * accel);
        } else if (elapsed < cruiseEnd) {
            distance = accel * 0.5f + elapsed - accel;
        } else {
            float t = elapsed - cruiseEnd;
            distance = accel * 0.5f + cruiseEnd - accel + t - t * t / (2f * brake);
        }
        final float totalStrides = 57f;
        final float travel = totalStrides * distance / area;
        final float cardW = 238 * k, cardH = 357 * k, gap = 28 * k;
        // Short posters while spinning; the winning poster grows as the strip settles.
        final float grow = rouletteFinished ? 1f : Math.max(0f, Math.min(1f, (elapsed - 0.91f) / 0.09f));
        final float winnerGrow = grow * grow * (3f - 2f * grow);
        // Focus is independent of the result: moving up/down removes the rim,
        // returning to the poster restores it without changing the winner.
        final float winnerFocus = rouletteFinished && zone == Z_CARDS && !sheetOpen
                ? Ui.clamp(cardFocus[0].value) : 0f;
        final float stride = cardW + gap;
        final int count = roulette.size();
        final float left = MARGIN * k, right = CONTENT_R * k;
        // The strip starts at the right edge and scrolls left; fade both ends like the title marquee.
        // Leave room for the focused card's blurred shadow below the poster.
        final float layerTop = cy - cardH / 2 - 55 * k;
        final float layerBottom = cy + cardH / 2 + 85 * k;
        prepareRoulettePosters(Math.round(totalStrides - travel));
        c.saveLayer(left, layerTop, right, layerBottom, null);
        for (int i = -62; i <= 5; i++) {
            float pos = i + totalStrides - travel;
            // Scale both dimensions together: keep the original 238:357 poster aspect ratio.
            float posterScale = (255f + (i == 0 ? 102f * winnerGrow : 0f)) / 357f;
            float posterWidth = cardW * posterScale;
            float posterHeight = cardH * posterScale;
            float posterX = cx + pos * stride;
            if (posterX + posterWidth / 2 < left || posterX - posterWidth / 2 > right) continue;
            RectF rect = roulettePosterRect;
            rect.set(posterX - posterWidth / 2, cy - posterHeight / 2,
                    posterX + posterWidth / 2, cy + posterHeight / 2);
            Recs.Pick pick = roulette.get(Math.floorMod(rouletteWinner + i, count));
            Bitmap bmp = recs.poster(pick);
            // Same resting shadow as unselected cards in drawLightCard().
            // The winning card gradually receives the stronger focused shadow.
            float shadowFocus = i == 0 ? winnerGrow * winnerFocus : 0f;
            RectF shadowRect = rouletteShadowRect;
            shadowRect.set(rect);
            shadowRect.offset(0, (10f + 14f * shadowFocus) * k);
            shadowDeep.setColor(Ui.alpha(0xFF000000,
                    tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * shadowFocus));
            shadows.draw(c, shadowRect, 18 * k, DEEP_BLUR * k, shadowDeep);
            if (bmp != null) {
                // Lightweight directional trails only while moving.
                float speed = rouletteSpinning ? (elapsed < accel ? elapsed / accel
                        : elapsed > cruiseEnd ? (1f - elapsed) / brake : 1f) * 12f : 0f;
                if (speed > 0.5f) {
                    for (int trail = 2; trail >= 1; trail--) {
                        Paint ghostPaint = rouletteGhostPaint;
                        c.saveLayer(rect.left - 50 * k, rect.top, rect.right, rect.bottom, ghostPaint);
                        RectF ghost = rouletteGhostRect;
                        ghost.set(rect);
                        ghost.offset(trail * speed * 4 * k, 0);
                        roundImage(c, bmp, ghost, 16 * k);
                        c.restore();
                    }
                }
                roundImage(c, bmp, rect, 16 * k);
            } else {
                fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
                c.drawRoundRect(rect, 16 * k, 16 * k, fill);
            }
            // Same highlight sheen and white gradient rim as drawLightCard().
            if (i == 0 && winnerFocus > 0.01f) {
                Paint winnerSheen = rouletteWinnerSheenScratch;
                winnerSheen.setShader(new LinearGradient(rect.left, rect.top,
                        rect.centerX(), rect.bottom,
                        0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                winnerSheen.setAlpha(Math.round(255f * winnerFocus));
                c.drawRoundRect(rect, 16 * k, 16 * k, winnerSheen);
                Paint winnerRim = rouletteWinnerRimScratch;
                winnerRim.setStyle(Paint.Style.STROKE);
                winnerRim.setStrokeWidth(2.5f * k);
                winnerRim.setShader(new LinearGradient(rect.centerX(), rect.top,
                        rect.centerX(), rect.bottom,
                        0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP));
                winnerRim.setAlpha(Math.round(255f * winnerFocus));
                c.drawRoundRect(rect, 16 * k, 16 * k, winnerRim);
            }
        }
        Paint edgeMask = rouletteEdgeScratch;
        if (rouletteEdgeLeft != left || rouletteEdgeRight != right) {
            edgeMask.setShader(new LinearGradient(left, 0, right, 0,
                    new int[]{0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.10f, 0.90f, 1f}, Shader.TileMode.CLAMP));
            rouletteEdgeLeft = left;
            rouletteEdgeRight = right;
        }
        edgeMask.setXfermode(textEdgeMode);
        c.drawRect(left, layerTop, right, layerBottom, edgeMask);
        edgeMask.setXfermode(null);
        c.restore();
        if (rouletteFinished) {
            Recs.Pick chosen = roulette.get(rouletteWinner);
            Paint p = rouletteTitleScratch;
            p.set(lCardTitle);
            p.setColor(tone(L_INK, WHITE));
            p.setTextSize(32 * k);
            ellipsizeScratch.set(p);
            String name = android.text.TextUtils.ellipsize(chosen.title,
                    ellipsizeScratch, 850 * k,
                    android.text.TextUtils.TruncateAt.END).toString();
            c.drawText(name, cx - p.measureText(name) / 2, cy + 230 * k, p);
            // Two compact glass hints instead of a single unframed caption.
            Paint hintText = rouletteHintScratch;
            hintText.set(lCardTitle);
            hintText.setTextSize(23 * k);
            hintText.setColor(tone(L_INK, WHITE));
            String hintWatch = Ui.ru ? "OK – смотреть" : "OK – watch";
            String hintAgain = Ui.ru ? "Вправо – крутить ещё" : "Right – spin again";
            float hintPad = 24 * k;
            float hintGap = 16 * k;
            float hintH = 58 * k;
            float hintW1 = hintText.measureText(hintWatch) + 2 * hintPad;
            float hintW2 = hintText.measureText(hintAgain) + 2 * hintPad;
            float hintX = cx - (hintW1 + hintGap + hintW2) / 2f;
            float hintY = cy + 251 * k;
            RectF hintOne = rouletteHintOneScratch;
            hintOne.set(hintX, hintY, hintX + hintW1, hintY + hintH);
            RectF hintTwo = rouletteHintTwoScratch;
            hintTwo.set(hintOne.right + hintGap, hintY,
                    hintOne.right + hintGap + hintW2, hintY + hintH);
            scene.liquid(c, hintOne, 20 * k, tone(0xFFFFFFFF, SMOKE),
                    tone(0.52f, 0.48f), tone(0.02f, 0.02f), 8 * k, 5 * k,
                    tone(0.62f, 0.48f), 1f);
            scene.liquid(c, hintTwo, 20 * k, tone(0xFFFFFFFF, SMOKE),
                    tone(0.52f, 0.48f), tone(0.02f, 0.02f), 8 * k, 5 * k,
                    tone(0.62f, 0.48f), 1f);
            float hintBase = hintY + hintH / 2f + Ui.halfCap(hintText);
            c.drawText(hintWatch, hintOne.centerX() - hintText.measureText(hintWatch) / 2f,
                    hintBase, hintText);
            c.drawText(hintAgain, hintTwo.centerX() - hintText.measureText(hintAgain) / 2f,
                    hintBase, hintText);
        }
    }

    private boolean picksOn() {
        return Recs.hasKey(getContext()) && (searchMode || Prefs.categoryCount() > 0);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        if (i >= cards.size()) return; // Last TMDB page may contain fewer than five titles.
        RectF base = cardRects[i];
        Recs.Pick p = cards.get(i);
        float v = cardFocus[i].value, f = Ui.clamp(v), s = 1 + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX(), cy = base.centerY() - 6 * k * v;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
        tmp.offset(0, (10 + 14 * f) * k);
        shadowDeep.setColor(Ui.alpha(0xFF000000, tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f));
        shadows.draw(c, tmp, r0, DEEP_BLUR * k, shadowDeep);
        c.restore();
        // Every card, including New Releases, displays its original portrait poster.
        Bitmap img = recs.poster(p);
        if (img != null) {
            roundImage(c, img, cell, r);
        } else {
            fill.setShader(null);
            fill.setColor(tone(0xFFDADADF, 0xFF2C2C2E));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            if (lightSheens.length != cardRects.length) {
                lightSheens = new LinearGradient[cardRects.length];
                lightRims = new LinearGradient[cardRects.length];
                lightShaderW = new float[cardRects.length];
            }
            if (lightSheens[i] == null || lightShaderW[i] != bw) {
                lightSheens[i] = new LinearGradient(-bw / 2, -bh / 2, 0, bh / 2, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                lightRims[i] = new LinearGradient(0, -bh / 2, 0, bh / 2, 0xE6FFFFFF, 0x59FFFFFF, Shader.TileMode.CLAMP);
                lightShaderW[i] = bw;
            }
            int alpha = Math.round(255 * f);
            c.save();
            c.translate(cx, cy);
            c.scale(s, s);
            tmp.set(-bw / 2, -bh / 2, bw / 2, bh / 2);
            sheen.setShader(lightSheens[i]);
            sheen.setAlpha(alpha);
            c.drawRoundRect(tmp, r0, r0, sheen);
            sheen.setAlpha(255);
            rim.setShader(lightRims[i]);
            rim.setAlpha(alpha);
            rim.setStrokeWidth(2.5f * k / s);
            c.drawRoundRect(tmp, r0, r0, rim);
            rim.setStrokeWidth(2.5f * k);
            rim.setAlpha(255);
            c.restore();
        }
        if (p == null) return;
        String title = p.title == null ? "" : p.title;
        drawCardMarquee(c, title, lCardTitle, base.left, base.bottom + 46 * k, bw, i);
        String detail = "";
        if ("tv".equals(p.kind)) detail = p.tvOrigin == null ? "" : p.tvOrigin;
        else if ("movie".equals(p.kind)) {
            String origin = p.movieOrigin == null ? "" : p.movieOrigin;
            String duration = p.duration == null ? "" : p.duration;
            // Normalize old cached metadata as well as newly fetched TMDB details.
            origin = origin.replace(" г.", "").replace(" г", "").replace(", ", " · ");
            detail = origin.isEmpty() ? duration : duration.isEmpty() ? origin : origin + " · " + duration;
        }
        if ("tv".equals(p.kind) && !detail.isEmpty()) {
            detail = detail.replace(" г.", "").replace(" г", "").replace(", ", " · ");
        }
        if (tab == 0 && !searchMode && detail.isEmpty() && p.meta != null) detail = p.meta;
        float metaLeft = base.left;
        float metaWidth = bw;
        float metaBaseline = base.bottom + 85 * k;
        if (p.rating > 0.0) {
            String rating = String.format(java.util.Locale.US, "%.1f", p.rating);
            float pad = 6 * k;
            Paint ratingText = ratingTextScratch;
            ratingText.set(lCardMeta);
            ratingText.setTypeface(android.graphics.Typeface.create(
                    lCardMeta.getTypeface(), android.graphics.Typeface.BOLD));
            // Match the translucent glass controls without sacrificing contrast.
            // Use the actual day/night transition, not the metadata text color.
            ratingText.setColor(p.rating >= 6.0 && p.rating < 9.0 ? 0xFF000000 : 0xFFFFFFFF);
            ratingText.setShadowLayer(0.85f * k, 0f, 0.6f * k, 0x66000000);
            float badgeWidth = ratingText.measureText(rating) + 2 * pad;
            float badgeTop = metaBaseline + ratingText.ascent() - 1.5f * k;
            float badgeBottom = metaBaseline + ratingText.descent() + 1.5f * k;
            RectF badge = ratingBadgeScratch;
            badge.set(base.left, badgeTop, base.left + badgeWidth, badgeBottom);
            float badgeRadius = 4 * k;
            Paint badgeFill = ratingFillScratch;
            badgeFill.reset();
            badgeFill.setAntiAlias(true);
            badgeFill.setStyle(Paint.Style.FILL);
            // Smoke glass in light mode, frosted glass in dark mode.
            badgeFill.setColor(ratingBadgeColor(Double.parseDouble(rating)));
            c.drawRoundRect(badge, badgeRadius, badgeRadius, badgeFill);
            // A subtle highlight and fine rim reproduce the upper glass buttons.
            Paint badgeSheen = ratingSheenScratch;
            badgeSheen.reset();
            badgeSheen.setAntiAlias(true);
            badgeSheen.setShader(new LinearGradient(
                    badge.left, badge.top, badge.left, badge.bottom,
                    blend(0x20FFFFFF, 0x32FFFFFF, night),
                    0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(badge, badgeRadius, badgeRadius, badgeSheen);
            Paint badgeRim = ratingRimScratch;
            badgeRim.reset();
            badgeRim.setAntiAlias(true);
            badgeRim.setStyle(Paint.Style.STROKE);
            badgeRim.setStrokeWidth(0.8f * k);
            badgeRim.setColor(blend(0x45FFFFFF, 0x66FFFFFF, night));
            ratingInsetScratch.set(badge.left + 0.4f * k, badge.top + 0.4f * k,
                    badge.right - 0.4f * k, badge.bottom - 0.4f * k);
            c.drawRoundRect(ratingInsetScratch, badgeRadius, badgeRadius, badgeRim);
            c.drawText(rating, base.left + pad, metaBaseline, ratingText);
            metaLeft += badgeWidth + 19 * k;
            metaWidth -= badgeWidth + 19 * k;
        }
        if (!detail.isEmpty() && metaWidth > 0) {
            drawCardMarquee(c, detail, lCardMeta, metaLeft, metaBaseline, metaWidth, i);
        }
    }

    private static int ratingBadgeColor(double r) {
        if (r >= 9.8) return 0xFF1EA1F2;
        if (r >= 9.0) return 0xFF186A3A;
        if (r >= 8.0) return 0xFF27B463;
        if (r >= 7.0) return 0xFFF4D03F;
        if (r >= 6.0) return 0xFFF39C13;
        if (r >= 4.1) return 0xFFE74C3C;
        return 0xFF633974;
    }

    /** Marquee shared by titles and TV origin metadata, with a soft alpha fade at clipped edges. */
    private void drawCardMarquee(Canvas c, String value, Paint paint, float left, float baseline, float width, int i) {
        float textWidth = paint.measureText(value);
        boolean focusedOverflow = zone == Z_CARDS && !sheetOpen && i == marqueeCard
                && cardPage == marqueePage && tab == marqueeTab && textWidth > width;
        if (focusedOverflow) marqueeNeeded = true;
        if (focusedOverflow && SystemClock.uptimeMillis() - marqueeSince >= 1000) {
            // Ping-pong marquee: scroll left, pause, scroll right, pause, repeat.
            float overflow = textWidth - width;
            float travelMs = overflow / (42f * k) * 1000f;
            float pauseMs = 500f;
            float cycle = 2f * travelMs + 2f * pauseMs;
            float elapsed = (SystemClock.uptimeMillis() - marqueeSince - 1000) % cycle;
            float offset;
            if (elapsed < travelMs) offset = elapsed / travelMs * overflow;
            else if (elapsed < travelMs + pauseMs) offset = overflow;
            else if (elapsed < 2f * travelMs + pauseMs)
                offset = overflow - (elapsed - travelMs - pauseMs) / travelMs * overflow;
            else offset = 0f;
            // Render into a layer so the letters fade out instead of being cut at the edges.
            float top = baseline + paint.ascent() - 4 * k;
            float bottom = baseline + paint.descent() + 4 * k;
            int layer = c.saveLayer(left, top, left + width, bottom, null);
            c.clipRect(left, top, left + width, bottom);
            c.drawText(value, left - offset, baseline, paint);
            float edge = Math.min(20f * k, width * 0.18f);
            if (edge > 0f) {
                Paint mask = marqueeMaskScratch;
                mask.setXfermode(textEdgeMode);
                // Fade at the leading/trailing clipped edge, depending on the text position.
                boolean leftClipped = offset > 0.5f * k;
                boolean rightClipped = overflow - offset > 0.5f * k;
                mask.setShader(new LinearGradient(left, 0, left + width, 0,
                        new int[] {
                                leftClipped ? 0x00FFFFFF : 0xFFFFFFFF,
                                0xFFFFFFFF, 0xFFFFFFFF,
                                rightClipped ? 0x00FFFFFF : 0xFFFFFFFF
                        },
                        new float[] {0f, edge / width, 1f - edge / width, 1f},
                        Shader.TileMode.CLAMP));
                c.drawRect(left, top, left + width, bottom, mask);
                mask.setShader(null);
                mask.setXfermode(null);
            }
            c.restoreToCount(layer);
        } else {
            ellipsizeScratch.set(paint);
            c.drawText(TextUtils.ellipsize(value, ellipsizeScratch, width, TextUtils.TruncateAt.END).toString(), left, baseline, paint);
        }
    }

    /** Reuse the visible background's tiny blurred pixels, including its day/night treatment. */
    private int[] profileBackground() {
        if (bgTo == null) return null;
        if (night >= 0.5f && bgTo.nightFrost != null) return bgTo.nightFrost;
        return bgTo.light != null ? bgTo.light : frostOf(bgTo);
    }

    /** Shared focus surface for circular actions and larger capsules. Leaves scaled bounds in cell. */
    private float drawActionSurface(Canvas c, RectF bounds, float focus) {
        float scale = actionSurface.draw(c, bounds, focus, night, k);
        cell.set(actionSurface.bounds);
        return scale;
    }

    /** Focusable clock/profile capsule next to the top-right actions. */
    private void drawClockProfileCapsule(Canvas c) {
        if (!clockButtonEnabled()) return;
        RectF capsule = topRects[9];
        if (capsule.isEmpty()) return;
        float clockFocus = Ui.clamp(topFocus[9].value);
        float scale = drawActionSurface(c, capsule, clockFocus);
        int saved = c.save();
        c.scale(scale, scale, capsule.centerX(), capsule.centerY());
        Paint p = clockCapsuleScratch;
        p.reset();
        p.setAntiAlias(true);
        // Dark text on the white focused capsule in both themes, just like Search.
        p.setColor(blend(tone(L_INK, 0xEBFFFFFF), L_INK, clockFocus));
        p.setTypeface(Ui.displayMedium);
        p.setTextSize(27 * k);
        String time = clockText;
        Paint.FontMetrics clockFm = clockMetricsScratch;
        p.getFontMetrics(clockFm);
        float clockInset = Math.max(5 * k, (capsule.height() - (clockFm.descent - clockFm.ascent)) / 2f);
        float clockBaseline = capsule.centerY() - (clockFm.ascent + clockFm.descent) / 2f;
        c.drawText(time, capsule.left + clockInset + 5 * k, clockBaseline, p);
        float avatarRadius = Math.max(1f, capsule.height() / 2f - 5 * k);
        float ax = capsule.right - 5 * k - avatarRadius, ay = capsule.centerY();
        if (profileAvatarIndex >= 0) {
            profileAvatar.draw(c, profileAvatarIndex, ax, ay, avatarRadius);
            c.restoreToCount(saved);
            return;
        }
        p.setColor(tone(0xFFD5E1E8, 0xFF485662));
        c.drawCircle(ax, ay, avatarRadius, p);
        p.setColor(tone(0xFF41505D, 0xFFF1F3F5));
        c.drawCircle(ax, ay - 7 * k, 7 * k, p);
        RectF shoulders = avatarShouldersScratch;
        shoulders.set(ax - 13 * k, ay + 3 * k, ax + 13 * k, ay + 16 * k);
        c.drawRoundRect(shoulders, 11 * k, 11 * k, p);
        c.restoreToCount(saved);
    }

    private String networkLabel() {
        String state = networkStatus.state() == NetworkStatus.WIFI
                ? (Ui.ru ? "Wi-Fi, интернет доступен" : "Wi-Fi, internet available")
                : networkStatus.state() == NetworkStatus.ETHERNET
                ? (Ui.ru ? "Ethernet, интернет доступен" : "Ethernet, internet available")
                : (Ui.ru ? "Нет интернета" : "No internet");
        return state + (Ui.ru ? ". Открыть настройки сети" : ". Open network settings");
    }

    private void openNetworkSettings() {
        for (String action : new String[] {android.provider.Settings.ACTION_WIRELESS_SETTINGS,
                android.provider.Settings.ACTION_WIFI_SETTINGS, android.provider.Settings.ACTION_SETTINGS}) {
            try { activity.startActivity(new Intent(action)); return; }
            catch (android.content.ActivityNotFoundException | SecurityException ignored) {}
        }
        showToast(Ui.ru ? "Не удалось открыть настройки сети" : "Could not open network settings");
    }

    private void drawLightButtons(Canvas c) {
        applyTone();
        drawClockProfileCapsule(c);
        if (topButtonEnabled(5)) {
            float networkFocus = Ui.clamp(topFocus[10].value);
            float networkScale = drawActionSurface(c, topRects[10], networkFocus);
            networkIcon.draw(c, networkStatus.state(), cell.centerX(), cell.centerY(),
                    30 * k * networkScale, blend(tone(L_INK, 0xEBFFFFFF), L_INK, networkFocus));
        }
        for (int i = 0; i < lightButtons.size(); i++) {
            if (!topButtonEnabled(i)) continue;
            RectF r = topRects[5 + i];
            float f = Ui.clamp(topFocus[5 + i].value);
            float s = drawActionSurface(c, r, f);
            Symbols.draw(getContext(), c, lightButtons.get(i).symbol, cell.centerX(), cell.centerY(),
                    27f * 1.15f * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));

        }
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0 || shelf.isEmpty()) return false;
                if (!picksOn() || (cards.isEmpty() && (tab != 4 || searchMode))) {
                    zone = Z_TOP;
                    topIndex = searchMode || !picksOn() ? availableTopButton(5) : tab;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (tab == 4 && !searchMode) {
                    if (dx > 0 && rouletteFinished) spinRoulette();
                    else if (dy < 0) { zone = Z_TOP; topIndex = searchMode ? availableTopButton(5) : tab; }
                    else if (dy > 0) zone = Z_SHELF;
                    break;
                }
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) {
                        int total = (searchMode ? searchResults : recs.catalog(TAB_KIND[tab])).size();
                        int perPage = 5;
                        if (dx > 0 && (cardPage + 1) * perPage < total) {
                            cardPage++;
                            cardIndex = 0;
                        } else if (dx < 0 && cardPage > 0) {
                            cardPage--;
                            cardIndex = perPage - 1;
                        } else return true;
                        updateCards();
                        // updateCards() clamps focus for the last (possibly incomplete) page.
                    } else cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    // Route by visible position, not fixed action IDs. The last poster
                    // reaches the rightmost action; the preceding poster reaches the
                    // action immediately to its left (or the same one if only one exists).
                    if (!searchMode && cardIndex == 4) {
                        topIndex = posterCornerTarget(true);
                    } else if (!searchMode && cardIndex == 3) {
                        topIndex = posterCornerTarget(false);
                    } else {
                        topIndex = searchMode ? availableTopButton(5) : tab;
                    }
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = nextTopControl(topIndex, dx);
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 5) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (!picksOn() || (cards.isEmpty() && (tab != 4 || searchMode))) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    }
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        if (zone == Z_TOP && !topControlVisible(topIndex)) zone = Z_SHELF;
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
