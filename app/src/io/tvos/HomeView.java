package io.tvos;

import io.tvos.watching.WatchingSources;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, with translucent panels:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it.
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    weather); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // Shared side edges; vertical margins and content centering live in HomeLayout.
    private static final float MARGIN = HomeLayout.EDGE, CONTENT_R = HomeLayout.RIGHT;
    private static final float SHELF_PAD = HomeLayout.DOCK_PAD, TILE_GAP = HomeLayout.DOCK_GAP;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TOP = 540, ROW_H = 126, CARDS_H = HomeLayout.WEATHER_HEIGHT;
    // "All Apps" sheet.
    private static final int COLUMNS = HomeAccessibility.COLUMNS, VISIBLE_ROWS = 3;
    private static final float GRID_W = HomeAccessibility.GRID_W, GRID_H = HomeAccessibility.GRID_H,
            GRID_GAP = HomeAccessibility.GRID_GAP, GRID_ROW = HomeAccessibility.GRID_ROW,
            GRID_TOP = HomeAccessibility.GRID_TOP;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    /** Search glass shadow radius shared with the cached Shadows renderer. */
    private static final float SHADOW_BLUR = 26;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, SEARCH_GLASS = 8;
    private static final int Z_SHELF = HomeAccessibility.Z_SHELF, Z_DRAWER = HomeAccessibility.Z_DRAWER;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; all recommendation cards use a 5-column portrait grid.
    private static final float L_TABBAR_TOP = HomeLayout.TAB_TOP, L_TABBAR_H = HomeLayout.TAB_HEIGHT;
    private static final float L_BUTTON = HomeLayout.BUTTON, L_BUTTON_GAP = 20, L_BUTTON_CY = HomeLayout.HEADER_CY;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73;
    // Prepared once per supported language; the drawing path never builds a label array.
    private final EnumMap<AppLanguage, String[]> tabLabels = new EnumMap<>(AppLanguage.class);
    private static final String[] TAB_KIND = {null, "movie", null, "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final RoundedImages images = new RoundedImages();
    private final HomeBackground background = new HomeBackground(scene, this::backgroundChanged);
    private final HomeData data;
    private final HomeWidgets widgets;
    private final HomeGenres genres, tvGenres, watchingMenu;
    private final RandomPoster randomPoster = new RandomPoster();
    private final HomeHeaderRenderer header;
    private final HomeHeaderRenderer.Frame headingFrame = new HomeHeaderRenderer.Frame();
    private final HomeAppsRenderer appSurfaces;
    private final HomeAppsRenderer.Frame appFrame = new HomeAppsRenderer.Frame();
    private final HomeNotices notices;
    private final Recs recs;
    private final PosterRenderer posters;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TvInputManager tvInputs;
    private final HomeAccessibility accessibility;
    private final Spoken spoken;
    private final Layer revealBackdrop, sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, searchGlassLayer;
    private int zone = Z_SHELF;

    // At the top: tabs (0 to 2) and icon-only buttons (3 to 5); the three cards
    private int tab, topIndex, cardIndex;
    private final RectF searchGlassBounds = new RectF();
    private boolean searchGlassVisible;
    private final HomeSearchInput searchInput;
    private boolean searchMode;
    private final Spring searchHeadingOpacity = new Spring(0.42f, 1f, 0);
    private String searchQuery = "";
    private List<Recs.Pick> searchResults = new ArrayList<>();
    private int workEpoch;
    private final SearchController searchWork;
    private final RouletteController rouletteWork;
    private final RouletteRenderer rouletteRenderer;
    private final MovieAppLauncher movieApps;
    private final HomeDockEditor dockEditor;

    private int widgetIndex;    // with TalkBack on, the drawer's weather card can take the focus
    private final WatchingCatalog watching;
    private final DockBackdrop dockBackdrop;
    private final DockBanner dockBanner = new DockBanner();
    private int backdropDockIndex = -1, backdropDirection;
    private String backdropFocus;
    private final DockContinue dockContinue;
    private List<Recs.Pick> cards = new ArrayList<>();
    private int cardPage = 0; // five portrait posters per page
    private boolean largePostersWithoutWidgets = false;
    private final Spring[] topFocus = new Spring[11];
    private final Spring rouletteButtonFocus = new Spring(0.26f, 0.85f, 0);
    private final RectF[] topRects = new RectF[11];
    private final Spring[] cardFocus = new Spring[8];
    private final RectF[] cardRects = new RectF[8];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private final NetworkStatus networkStatus;
    private Paint labelPaint;
    private Paint lTab, lTabOn, lCardTitle, lCardMeta, lDate;
    private float contentTop, contentBottom;
    private final HomeReveal reveal = new HomeReveal();
    private final View[] revealContent;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final RectF platterRect = new RectF();
    private int shelfIndex;
    private Spring[] dockSlide = new Spring[0];
    private final Spring dockLift = new Spring(0.3f, 0.72f, 0);
    private int dockLiftIndex = -1;
    private long dockWiggleAt;
    private boolean dockAnimating;

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private float drawerExtraHeight;
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "";
    private long clockMinute = -1;
    private boolean okDown, backDown, vpnPress, vpnBusy;
    private final Spring vpnColor = new Spring(.35f, 1f, 0);
    private final Runnable vpnHold = this::openVpnOnHold;

    private boolean vpnFocused() {
        return running && !sheetOpen && !drawerOpen && zone == Z_TOP
                && topIndex == 7 && topButtonEnabled(2);
    }
    private void openVpnOnHold() {
        if (!okDown || !vpnPress || !vpnFocused()) return;
        okDown = false;
        openVpnApp();
    }
    private void openVpnApp() {
        String pkg = activity.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("vpn_app", "");
        if (pkg.isEmpty()) { showToast(AppStrings.get(R.string.home_vpn_choose_app)); return; }
        Intent launch = HomeApps.launchIntent(activity.getPackageManager(), pkg);
        if (launch == null) { showToast(AppStrings.get(R.string.home_vpn_app_not_found)); return; }
        if (!AppLaunch.start(activity, launch, "VPN launch")) showToast(AppStrings.get(R.string.home_could_not_open_vpn));
    }
    private void toggleVpn() {
        String pkg = activity.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("vpn_app", "");
        if (!VpnControl.PACKAGE.equals(pkg)) { openVpnApp(); return; }
        if (vpnBusy) return;
        vpnBusy = true;
        int epoch = workEpoch;
        Context context = activity.getApplicationContext();
        LOADER.execute(() -> {
            boolean failed = false;
            try { VpnControl.toggle(context); }
            catch (java.io.IOException | RuntimeException e) { AppLog.w("VPN toggle failed: " + e.getClass().getSimpleName()); failed = true; }
            final boolean error = failed;
            handler.post(() -> {
                vpnBusy = false;
                if (error && running && epoch == workEpoch) showToast(AppStrings.get(R.string.home_vpn_toggle_failed));
            });
        });
    }
    private void beginDockEdit() {
        dockLiftIndex = shelfIndex;
        dockWiggleAt = SystemClock.uptimeMillis();
        dockLift.target = 1;
        dockAnimating = true;
        click.open();
        notices.hint(AppStrings.get(R.string.home_dock_edit_hint));
        tilesDirty = true;
        wake();
    }

    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Shadows shadows = new Shadows();
    private final ActionSurface actionSurface = new ActionSurface(scene, shadows);
    // Reused only on the UI drawing thread to avoid Paint allocations per frame.


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
                case SKY: background.draw(c, drawer.value); break;
                case HERO: drawLightTop(c); break;
                case STATUS: {
                    HomeHeaderRenderer.Frame topFrame = headerFrame();
                    header.drawDateWeather(c, topFrame);
                    header.drawButtons(c, topFrame);
                    break;
                }
                case PLATTER: appSurfaces.drawPlatter(c, appFrame()); break;
                case TILES: appSurfaces.drawTiles(c, appFrame()); break;
                case DRAWER: appSurfaces.drawDrawer(c, appFrame()); break;
                case SHEET: appSurfaces.drawSheet(c, appFrame()); break;
                case SEARCH_GLASS: drawSearchGlass(c); break;
                default: notices.draw(c); break;
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
            notices.check();
            updatePalette();
            background.checkNight(data.isNight(System.currentTimeMillis()), running);
            updateCards();      // three new ones every hour
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        widgets = new HomeWidgets(a,data);
        header = new HomeHeaderRenderer(a, data, actionSurface, shadows);
        appSurfaces = new HomeAppsRenderer(a, scene, shadows, widgets, images);
        recs = Recs.get(a);
        posters = new PosterRenderer(recs, shadows, images, cardRects.length);
        dockBackdrop = new DockBackdrop(activity, this::showDockBackdrop);
        dockContinue = new DockContinue(activity, this::dockContinueChanged);
        watching = new WatchingCatalog(activity, () -> {
            if (running) updateDockBackdrop();
            if (running && tab == 2 && !searchMode) {
                updateCards();
                logWatchingCards();
                wake();
            }
        });
        searchWork = new SearchController(handler, recs, () -> running && searchMode,
                this::acceptSearchResults);
        rouletteWork = new RouletteController(handler, recs, () -> running,
                () -> tabEnabled(4), () -> tab == 4,
                () -> { heroDirty = true; wake(); }, this::finishRoulette);
        accessibility = new HomeAccessibility(a, data, new HomeAccessibility.Host() {
            @Override public void read(HomeAccessibility.State state) { readAccessibilityState(state); }
            @Override public void wake() { HomeView.this.wake(); }
            @Override public void focus(int group, int index) { focusAccessibleItem(group, index); }
            @Override public void activate() { HomeView.this.activate(); }
        });
        spoken = new Spoken(this, accessibility);
        rouletteRenderer = new RouletteRenderer(rouletteWork, recs, scene, shadows, actionSurface, images);
        movieApps = new MovieAppLauncher(a, this, LOADER, handler, new MovieAppLauncher.Host() {
            @Override public boolean running() { return running; }
            @Override public int epoch() { return workEpoch; }
            @Override public void toast(String text) { showToast(text); }
        });
        dockEditor = new HomeDockEditor(handler, new HomeDockEditor.Host() {
            @Override public boolean eligible() { return okDown && !sheetOpen && zone == Z_SHELF && !shelf.isEmpty(); }
            @Override public List<HomeApps.Item> items() { return shelf; }
            @Override public int index() { return shelfIndex; }
            @Override public void begin() { beginDockEdit(); }
            @Override public void move(int from, int to) { shiftDockTiles(from, to); }
            @Override public void finish(List<HomeApps.Item> restore) { finishDockTiles(restore); }
            @Override public void saved() { click.open(); }
            @Override public void canceled() { click.move(); }
        });
        click = new Click(a);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        setBackgroundColor(0xFF000000);
        revealBackdrop = new Layer(a, SKY);
        revealBackdrop.setVisibility(INVISIBLE);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        searchGlassLayer = new Layer(a, SEARCH_GLASS);
        revealContent = new View[] {sky, heroLayer};
        notices = new HomeNotices(a, scene, LOADER, handler, toastLayer::invalidate, this::wake, this::announceForAccessibility);
        searchInput = new HomeSearchInput(a,this,searchGlassLayer,new HomeSearchInput.Host(){
            @Override public boolean running(){return running;}
            @Override public String query(){return searchQuery;}
            @Override public void queryChanged(String query){searchQueryChanged(query);}
            @Override public void restore(){restoreSearchRequest();}
            @Override public void dismissed(){searchInputDismissed();}
            @Override public void paused(){closeSearch();}
            @Override public void syncGlass(View panel){syncSearchGlass(panel);}
        });
        genres = createGenres(1);
        tvGenres = createGenres(3);
        watchingMenu = createWatchingMenu();
        // Hardware layers on the GPU: only the one that changed is redrawn; moving, fading and scaling stay cheap.
        // Not on Android 9 to 11: on older GPUs (a Mi Box 3's Mali-450) redrawing and compositing a big layer on every
        // frame cost more than drawing straight to the screen (measured: focus on the picks 33 → 12 ms a frame, the
        // drawer 19 → 17 ms).
        for (View v : new View[] {heroLayer, status, platter, tiles, drawerLayer, sheetLayer}) v.setLayerType(Ui.LEGACY ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        for (View v : new View[] {revealBackdrop, sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer, searchGlassLayer}) {
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
        background.checkNight(data.isNight(System.currentTimeMillis()), false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        header.resume();
        if (running) return;
        running = true;
        genres.resume(); tvGenres.resume(); watchingMenu.resume();
        if (topButtonEnabled(5) || topButtonEnabled(2)) networkStatus.start();
        vpnColor.snap(networkStatus.vpn() ? 1 : 0);
        background.checkNight(data.isNight(System.currentTimeMillis()), false);
        syncVisibleTabs();
        // SettingsActivity runs separately; the old button rectangles remain cached.
        // Repack the visible top actions immediately after returning from settings.
        layoutLight();
        resumedAt = SystemClock.uptimeMillis();
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        watching.start();
        if (Prefs.categoryCount() == 0) DockBackdrop.requestPermission(activity);
        updateDockBackdrop();
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        notices.check();
        reload();
        updatePalette();
        updateCards();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        updateMotion(); // Keep a staged dream return applied before the first resumed draw.
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
        if (tab == 4 && tabEnabled(4) && rouletteWork.picks().isEmpty() && !rouletteWork.loading()) loadRoulette();
        searchWork.resume(searchQuery);
    }

    private HomeGenres createGenres(int category) {
        return new HomeGenres(this, handler, recs.genres(), new HomeGenres.Host() {
            @Override public String kind() { return category == 3 ? "tv" : "movie"; }
            @Override public boolean eligible() {
                return running && okDown && tab == category && !searchMode && !sheetOpen && !drawerOpen
                        && !dockEditor.editing() && (zone == Z_TOP && topIndex == category);
            }
            @Override public RectF anchor() { return topRects[category]; }
            @Override public Paint anchorPaint() { return lTabOn; }
            @Override public void visibilityChanged() { heroDirty = true; heroLayer.invalidate(); wake(); }
            @Override public void opened() { okDown = false; dockEditor.cancelHold(); click.open(); }
            @Override public void changed() {
                layoutLight();
                if (tab == category && !searchMode) {
                    cardPage = cardIndex = 0;
                    updateCards(); retarget(false);
                }
                heroDirty = statusDirty = true;
                wake();
            }
            @Override public void message(String text) { showToast(text); }
        });
    }

    private HomeGenres createWatchingMenu() {
        return new HomeGenres(this, handler, new HomeGenres.Host() {
            @Override public boolean eligible() {
                return running && okDown && tab == 2 && !searchMode && !sheetOpen && !drawerOpen
                        && !dockEditor.editing() && zone == Z_TOP && topIndex == 2;
            }
            @Override public RectF anchor() { return topRects[2]; }
            @Override public Paint anchorPaint() { return lTabOn; }
            @Override public void visibilityChanged() { heroDirty = true; heroLayer.invalidate(); wake(); }
            @Override public void opened() { okDown = false; dockEditor.cancelHold(); click.open(); }
            @Override public void changed() {
                cardPage = cardIndex = 0;
                updateCards(); retarget(false); heroDirty = true; wake();
            }
            @Override public void message(String text) { showToast(text); }
        }, new HomeGenres.Choices() {
            @Override public String label() { return AppStrings.get(R.string.home_tab_watching); }
            @Override public String heading() { return AppStrings.get(R.string.home_watching_services); }
            @Override public List<HomeGenres.Choice> items() {
                List<HomeGenres.Choice> items = new ArrayList<>();
                for (String source : watching.sources()) items.add(new HomeGenres.Choice(source, watching.sourceLabel(source)));
                return items;
            }
            @Override public String selected() { return watching.source(); }
            @Override public void select(String source) { watching.selectSource(source); }
        });
    }

    void prepareDreamReturn() {
        if (drawerOpen || sheetOpen || searchMode || dockEditor.editing()) return;
        reveal.prepare(reducedDreamMotion());
        updateMotion();
    }

    void cancelDreamReturn() {
        reveal.cancel();
        updateMotion();
    }

    private boolean reducedDreamMotion() {
        return getContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("reduce_motion", false) || !android.animation.ValueAnimator.areAnimatorsEnabled();
    }

    void revealFromDream() {
        if (!running || drawerOpen || sheetOpen || searchMode || dockEditor.editing()) return;
        reveal.start(SystemClock.uptimeMillis(), reducedDreamMotion());
        updateMotion();
        wake();
    }

    void pause() {
        if (!running) return;
        running = false;
        randomPoster.clear();
        genres.pause(); tvGenres.pause(); watchingMenu.pause();
        if (!reveal.prepared()) reveal.cancel();
        updateMotion();
        networkStatus.stop();
        handler.removeCallbacks(vpnHold); vpnPress = false;
        workEpoch++;
        searchWork.pause(searchMode);
        cancelRouletteWork();
        dockEditor.pause();
        okDown = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(reloadSoon);
        data.removeListener(this);
        recs.removeListener(this);
        watching.stop();
        dockBackdrop.pause();
        dockContinue.hide();
        backdropDockIndex = -1; backdropDirection = 0; backdropFocus = null;
        recs.setPosterWindow(java.util.Collections.emptyList());
        rouletteWork.invalidatePosters();
        images.clear();
        appSurfaces.clear();
        header.clearFrame();
        posters.clear();
        shadows.clear();
        widgets.clear();
        accessibility.clear();
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        searchInput.dismiss();
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
        if (searchMode) { cancelSearchWork(); searchMode = false; searchResults.clear(); }
        if (Prefs.firstCategory() >= 0 && tab != Prefs.firstCategory()) selectTab(Prefs.firstCategory());
        updateCards();
        retarget(!animated);
        motionDirty = true;
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "search", AppStrings.get(R.string.home_search), "search"));
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", AppStrings.get(R.string.home_live_tv), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "vpn", "VPN", "vpn"));
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", AppStrings.get(R.string.home_settings), "settings"));
    }

    private boolean topButtonEnabled(int index) { return Prefs.topButtonEnabled(index); }
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

    private String[] tabs() {
        AppLanguage language = AppLanguage.current();
        String[] labels = tabLabels.get(language);
        if (labels == null) {
            labels = new String[] {AppStrings.get(R.string.home_tab_new, language), AppStrings.get(R.string.home_tab_movies, language), AppStrings.get(R.string.home_tab_watching, language), AppStrings.get(R.string.home_tab_shows, language), "🎲"};
            tabLabels.put(language, labels);
        }
        labels[1] = genres.label();
        labels[3] = tvGenres.label();
        return labels;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        background.checkNight(data.isNight(System.currentTimeMillis()), running);
        heroDirty = statusDirty = drawerDirty = true;
        platter.invalidate();
    }

    private void dockContinueChanged() {
        heroDirty = true;
        if (running) { heroLayer.invalidate(); wake(); }
    }

    private void updateDockBackdrop() {
        if (!running) return;
        if (Prefs.categoryCount() != 0 || searchMode) { dockContinue.hide(); dockBackdrop.pause(); backdropDockIndex = -1; backdropFocus = null; return; }
        if (sheetOpen || drawerOpen || zone != Z_SHELF || shelfIndex < 0 || shelfIndex >= shelf.size()) return;
        HomeApps.Item item = shelf.get(shelfIndex);
        String pkg = item.kind == HomeApps.APP ? item.pkg
                : item.kind == HomeApps.SETTINGS || item.kind == HomeApps.ANDROID_SETTINGS ? "io.tvos" : null;
        if (getContext().getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(DockContinue.KEY, false)
                && "kinopub".equals(WatchingSources.id(pkg))) {
            dockBackdrop.focus(null);
            dockContinue.show(watching.forSource("kinopub"));
            return;
        }
        dockContinue.hide();
        int direction = backdropDockIndex < 0 ? 0 : Integer.compare(shelfIndex, backdropDockIndex);
        backdropDockIndex = shelfIndex;
        if (pkg != null) {
            if (!pkg.equals(backdropFocus)) { backdropDirection = direction; backdropFocus = pkg; }
            dockBackdrop.focus(pkg);
        }
    }

    private void showDockBackdrop(DockBackdrop.Artwork artwork) {
        if (!running || Prefs.categoryCount() != 0 || searchMode) return;
        boolean animate = !activity.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("reduce_motion", false);
        dockBanner.show(artwork, animate);
        heroDirty = true; heroLayer.invalidate();
        background.show(artwork == null ? null : artwork.background, animate, backdropDirection);
        wake();
    }

    private void logWatchingCards() {
        android.util.Log.i("tvOS-WatchNext", "Home source=" + watching.source()
                + ", page=" + cardPage + ": " + WatchingItems.trace(cards));
    }

    void refreshWatching() { watching.refresh(); dockBackdrop.pause(); updateDockBackdrop(); }

    @Override public void onRecs() {
        wake();
        updateCards();
    }

    private void invalidateAll() {
        for (View v : new View[] {revealBackdrop, sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(revealBackdrop, width, height);
        measure(sky, width, height);
        measure(heroLayer, width, Math.round(HomeLayout.CARDS_LAYER_HEIGHT * kk));
        measure(status, width, Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round((HomeLayout.HEIGHT - HomeLayout.DOCK_LAYER_TOP) * kk));
        measure(tiles, width, Math.round((HomeLayout.HEIGHT - HomeLayout.DOCK_LAYER_TOP) * kk));
        measure(drawerLayer, width, Math.round((HomeLayout.HEIGHT - 420) * kk));
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
        revealBackdrop.layout(0, 0, width, height);
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(HomeLayout.DOCK_LAYER_TOP * kk));
        place(tiles, 0, Math.round(HomeLayout.DOCK_LAYER_TOP * kk));
        place(drawerLayer, 0, Math.round(420 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
        searchGlassLayer.layout(0, 0, width, height);
        for (View layer : revealContent) {
            layer.setPivotX(width / 2f - layer.getLeft());
            layer.setPivotY(height / 2f - layer.getTop());
        }
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        background.resize(w, h);
        scene.setSize(w, h);
        makePaints();
        appSurfaces.invalidateLabels();
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutDrawer();
        layoutLight();
        updateCards();
        motionDirty = true;
        wake();
    }

    private void makePaints() {
        widgets.resize(k);
        header.resize(k);
        appSurfaces.resize(k);
        notices.resize(k, w);
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        shadow.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * k, BlurMaskFilter.Blur.NORMAL));
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
        int focusedPoster = focusedPoster();
        if (posters.focus(focusedPoster, cardPage, tab, up)) heroDirty = true;
        boolean marqueeActive = posters.marqueeActive(up);
        if (marqueeActive || rouletteWork.spinning()) heroDirty = true;
        boolean dockMoving = step(dockSlide, dt) | dockLift.step(dt);
        if (dockEditor.editing() || dockLift.value > 0.001f) dockMoving = true;
        dockAnimating = dockMoving;
        if (!dockEditor.editing() && !dockMoving) dockLiftIndex = -1;
        if (dockMoving) tilesDirty = true;
        boolean tilesMoving = step(tileFocus, dt) | dockMoving;
        boolean searchHeadingMoving = tickSearchHeading(dt);
        vpnColor.target = networkStatus.vpn() ? 1 : 0;
        if (reducedDreamMotion()) vpnColor.snap(vpnColor.target);
        boolean vpnMoving = vpnColor.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt) | rouletteButtonFocus.step(dt);
        if (topMoving || vpnMoving) status.invalidate();
        boolean bannerMoving = Prefs.categoryCount() == 0 && !searchMode && dockBanner.tick(up);
        boolean heroMoving = topMoving | cardsMoving | bannerMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = notices.tick(up);
        boolean bgFading = background.tickFade(up, drawerLayer.getVisibility() == VISIBLE || notices.visible());
        boolean nightMoving = background.tickNight(up);

        boolean revealing = reveal.tick(up);
        if (drawerMoving || sheetMoving || motionDirty || revealing) updateMotion();
        // Opening and closing the drawer only moves the cached layers: redrawing the glass on every frame made the TV
        // drop frames (measured: ~50 ms of GPU per frame). The app tiles have no glass; the top buttons fade out as
        // they rise; the drawer is drawn resting in place; the glass shelf is redrawn once the drawer stops, so the
        // glass shows the background where it ended up.
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || searchHeadingMoving || (bgFading && Prefs.categoryCount() == 0)) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading) { sky.invalidate(); revealBackdrop.invalidate(); }
        if (bgFading || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            status.invalidate();
            heroLayer.invalidate(); // the header date/time changes even when recommendations are enabled
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || bgFading || nightMoving || searchHeadingMoving
                || vpnMoving || notices.busy() || rouletteWork.spinning() || dockMoving || revealing;
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else {
            // Short text needs only the normal clock tick. Long text wakes at its dwell deadline,
            // then keeps the existing 50 ms marquee cadence without polling during the wait.
            long delay = Math.min(posters.nextDelay(up), reveal.delay(up));
            if (Prefs.categoryCount() == 0 && !searchMode) delay = Math.min(delay, dockBanner.nextDelay(up));
            Choreographer.getInstance().postFrameCallbackDelayed(this, delay);
        }
    }

    private int focusedPoster() {
        return !sheetOpen && picksOn() && (searchMode || tab != 4) && zone == Z_CARDS
                && cardIndex >= 0 && cardIndex < cards.size() ? cardIndex : -1;
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
        show(heroLayer, top * reveal.opacity(), lift);
        show(status, top, lift);
        float dockOffset = (h - platterRect.top + 20 * k) * reveal.remaining();
        show(platter, home, lift - d * drawerExtraHeight + dockOffset);
        show(tiles, home, lift - d * drawerExtraHeight + dockOffset);
        revealBackdrop.setVisibility(reveal.remaining() > 0 ? VISIBLE : INVISIBLE);
        sky.setAlpha(reveal.opacity());
        float contentScale = reveal.scale();
        for (View layer : revealContent) {
            layer.setScaleX(contentScale);
            layer.setScaleY(contentScale);
        }
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
        if (minute == clockMinute || labelPaint == null) return false;
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

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    // MARK: - Night look

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private void backgroundChanged() {
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        revealBackdrop.invalidate();
        platter.invalidate();
    }

    private float dark() {
        return background.night();
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
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lDate.setColor(tone(L_DIM, 0xFF98989D));
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

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

    // MARK: - Shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        appSurfaces.invalidateDock();
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        if (dockEditor.editing()) return; // Do not overwrite a drag with an asynchronous refresh.
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
        float tw = HomeLayout.dockTileWidth(n, k);
        float th = tw / HomeLayout.DOCK_ASPECT;
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float bottom = HomeLayout.dockBottom(h, k);
        float top = bottom - th - 2 * pad;
        platterRect.set(left - pad, top, left + width + pad, bottom);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        appSurfaces.clear();
        header.clearFrame();
        layoutLight(); // Dock height changes the free space for posters and roulette.
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
        float tw = Math.max(1, (right - left - Math.max(0, n - groups) * gap
                - (groups - 1) * groupGap) / Math.max(1, n));
        float rowHeight = Math.max(ROW_H * k, Math.min(168 * k, tw * ROW_H / 230f));
        drawerExtraHeight = n == 0 ? 0 : rowHeight - ROW_H * k;
        float x = left, top = ROW_TOP * k - drawerExtraHeight;
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + rowHeight);
            x += tw + gap;
        }
        float cardsTop = HomeLayout.weatherTop(h, k);
        weatherCard.set(left, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = motionDirty = true;
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

    private HomeAppsRenderer.Frame appFrame() {
        appFrame.shelf = shelf; appFrame.tileRects = tileRects; appFrame.tileFocus = tileFocus; appFrame.dockSlide = dockSlide;
        appFrame.dockLiftIndex = dockLiftIndex; appFrame.dockLift = dockLift.value; appFrame.dockWiggleAt = dockWiggleAt;
        appFrame.drawerItems = drawerItems; appFrame.drawerRects = drawerRects; appFrame.drawerFocus = drawerFocus;
        appFrame.drawerInputs = drawerInputs; appFrame.weatherCard = weatherCard; appFrame.weatherBottom = weatherBottom();
        appFrame.others = others; appFrame.gridFocus = gridFocus; appFrame.gridIndex = gridIndex; appFrame.sheetOpen = sheetOpen;
        appFrame.focusedCell = focusedCell; appFrame.scroll = scroll.value; appFrame.platterRect = platterRect;
        appFrame.k = k; appFrame.night = background.night(); appFrame.w = w; appFrame.h = h;
        return appFrame;
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        updateDockBackdrop();
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        float rouletteTarget = !sheetOpen && tab == 4 && zone == Z_CARDS ? 1 : 0;
        boolean heroChanged = rouletteButtonFocus.target != rouletteTarget;
        rouletteButtonFocus.target = rouletteTarget;
        if (snap) rouletteButtonFocus.snap(rouletteTarget);
        heroChanged |= aim(cardFocus, !sheetOpen && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || topChanged;
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
        appSurfaces.invalidateLabels();
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

    // MARK: - Toasts

    void showToast(String text) { notices.add(text); }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final int epoch = workEpoch;
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                if (!running || epoch != workEpoch) return;
                setShelf(s);
                setDrawerItems(d);
                if (!sheetOpen) setOthers(o);
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    private boolean dockContinueKey(KeyEvent e) {
        if (!dockEditor.editing() && !sheetOpen && !drawerOpen && !searchMode && dockContinue.key(e, zone == Z_SHELF,
                () -> { zone = Z_TOP; topIndex = availableTopButton(5); retarget(false); },
                pick -> { if (!watching.open(pick)) showToast(AppStrings.get(R.string.home_watching_open_failed)); })) {
            okDown = false; dockEditor.cancelHold(); return true;
        }
        return false;
    }

    boolean onKey(KeyEvent e) {
        wake();
        if (dockContinueKey(e)) return true;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int genreKey = e.getKeyCode();
        if (genreKey != KeyEvent.KEYCODE_DPAD_CENTER && genreKey != KeyEvent.KEYCODE_ENTER
                && genreKey != KeyEvent.KEYCODE_NUMPAD_ENTER) { genres.cancelHold(); tvGenres.cancelHold(); watchingMenu.cancelHold(); handler.removeCallbacks(vpnHold); }
        if (dockEditor.editing()) {
            boolean handled = dockEditor.onKey(e);
            if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || e.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER) okDown = false;
            return handled;
        }
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
                        vpnPress = vpnFocused();
                        if (vpnPress) handler.postDelayed(vpnHold, HomeDockEditor.HOLD_MS);
                        dockEditor.arm();
                        genres.arm(); tvGenres.arm(); watchingMenu.arm();
                    }
                } else {
                    handler.removeCallbacks(vpnHold);
                    dockEditor.cancelHold();
                    genres.released(); tvGenres.released(); watchingMenu.released();
                    if (okDown && !e.isCanceled() && (!vpnPress || vpnFocused()) && spoken.keyClick()) activate();
                    vpnPress = false;
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

    private void shiftDockTiles(int i, int j) {
        HomeApps.Item item = shelf.get(i);
        shelf.set(i, shelf.get(j));
        shelf.set(j, item);
        Spring f = tileFocus[i]; tileFocus[i] = tileFocus[j]; tileFocus[j] = f;
        Spring slide = dockSlide[i]; dockSlide[i] = dockSlide[j]; dockSlide[j] = slide;
        float d = tileRects[i].left - tileRects[j].left;
        dockSlide[j].value += d;
        dockSlide[i].value -= d;
        appSurfaces.swapDock(i, j, shelf.size());
        shelfIndex = dockLiftIndex = j;
        retarget(false);
        tilesDirty = true;
        wake();
        click.move();
    }

    private void finishDockTiles(List<HomeApps.Item> before) {
        dockLift.target = 0;
        if (before != null && before.size() == shelf.size()) {
            HomeApps.Item selected = shelf.get(shelfIndex);
            int n = shelf.size();
            Spring[] focus = new Spring[n], slide = new Spring[n];
            appSurfaces.restoreDock(before, shelf);
            for (int a = 0; a < n; a++) {
                int b = shelf.indexOf(before.get(a));
                if (b < 0) { b = a; }
                focus[a] = tileFocus[b];
                slide[a] = dockSlide[b];
                slide[a].value += tileRects[b].left - tileRects[a].left;
            }
            shelf = before;
            tileFocus = focus;
            dockSlide = slide;
            shelfIndex = dockLiftIndex = shelf.indexOf(selected);
            if (shelfIndex < 0) shelfIndex = dockLiftIndex = 0;
        }
        retarget(false);
        tilesDirty = true;
        wake();
    }

    private void back() {
        if (rouletteWork.spinning()) cancelRouletteWork();
        if (dockEditor.editing()) { dockEditor.finish(false); click.move(); return; }
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
        if (rouletteWork.spinning()) return; // Keep remote focus on the running roulette until it stops.
        if (dockEditor.editing()) { if (dx != 0) dockEditor.move(dx); return; }
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else {
                    if (dy <= 0 || drawerItems.isEmpty()) return;
                    openDrawer();
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
                } else if (accessibility.talkBack() && !widgets().isEmpty()) {
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
        if (rouletteWork.spinning()) return;
        HomeApps.Item it;
        RectF r;
        if (!sheetOpen && zone == WIDGETS) return;   // a widget is only read
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
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
                ProfileActivity.open(activity, background.profilePixels());
                return;
            }
            if (topIndex == 5) { openSearch(); return; }
            it = lightButtons.get(topIndex - 5);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            if (tab == 4 && !searchMode) {
                if (rouletteWork.finished() && !rouletteWork.picks().isEmpty()) movieApps.choose(rouletteWork.picks().get(rouletteWork.winner()));
                else spinRoulette();
                return;
            }
            Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
            if (p == null) return;
            if (randomPoster.placeholder(p)) {
                randomPoster.load(recs.genres(), tab == 3 ? "tv" : "movie", randomGenre(),
                        tab == 0 ? baseCatalog() : null,
                        () -> { updateCards(); wake(); },
                        () -> { showToast(AppStrings.get(R.string.home_genre_failed)); updateCards(); wake(); });
            } else if (p.watchingKey != null) {
                if (!watching.open(p)) showToast(AppStrings.get(R.string.home_watching_open_failed));
            } else movieApps.choose(p);
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
            toggleVpn();
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
        if (!opened) showToast(AppStrings.get(R.string.home_open_error_prefix) + it.label + ".");
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
        if (!running || !searchInput.showing()) return;
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

    private void beginSearch() {
        randomPoster.clear();
        cancelRouletteWork();
        setSearchInputOpen(true);
        if (!searchMode) {
            searchResults = new ArrayList<>();
            cardPage = cardIndex = 0;
        }
        searchMode = true;
    }

    private void restoreSearchRequest() {
        searchWork.restore(searchQuery, !searchResults.isEmpty());
    }

    private void openSearch() {
        if (searchInput.showing()) return;
        beginSearch();
        zone = Z_TOP;
        topIndex = availableTopButton(5);
        layoutLight();
        updateCards();
        retarget(false);
        heroDirty = statusDirty = true;
        wake();
        searchInput.open(searchQuery,background.night()>.5f,L_BUTTON_CY*k);
    }

    private void searchQueryChanged(String query) {
        searchQuery = query;
        searchResults = new ArrayList<>();
        cardPage = cardIndex = 0;
        layoutLight();
        updateCards();
        heroDirty = statusDirty = true;
        wake();
        searchWork.changed(query);
    }

    private void searchInputDismissed() {
        searchGlassVisible = false;
        setSearchInputOpen(false);
        searchGlassLayer.animate().cancel();
        searchGlassLayer.setAlpha(1f);
        searchGlassLayer.setScaleX(1f);
        searchGlassLayer.setScaleY(1f);
        searchGlassLayer.setTranslationY(0f);
        searchGlassLayer.setVisibility(INVISIBLE);
        searchWork.flush();
        zone = searchResults.isEmpty() ? Z_TOP : Z_CARDS;
        if (zone == Z_TOP) topIndex = availableTopButton(5);
        retarget(false);
        heroDirty = statusDirty = true;
        wake();
    }

    private void cancelSearchWork() {
        searchWork.cancel();
    }

    private void acceptSearchResults(List<Recs.Pick> found) {
        searchResults = found;
        cardPage = cardIndex = 0;
        layoutLight();
        updateCards();
        retarget(false);
        heroDirty = statusDirty = true;
        wake();
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

    // MARK: - Accessibility navigation; virtual-node presentation lives in HomeAccessibility.

    private static final int GRID = HomeAccessibility.GRID, WIDGETS = HomeAccessibility.WIDGETS;

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return spoken; }

    private void readAccessibilityState(HomeAccessibility.State s) {
        s.sheetOpen = sheetOpen; s.searchMode = searchMode; s.drawerOpen = drawerOpen;
        s.picksOn = picksOn(); s.weatherBottom = weatherBottom(); s.spinning = rouletteWork.spinning();
        s.tab = tab; s.zone = zone; s.gridIndex = gridIndex; s.topIndex = topIndex;
        s.cardIndex = cardIndex; s.shelfIndex = shelfIndex; s.drawerIndex = drawerIndex; s.widgetIndex = widgetIndex;
        s.others = others; s.shelf = shelf; s.drawerItems = drawerItems; s.lightButtons = lightButtons; s.cards = cards;
        s.topRects = topRects; s.cardRects = cardRects; s.tileRects = tileRects; s.drawerRects = drawerRects;
        s.weatherCard = weatherCard; s.tabs = tabs(); s.networkState = networkStatus.state();
        s.k = k; s.h = h; s.scroll = scroll.target;
        s.contentTop = contentTop; s.contentBottom = contentBottom;
        s.heroY = heroLayer.getTranslationY(); s.statusY = status.getTranslationY(); s.tilesY = tiles.getTranslationY();
        for (int i = 0; i < s.topVisible.length; i++) s.topVisible[i] = topControlVisible(i);
    }

    private void focusAccessibleItem(int group, int i) {
        if (group == GRID) {
            gridIndex = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        switch (group) {
            case Z_DRAWER:
                if (!drawerOpen) openDrawer();
                drawerIndex = i;
                break;
            case WIDGETS:
                widgetIndex = i;
                break;
            case Z_TOP:
                if (drawerOpen) closeDrawer();
                topIndex = i;
                if (i < 5) selectTab(i);
                break;
            case Z_CARDS:
                if (drawerOpen) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
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

    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawerOpen) return out;
        if (weatherBottom()) out.add(0);
        return out;
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    /** Date and weather are the only optional left-side top-panel widgets. */
    private boolean noTopWidgets() {
        return !showHeaderPart("show_date") && !weatherTop();
    }

    private void layoutLight() {
        if (lTab == null) return;
        largePostersWithoutWidgets = noTopWidgets();
        // Align the resting tab capsule with the common outer edge; text keeps its inner padding.
        float pad = 26 * k;
        float tabTop = (largePostersWithoutWidgets ? HomeLayout.TOP : L_TABBAR_TOP) * k;
        float tabBottom = tabTop + L_TABBAR_H * k;
        float x = MARGIN * k;
        for (int i : Prefs.categoryOrder()) {
            if (!tabEnabled(i)) { topRects[i].setEmpty(); continue; }
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        layoutTopActions(b, right, cy);
        float regularW = ((CONTENT_R - MARGIN) * k - 5 * 18 * k) / 6f;
        // Keep compact-header posters 10% larger; center poster + all captions as one block.
        float posterW = regularW * (largePostersWithoutWidgets ? 1.10f : 1f);
        float posterH = posterW * 1.5f;
        contentTop = Math.max(tabBottom, (HomeLayout.TOP + L_BUTTON) * k);
        contentBottom = platterRect.top;
        float captionHeight = HomeLayout.POSTER_META_BASELINE * k + lCardMeta.descent() + 1.5f * k;
        float top = HomeLayout.centeredTop(contentTop, contentBottom, posterH + captionHeight);
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
                float unit = k * HomeLayout.BUTTON_SCALE;
                clockMeasure.setTextSize(27 * unit);
                Paint.FontMetrics fm = clockMeasure.getFontMetrics();
                float glyphHeight = fm.descent - fm.ascent;
                float textInset = Math.max(5 * unit, (b - glyphHeight) / 2f);
                float avatarDiameter = b - 10 * unit;
                float capsuleWidth = textInset + 5 * unit + clockMeasure.measureText(clockText) + 10 * unit
                        + avatarDiameter + 5 * unit + 5 * unit;
                topRects[9].set(right - capsuleWidth, cy - b / 2f, right, cy + b / 2f);
                right = topRects[9].left - L_BUTTON_GAP * k;
            } else {
                topRects[control].set(right - b, cy - b / 2, right, cy + b / 2);
                right -= b + L_BUTTON_GAP * k;
            }
        }
    }

    private List<Recs.Pick> baseCatalog() {
        if (tab == 2) return watching.picks();
        if (tab == 1 && genres.movies() != null) return genres.movies();
        if (tab == 3 && tvGenres.movies() != null) return tvGenres.movies();
        return recs.catalog(TAB_KIND[tab]);
    }
    private int randomGenre() { return tab == 1 ? genres.selectedId() : tab == 3 ? tvGenres.selectedId() : 0; }
    private List<Recs.Pick> currentCatalog() {
        if (searchMode) return searchResults;
        if (tab == 4) return java.util.Collections.emptyList();
        if (tab == 2) return watching.picks();
        List<Recs.Pick> base = baseCatalog();
        List<Recs.Pick> all = new ArrayList<>(base.subList(0, Math.min(24, base.size())));
        if (tab != 4 && !all.isEmpty())
            all.add(randomPoster.tail(tab + ":" + randomGenre() + ":" + Ui.language, tab == 3 ? "tv" : "movie"));
        return all;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        if (labelPaint == null) return;
        if (!searchMode && Prefs.firstCategory() < 0) {
            cards.clear();
            recs.setPosterWindow(java.util.Collections.emptyList());
            heroDirty = true;
            return;
        }
        if (tab == 4 && !searchMode) {
            cards.clear();
            if (!rouletteWork.spinning() && !rouletteWork.finished()) {
                rouletteWork.invalidatePosters();
                recs.setPosterWindow(java.util.Collections.emptyList());
            }
            cardPage = 0;
            heroDirty = true;
            return;
        }
        List<Recs.Pick> all = currentCatalog();
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
            if (searchMode || tab <= 2) for (Recs.Pick pick : cards) if (pick.id > 0 && pick.watchingKey == null) recs.requestDuration(pick);
            Backdrop b = cards.isEmpty() || cards.get(0).id <= 0 ? null : recs.backdrop(cards.get(0));
            if (cards.isEmpty() || b != null) background.show(b);
        }
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (searchMode) { cancelSearchWork(); searchMode = false; searchResults.clear(); }
        if (index == tab || !tabEnabled(index)) return;
        if (tab == 4 || index == 4) {
            rouletteWork.reset();
        }
        randomPoster.clear();
        tab = index;
        if (tab == 2) watching.requestPermission(activity);
        layoutLight();
        cardPage = 0;
        cardIndex = 0;
        updateCards();
        if (tab == 4 && tabEnabled(4) && rouletteWork.picks().isEmpty() && !rouletteWork.loading()) loadRoulette();
        heroDirty = true;
    }

    private void cancelRouletteWork() {
        rouletteWork.cancel();
    }

    private void loadRoulette() {
        rouletteWork.load();
    }

    private void finishRoulette() {
        // The winning poster becomes the focused, actionable card.
        zone = Z_CARDS;
        cardIndex = 0;
        retarget(false);
        heroDirty = true;
        wake();
    }

    private void spinRoulette() {
        if (rouletteWork.spinning()) return;
        if (!rouletteWork.spin()) {
            showToast(AppStrings.get(R.string.home_movies_loading_toast));
            return;
        }
        zone = Z_CARDS;
        cardIndex = 0;
        retarget(false);
        heroDirty = true;
        wake();
    }

    private void drawLightTop(Canvas c) {
        posters.begin(k, background.night(), tab, cardPage, searchMode, zone == Z_CARDS && !sheetOpen,
                lCardTitle, lCardMeta);
        HomeHeaderRenderer.Frame heading = headerFrame();
        if (!picksOn()) {
            if (dockContinue.visible()) dockContinue.draw(c, k, contentBottom - 20 * k, background.night(), noTopWidgets());
            else if (!searchMode && Prefs.categoryCount() == 0) dockBanner.draw(c, k, noTopWidgets());
            return;
        }
        header.drawTabs(c, heading);
        if (tab == 4 && !searchMode) {
            rouletteRenderer.draw(c, k, background.night(), lCardTitle, rouletteButtonFocus.value,
                    zone == Z_CARDS && !sheetOpen ? cardFocus[0].value : 0f, contentTop, contentBottom);
            return;
        }
        if (tab == 2 && !searchMode && cards.isEmpty()) {
            c.drawText(watching.emptyText(), MARGIN * k, contentTop + 55 * k, lCardMeta);
        }
        int top = -1;
        for (int i = 0; i < cards.size(); i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < cards.size(); i++) if (i != top) posters.draw(c, cards.get(i), cardRects[i], cardFocus[i].value, i);
        if (top >= 0) posters.draw(c, cards.get(top), cardRects[top], cardFocus[top].value, top);
    }

    /** Date and weather use the same top inset as the right-side controls. */
    private boolean showHeaderPart(String key) {
        return getContext().getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(key, true);
    }

    private HomeHeaderRenderer.Frame headerFrame() {
        applyTone();
        headingFrame.k = k; headingFrame.night = background.night(); headingFrame.headingOpacity = searchHeadingOpacity.value;
        headingFrame.vpnTint = Ui.clamp(vpnColor.value);
        headingFrame.hiddenTab = genres.hidesAnchor() ? 1 : tvGenres.hidesAnchor() ? 3 : watchingMenu.hidesAnchor() ? 2 : -1;
        headingFrame.tab = tab; headingFrame.networkState = networkStatus.state(); headingFrame.tabs = tabs();
        headingFrame.clockText = clockText; headingFrame.dateText = dateText; headingFrame.searchQuery = searchQuery;
        headingFrame.topRects = topRects; headingFrame.topFocus = topFocus; headingFrame.lightButtons = lightButtons;
        headingFrame.lDate = lDate; headingFrame.lTab = lTab; headingFrame.lTabOn = lTabOn;
        headingFrame.showDate = showHeaderPart("show_date"); headingFrame.weatherTop = weatherTop();
        headingFrame.searchMode = searchMode; headingFrame.largePostersWithoutWidgets = largePostersWithoutWidgets;
        return headingFrame;
    }

    private boolean picksOn() {
        return (Recs.hasKey(getContext()) || (!searchMode && Prefs.categoryEnabled(2)))
                && (searchMode || Prefs.categoryCount() > 0);
    }

    private void openNetworkSettings() {
        for (String action : new String[] {android.provider.Settings.ACTION_WIRELESS_SETTINGS,
                android.provider.Settings.ACTION_WIFI_SETTINGS, android.provider.Settings.ACTION_SETTINGS}) {
            if (AppLaunch.start(activity, new Intent(action), "Network settings")) return;
        }
        showToast(AppStrings.get(R.string.home_could_not_open_network_settings));
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0) return false;
                if (shelf.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = searchMode || !picksOn() ? availableTopButton(5) : tab;
                } else if (!picksOn() || (cards.isEmpty() && (tab != 4 || searchMode))) {
                    zone = Z_TOP;
                    topIndex = searchMode || !picksOn() ? availableTopButton(5) : tab;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (tab == 4 && !searchMode) {
                    if (dx > 0 && rouletteWork.finished()) spinRoulette();
                    else if (dy < 0) { zone = Z_TOP; topIndex = searchMode ? availableTopButton(5) : tab; }
                    else if (dy > 0) zone = Z_SHELF;
                    break;
                }
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) {
                        int total = currentCatalog().size();
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
                    if (!searchMode && randomPoster.resetAway(cardPage * 5 + cardIndex, currentCatalog().size() - 1))
                        updateCards();
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
                    if (topIndex < 5) selectTab(topIndex);   // category tabs: focusing a tab selects it
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
