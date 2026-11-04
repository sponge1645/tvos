package io.tvos;

import io.tvos.weather.WeatherForecast;

import io.tvos.artwork.RoundedImages;
import io.tvos.home.dock.HomeDockEditor;

import io.tvos.home.HomeLayout;
import io.tvos.home.HomeGeometry;
import io.tvos.home.HomeLayerMotion;
import io.tvos.home.HomePosterWindow;
import io.tvos.home.HomeFocusPolicy;
import io.tvos.home.HomeReveal;
import io.tvos.watching.WatchingSources;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
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
    // "All Apps" sheet.
    private static final int COLUMNS = HomeAccessibility.COLUMNS, VISIBLE_ROWS = 3;
    private static final int WHITE = 0xFFF5F5F7;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7, SEARCH_GLASS = 8;
    private static final int Z_SHELF = HomeAccessibility.Z_SHELF, Z_DRAWER = HomeAccessibility.Z_DRAWER;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; all recommendation cards use a 5-column portrait grid.
    private static final float L_BUTTON = HomeLayout.BUTTON, L_BUTTON_GAP = 20, L_BUTTON_CY = HomeLayout.HEADER_CY;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73;
    // Prepared once per supported language; the drawing path never builds a label array.
    private final EnumMap<AppLanguage, String[]> tabLabels = new EnumMap<>(AppLanguage.class);
    private static final String[] TAB_KIND = {null, "movie", null, "tv"};
    private static final int Z_TOP = 4, Z_CARDS = 5;

    private final Activity activity;
    private final HomeGeometry geometry = new HomeGeometry();
    private final Scene scene = new Scene();
    private final RoundedImages images = new RoundedImages(Ui.LEGACY);
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

    // Category and action IDs remain stable while their visual order is configurable.
    private int tab, topIndex, cardIndex;
    private final HomeSearchGlass searchGlass;
    private final HomeSearchInput searchInput;
    private final HomeSearchState<Recs.Pick> search = new HomeSearchState<>();
    private final HomeAppWork appWork;
    private final SearchController searchWork;
    private final MovieAppLauncher movieApps;
    private final HomeDockEditor<HomeApps.Item> dockEditor;

    private static final int Z_WEATHER = 6;
    private HomeWeatherPanel weatherPanel;
    private boolean weatherTouch, weatherWasFocused;
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
    private final RectF[] topRects = new RectF[11];
    private final Spring[] cardFocus = new Spring[8];
    private final RectF[] cardRects = new RectF[8];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private final NetworkStatus networkStatus;
    private Paint lTab, lTabOn, lCardTitle, lCardMeta, lDate;
    private float contentTop, contentBottom;
    private final HomeReveal reveal = new HomeReveal();
    private final View[] revealContent, redrawLayers;
    private final HomeLayerMotion layerMotion;

    // Shelf
    private final HomeDockState<HomeApps.Item> dock = new HomeDockState<>((a, b) -> a.key.equals(b.key));
    private final RectF platterRect = new RectF();

    // Bottom drawer
    private final HomeDrawerState<HomeApps.Item> drawer = new HomeDrawerState<>(
            (a, b) -> a.key.equals(b.key) && a.connected == b.connected,
            item -> item.kind == HomeApps.INPUT || item.kind == HomeApps.LIVE_TV);

    // "All Apps" sheet
    private final HomeGridState<HomeApps.Item> grid = new HomeGridState<>(COLUMNS, VISIBLE_ROWS);
    private final RectF focusedCell = new RectF();

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean drawerWasMoving, idle;
    private long lastNanos;
    private int w, h;
    private float k = 1;
    private String clockText = "", dateText = "";
    private long clockMinute = -1;
    private boolean okDown, backDown, vpnPress;
    private final Spring weatherFocus = new Spring(.26f, .85f, 0);
    private final Spring vpnColor = new Spring(.35f, 1f, 0);
    private final Runnable vpnHold = this::openVpnOnHold;

    private boolean vpnFocused() {
        return running && !grid.open && !drawer.open && zone == Z_TOP
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
        appWork.toggleVpn(); networkStatus.checkSoon();
    }
    private void beginDockEdit() {
        dock.begin(SystemClock.uptimeMillis());
        click.open();
        notices.hint(AppStrings.get(R.string.home_dock_edit_hint));
        tilesDirty = true;
        wake();
    }

    private final Shadows shadows = new Shadows();
    private final ActionSurface actionSurface = new ActionSurface(scene, shadows);


    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override protected void onDraw(Canvas c) {
            if (lTab == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: background.draw(c, drawer.motion.value); break;
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
            networkStatus.rescan();
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
        background.setFallbackWordmark((canvas, x, y, width, color) ->
                Symbols.draw(a, canvas, "tvos_wordmark", x, y, width * (144f/152), color));
        appWork = new HomeAppWork(a.getApplicationContext(), LOADER, handler, () -> running,
                (loadedShelf, drawer, loadedOthers) -> {
                    setShelf(loadedShelf);
                    setDrawerItems(drawer);
                    if (!grid.open) setOthers(loadedOthers);
                }, () -> showToast(AppStrings.get(R.string.home_vpn_toggle_failed)));
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
            if (running && tab == 2 && !search.active) {
                updateCards();
                logWatchingCards();
                wake();
            }
        });
        searchWork = new SearchController(handler, recs, () -> running && search.active,
                this::acceptSearchResults);
        accessibility = new HomeAccessibility(a, data, new HomeAccessibility.Host() {
            @Override public void read(HomeAccessibility.State state) { readAccessibilityState(state); }
            @Override public void wake() { HomeView.this.wake(); }
            @Override public void focus(int group, int index) { focusAccessibleItem(group, index); }
            @Override public void activate() { HomeView.this.activate(); }
        });
        spoken = new Spoken(this, accessibility);
        movieApps = new MovieAppLauncher(a, this, LOADER, handler, new MovieAppLauncher.Host() {
            @Override public boolean running() { return running; }
            @Override public int epoch() { return appWork.epoch(); }
            @Override public void toast(String text) { showToast(text); }
        });
        dockEditor = new HomeDockEditor<>(handler, new HomeDockEditor.Host<HomeApps.Item>() {
            @Override public boolean eligible() { return okDown && !grid.open && zone == Z_SHELF && !dock.items.isEmpty(); }
            @Override public List<HomeApps.Item> items() { return dock.items; }
            @Override public int index() { return dock.index; }
            @Override public void begin() { beginDockEdit(); }
            @Override public void move(int from, int to) { shiftDockTiles(from, to); }
            @Override public void finish(List<HomeApps.Item> restore) { finishDockTiles(restore); }
            @Override public void saved() { click.open(); }
            @Override public void canceled() { click.move(); }
        }, items -> Prefs.setShelf(HomeApps.shelfOrder(items)));
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
        searchGlass = new HomeSearchGlass(this, searchGlassLayer, scene, shadows);
        revealContent = new View[] {sky, heroLayer};
        layerMotion = new HomeLayerMotion(revealBackdrop, sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer);
        redrawLayers = new View[] {revealBackdrop, sky, heroLayer, status, platter, tiles, drawerLayer};
        notices = new HomeNotices(a, scene, LOADER, handler, toastLayer::invalidate, this::wake, this::announceForAccessibility);
        searchInput = new HomeSearchInput(a,this,searchGlassLayer,new HomeSearchInput.Host(){
            @Override public boolean running(){return running;}
            @Override public String query(){return search.query;}
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
        appWork.onVpnChanged(networkStatus::checkSoon);
        makeLightButtons();
        recs.setLight(true);
        background.checkNight(data.isNight(System.currentTimeMillis()), false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setDrawerItems(HomeAppWork.drawerItems(a));
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
        lastNanos = 0;
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
        if (grid.open) closeSheet();
        grid.sheet.snap(grid.sheet.target);
        motionDirty = true;
        updateMotion(); // Keep a staged dream return applied before the first resumed draw.
        invalidateAll();
        idle = false;
        Choreographer.getInstance().postFrameCallback(this);
        spoken.readAgain();
        searchWork.resume(search.query);
    }

    private HomeGenres.Host menuHost(int category) {
        return new HomeGenres.Host() {
            @Override public String kind() { return category == 3 ? "tv" : "movie"; }
            @Override public boolean eligible() {
                return running && okDown && tab == category && !search.active && !grid.open && !drawer.open
                        && !dockEditor.editing() && zone == Z_TOP && topIndex == category;
            }
            @Override public RectF anchor() { return topRects[category]; }
            @Override public Paint anchorPaint() { return lTabOn; }
            @Override public void visibilityChanged() { heroDirty = true; heroLayer.invalidate(); wake(); }
            @Override public void opened() { okDown = false; dockEditor.cancelHold(); click.open(); }
            @Override public void moved() { click.move(); }
            @Override public void changed() {
                if (category != 2) layoutLight();
                if (category == 2 || (tab == category && !search.active)) {
                    cardPage = cardIndex = 0;
                    updateCards(); retarget(false);
                }
                heroDirty = true;
                if (category != 2) statusDirty = true;
                wake();
            }
            @Override public void message(String text) { showToast(text); }
        };
    }

    private HomeGenres createGenres(int category) {
        return new HomeGenres(this, handler, recs.genres(), menuHost(category));
    }

    private HomeGenres createWatchingMenu() {
        return new HomeGenres(this, handler, menuHost(2), new HomeGenres.Choices() {
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
        if (drawer.open || grid.open || search.active || dockEditor.editing()) return;
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
        if (!running || drawer.open || grid.open || search.active || dockEditor.editing()) return;
        reveal.start(SystemClock.uptimeMillis(), reducedDreamMotion());
        updateMotion();
        wake();
    }

    void pause() {
        if (weatherPanel != null) weatherPanel.dismiss();
        if (!running) return;
        running = false;
        randomPoster.clear();
        genres.pause(); tvGenres.pause(); watchingMenu.pause();
        if (!reveal.prepared()) reveal.cancel();
        updateMotion();
        networkStatus.stop();
        handler.removeCallbacks(vpnHold); vpnPress = false;
        appWork.invalidate();
        searchWork.pause(search.active);
        dockEditor.pause();
        okDown = backDown = false;
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
        dock.index = 0;
        grid.open = false;
        drawer.open = false;
        if (animated) {
            grid.sheet.target = 0;
            drawer.motion.target = 0;
        } else {
            grid.sheet.snap(0);
            drawer.motion.snap(0);
        }
        if (search.active) { cancelSearchWork(); search.close(); }
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

    private final java.util.function.IntPredicate enabledTabs = HomeView::tabEnabled;
    private final java.util.function.IntPredicate visibleTop = this::topControlVisible;
    private final java.util.function.IntPredicate enabledTop = this::topButtonEnabled;
    private final java.util.function.IntSupplier topFallback = () -> picksOn() ? tab : 0;

    private boolean topButtonEnabled(int index) { return Prefs.topButtonEnabled(index); }
    private boolean topControlVisible(int id) {
        return id < 5 ? !search.active && picksOn() && tabEnabled(id) : topButtonEnabled(id - 5);
    }

    /** Tabs and actions follow their saved visual order, independently of stable IDs. */
    private int nextTopControl(int current, int direction) {
        return HomeFocusPolicy.next(current, direction, Prefs.categoryOrder(), Prefs.topButtonOrder(), visibleTop);
    }

    private int firstTopControl() {
        for (int id : Prefs.categoryOrder()) if (topControlVisible(id)) return id;
        return availableTopButton(5);
    }

    private int availableTopButton(int preferred) {
        return HomeFocusPolicy.available(preferred, Prefs.topButtonOrder(), enabledTop, topFallback);
    }

    /** Poster shortcuts use the first/last visible action in the saved layout. */
    private int posterCornerTarget(boolean rightmost) {
        return HomeFocusPolicy.corner(rightmost, Prefs.topButtonOrder(), enabledTop, topFallback);
    }

    private static boolean tabEnabled(int i) {
        return Prefs.categoryEnabled(i);
    }

    private void syncVisibleTabs() {
        int first = Prefs.firstCategory();
        if (!tabEnabled(tab) && first >= 0) selectTab(first);
        if (topIndex < 5 && !tabEnabled(topIndex)) topIndex = first >= 0 ? tab : availableTopButton(5);
        if (first < 0 && zone == Z_CARDS) zone = Z_SHELF;
        if (topIndex >= 5 && !topButtonEnabled(topIndex - 5)) topIndex = availableTopButton(5);
        if (zone == Z_TOP && !topControlVisible(topIndex)) zone = Z_SHELF;
    }

    private String[] tabs() {
        AppLanguage language = AppLanguage.current();
        String[] labels = tabLabels.get(language);
        if (labels == null) {
            labels = new String[] {AppStrings.get(R.string.home_tab_new, language), AppStrings.get(R.string.home_tab_movies, language), AppStrings.get(R.string.home_tab_watching, language), AppStrings.get(R.string.home_tab_shows, language)};
            tabLabels.put(language, labels);
        }
        labels[1] = genres.label();
        labels[3] = tvGenres.label();
        return labels;
    }

    @Override public void onData() {
        wake();
        updatePalette();
        layoutLight();
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
        if (Prefs.categoryCount() != 0 || search.active) { dockContinue.hide(); dockBackdrop.pause(); backdropDockIndex = -1; backdropFocus = null; return; }
        if (grid.open || drawer.open || zone != Z_SHELF || dock.index < 0 || dock.index >= dock.items.size()) return;
        HomeApps.Item item = dock.items.get(dock.index);
        String pkg = item.kind == HomeApps.APP ? item.pkg
                : item.kind == HomeApps.SETTINGS || item.kind == HomeApps.ANDROID_SETTINGS ? "io.tvos" : null;
        String source = WatchingSources.id(pkg);
        if (Prefs.homeContinue() && ("kinopub".equals(source) || "seena".equals(source) || "lampa".equals(source))) {
            dockBackdrop.focus(null);
            dockContinue.show(watching.forSource(source));
            return;
        }
        dockContinue.hide();
        int direction = backdropDockIndex < 0 ? 0 : Integer.compare(dock.index, backdropDockIndex);
        backdropDockIndex = dock.index;
        if (pkg != null) {
            if (!pkg.equals(backdropFocus)) { backdropDirection = direction; backdropFocus = pkg; }
            dockBackdrop.focus(pkg);
        }
    }

    private void showDockBackdrop(DockBackdrop.Artwork artwork) {
        if (!running || Prefs.categoryCount() != 0 || search.active) return;
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
        for (View v : redrawLayers) v.invalidate();
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
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lDate = Ui.text(Ui.semibold, 34 * k, L_DIM);
        searchGlass.resize(k);
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
        if (marqueeActive) heroDirty = true;
        boolean dockMoving = dock.step(dt, dockEditor.editing());
        if (dockMoving) tilesDirty = true;
        boolean tilesMoving = step(dock.focus, dt) | dockMoving;
        boolean searchHeadingMoving = tickSearchHeading(dt);
        vpnColor.target = networkStatus.vpn() ? 1 : 0;
        if (reducedDreamMotion()) vpnColor.snap(vpnColor.target);
        boolean vpnMoving = vpnColor.step(dt);
        weatherFocus.target = zone == Z_WEATHER ? 1 : 0;
        if (reducedDreamMotion()) weatherFocus.snap(weatherFocus.target);
        boolean weatherMoving = weatherFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        if (topMoving || vpnMoving || weatherMoving) status.invalidate();
        boolean bannerMoving = Prefs.categoryCount() == 0 && !search.active && dockBanner.tick(up);
        boolean heroMoving = topMoving | cardsMoving | bannerMoving;
        boolean drawerItemsMoving = step(drawer.focus, dt);
        boolean gridMoving = step(grid.focus, dt) | grid.scroll.step(dt);
        boolean drawerMoving = drawer.motion.step(dt);
        boolean sheetMoving = grid.sheet.step(dt);
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
            layoutLight();
            status.invalidate();
            heroLayer.invalidate(); // the header date/time changes even when recommendations are enabled
        }

        // Busy: something is moving, or the sheet or the drawer is open.
        boolean busy = tilesMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || bgFading || nightMoving || searchHeadingMoving
                || vpnMoving || weatherMoving || notices.busy() || dockMoving || revealing;
        idle = !busy;
        if (busy) Choreographer.getInstance().postFrameCallback(this);
        else {
            // Short text needs only the normal clock tick. Long text wakes at its dwell deadline,
            // then keeps the existing 50 ms marquee cadence without polling during the wait.
            long delay = posters.nextDelay(up);
            if (Prefs.categoryCount() == 0 && !search.active) delay = Math.min(delay, dockBanner.nextDelay(up));
            Choreographer.getInstance().postFrameCallbackDelayed(this, delay);
        }
    }

    private int focusedPoster() {
        return !grid.open && picksOn() && zone == Z_CARDS
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
        layerMotion.apply(drawer.motion.value, grid.sheet.value, grid.open, reveal, k, h, platterRect.top, drawer.extraHeight);
    }


    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || lTab == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        dateText = Ui.date(now);
        return true;
    }

    private void updatePalette() {
        WeatherForecast wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Night look

    /** Refresh layers that sample the changing wallpaper. */
    private void backgroundChanged() {
        heroDirty = statusDirty = tilesDirty = drawerDirty = true;
        sky.invalidate();
        revealBackdrop.invalidate();
        platter.invalidate();
    }


    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return ActionSurface.blend(onLight, onDark, background.night());
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
        int old = prefs.getInt("weather_placement", 1);
        return top ? (old == 1 || old == 3) : (old == 2 || old == 3);
    }
    private boolean weatherTop() { return weatherVisible(true); }
    private boolean weatherBottom() { return weatherVisible(false); }

    // MARK: - Shelf

    private void setShelf(List<HomeApps.Item> list) {
        wake();
        appSurfaces.invalidateDock();
        if (dockEditor.editing()) return; // Do not overwrite a drag with an asynchronous refresh.
        if (dock.replace(list)) {
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        geometry.shelf(platterRect, dock.rects, dock.items.size(), w, h, k);
        appSurfaces.clear();
        header.clearFrame();
        layoutLight(); // Dock height changes the free space for posters.
    }

    // MARK: - Bottom drawer

    private void setDrawerItems(List<HomeApps.Item> list) {
        boolean resized = drawer.focus.length != list.size();
        boolean changed = drawer.replace(list);
        if (resized) retarget(true);
        layoutDrawer();
        if (changed) drawerDirty = true;
    }

    private void layoutDrawer() {
        drawer.extraHeight = geometry.drawer(drawer.rects, drawer.items.size(), drawer.inputs, drawer.weather, h, k);
        drawerDirty = motionDirty = true;
    }

    private void openDrawer() {
        if (drawer.items.isEmpty()) return;
        drawer.open(dock.items.isEmpty() ? 0 : nearest(drawer.rects, dock.rects[dock.index].centerX()));
        zone = Z_DRAWER;
        drawerDirty = true;
    }

    private void closeDrawer() {
        drawer.close();
        zone = Z_SHELF;
        if (!dock.items.isEmpty() && drawer.index < drawer.rects.length)
            dock.index = nearest(dock.rects, drawer.rects[drawer.index].centerX());
    }

    private HomeAppsRenderer.Frame appFrame() {
        appFrame.shelf = dock.items; appFrame.tileRects = dock.rects; appFrame.tileFocus = dock.focus; appFrame.dockSlide = dock.slide;
        appFrame.dockLiftIndex = dock.liftedIndex; appFrame.dockLift = dock.lift.value; appFrame.dockWiggleAt = dock.wiggleAt;
        appFrame.drawerItems = drawer.items; appFrame.drawerRects = drawer.rects; appFrame.drawerFocus = drawer.focus;
        appFrame.drawerInputs = drawer.inputs; appFrame.weatherCard = drawer.weather; appFrame.weatherBottom = weatherBottom();
        appFrame.others = grid.items; appFrame.gridFocus = grid.focus; appFrame.gridIndex = grid.index; appFrame.sheetOpen = grid.open;
        appFrame.focusedCell = focusedCell; appFrame.scroll = grid.scroll.value; appFrame.platterRect = platterRect;
        appFrame.k = k; appFrame.night = background.night(); appFrame.w = w; appFrame.h = h;
        return appFrame;
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        updateDockBackdrop();
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(dock.focus, !grid.open && zone == Z_SHELF ? dock.index : -1, snap);
        boolean drawerChanged = aim(drawer.focus, !grid.open && zone == Z_DRAWER ? drawer.index : -1, snap);
        boolean topChanged = aim(topFocus, !grid.open && zone == Z_TOP ? topIndex : -1, snap);
        boolean heroChanged = aim(cardFocus, !grid.open && zone == Z_CARDS ? cardIndex : -1, snap) | topChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || topChanged || weatherWasFocused != (zone == Z_WEATHER);
        weatherWasFocused = zone == Z_WEATHER;
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
        grid.replace(list);
        appSurfaces.invalidateLabels();
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        grid.open = true;
        grid.index = 0;
        grid.scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        grid.sheet.target = 1;
        gridDirty = true;
    }

    private void closeSheet() {
        grid.open = false;
        grid.sheet.target = 0;
        retarget(false);
    }

    private void retargetGrid(boolean snap) {
        grid.retarget(snap);
        spoken.focusMoved();
    }

    // MARK: - Toasts

    void showToast(String text) { notices.add(text); }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() { appWork.load(); }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    private boolean dockContinueKey(KeyEvent e) {
        if (!dockEditor.editing() && !grid.open && !drawer.open && !search.active && dockContinue.key(e, zone == Z_SHELF,
                () -> { zone = Z_TOP; topIndex = availableTopButton(5); retarget(false); },
                pick -> { if (!watching.open(pick)) showToast(AppStrings.get(R.string.home_watching_open_failed)); })) {
            okDown = false; dockEditor.cancelHold(); return true;
        }
        return false;
    }

    private boolean canOpenWeather() {
        return !grid.open && !drawer.open && weatherTop() && !Double.isNaN(data.weather.temp);
    }
    private void openWeather() {
        if (!canOpenWeather() || weatherPanel != null) return;
        zone = Z_WEATHER; okDown = false; click.open(); retarget(false); status.invalidate();
        weatherPanel = new HomeWeatherPanel(activity, scene, data);
        weatherPanel.setOnDismissListener(dialog -> {
            weatherPanel = null;
            if (running) { zone = canOpenWeather() ? Z_WEATHER : Z_TOP; retarget(false); status.invalidate(); requestFocus(); spoken.focusMoved(); }
        });
        status.invalidate();
        weatherPanel.show();
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            weatherTouch = canOpenWeather() && header.weatherBounds().contains(event.getX(), event.getY());
            if (weatherTouch) return true;
        } else if (event.getAction() == android.view.MotionEvent.ACTION_UP && weatherTouch) {
            weatherTouch = false;
            if (canOpenWeather() && header.weatherBounds().contains(event.getX(), event.getY())) openWeather();
            return true;
        } else if (event.getAction() == android.view.MotionEvent.ACTION_CANCEL) weatherTouch = false;
        return super.onTouchEvent(event);
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
        dock.swap(i, j);
        appSurfaces.swapDock(i, j, dock.items.size());
        retarget(false);
        tilesDirty = true;
        wake();
        click.move();
    }

    private void finishDockTiles(List<HomeApps.Item> before) {
        if (before != null && before.size() == dock.items.size()) appSurfaces.restoreDock(before, dock.items);
        dock.finish(before);
        retarget(false);
        tilesDirty = true;
        wake();
    }

    private void back() {
        if (dockEditor.editing()) { dockEditor.finish(false); click.back(); return; }
        if (grid.open) {
            closeSheet();
        } else if (search.active) {
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
        click.back();
    }

    private void focusSound() {
        if (zone == Z_SHELF) click.app(); else click.move();
    }

    private void move(int dx, int dy) {
        if (dockEditor.editing()) { if (dx != 0) dockEditor.move(dx); return; }
        if (grid.open) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(dock.items.size() - 1, dock.index + dx));
                    if (next == dock.index) return;
                    dock.index = next;
                } else {
                    if (dy <= 0 || drawer.items.isEmpty()) return;
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
                    int next = Math.max(0, Math.min(drawer.items.size() - 1, drawer.index + dx));
                    if (next == drawer.index) return;
                    drawer.index = next;
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
        focusSound();
    }

    private static int nearest(RectF[] rects, float x) {
        return nearest(rects, rects.length, x);
    }

    private void moveGrid(int dx, int dy) {
        if (!grid.move(dx, dy)) return;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        wake();
        if (zone == Z_WEATHER) { openWeather(); return; }
        HomeApps.Item it;
        RectF r;
        if (!grid.open && zone == WIDGETS) return;   // a widget is only read
        if (grid.open) {
            if (grid.items.isEmpty()) return;
            it = grid.items.get(grid.index);
            r = focusedCell;
        } else if (zone == Z_DRAWER) {
            if (drawer.items.isEmpty()) return;
            it = drawer.items.get(drawer.index);
            r = drawer.rects[drawer.index];
        } else if (zone == Z_TOP) {
            if (topIndex < 5) {
                if (search.active) { closeSearch(); return; }
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
            if (dock.items.isEmpty()) return;
            it = dock.items.get(dock.index);
            r = dock.rects[dock.index];
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
            for (int i = 0; i < drawer.items.size(); i++) {
                if (drawer.items.get(i).kind == HomeApps.INPUT) {
                    drawer.index = i;
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
        if (!searchGlass.visible()) return;
        applyTone();
        searchGlass.draw(c, k, background.night());
    }

    /** Position the wallpaper-aware glass under the transparent Android text-input window. */
    private void syncSearchGlass(View panel) {
        if (running && searchInput.showing()) searchGlass.sync(panel);
    }

    private void beginSearch() {
        randomPoster.clear();
        setSearchInputOpen(true);
        if (search.begin()) cardPage = cardIndex = 0;
    }

    private void restoreSearchRequest() {
        searchWork.restore(search.query, !search.results.isEmpty());
    }

    private void openSearch() {
        AppLaunch.start(activity, new android.content.Intent(activity, SearchActivity.class)
                .putExtra("profile_frost", background.profilePixels()), "Search screen");
    }

    private void searchQueryChanged(String query) {
        search.changed(query);
        cardPage = cardIndex = 0;
        refreshSearch(false);
        searchWork.changed(query);
    }

    private void searchInputDismissed() {
        searchGlass.hide();
        setSearchInputOpen(false);
        searchWork.flush();
        zone = search.results.isEmpty() ? Z_TOP : Z_CARDS;
        if (zone == Z_TOP) topIndex = availableTopButton(5);
        retarget(false);
        heroDirty = statusDirty = true;
        wake();
    }

    private void cancelSearchWork() {
        searchWork.cancel();
    }

    private void acceptSearchResults(List<Recs.Pick> found) {
        search.accept(found);
        cardPage = cardIndex = 0;
        refreshSearch(true);
    }

    /** Input intent is known before the dialog has its first layout, avoiding a heading flash. */
    private void setSearchInputOpen(boolean open) {
        search.inputOpen(open);
        heroDirty = true;
        wake();
    }

    private boolean tickSearchHeading(float dt) { return search.tickHeading(dt); }

    private void closeSearch() {
        cancelSearchWork();
        search.close();
        cardPage = cardIndex = 0;
        zone = Z_TOP;
        topIndex = Prefs.firstCategory() >= 0 ? tab : availableTopButton(5);
        if (!topControlVisible(topIndex)) zone = Z_SHELF;
        refreshSearch(true);
    }

    /** Typing keeps input focus; accepted results and screen transitions retarget poster focus. */
    private void refreshSearch(boolean retargetFocus) {
        layoutLight();
        updateCards();
        if (retargetFocus) retarget(false);
        heroDirty = statusDirty = true;
        wake();
    }

    // MARK: - Accessibility navigation; virtual-node presentation lives in HomeAccessibility.

    private static final int GRID = HomeAccessibility.GRID, WIDGETS = HomeAccessibility.WIDGETS;

    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return spoken; }

    private void readAccessibilityState(HomeAccessibility.State s) {
        s.sheetOpen = grid.open; s.searchMode = search.active; s.drawerOpen = drawer.open;
        s.picksOn = picksOn(); s.weatherBottom = weatherBottom();
        s.tab = tab; s.zone = zone; s.gridIndex = grid.index; s.topIndex = topIndex;
        s.cardIndex = cardIndex; s.shelfIndex = dock.index; s.drawerIndex = drawer.index; s.widgetIndex = widgetIndex;
        s.others = grid.items; s.shelf = dock.items; s.drawerItems = drawer.items; s.lightButtons = lightButtons; s.cards = cards;
        s.topRects = topRects; s.cardRects = cardRects; s.tileRects = dock.rects; s.drawerRects = drawer.rects;
        s.weatherHeader = header.weatherBounds(); s.weatherTop = canOpenWeather();
        s.weatherCard = drawer.weather; s.tabs = tabs(); s.networkState = networkStatus.state();
        s.k = k; s.h = h; s.scroll = grid.scroll.target;
        s.contentTop = contentTop; s.contentBottom = contentBottom;
        s.heroY = heroLayer.getTranslationY(); s.statusY = status.getTranslationY(); s.tilesY = tiles.getTranslationY();
        for (int i = 0; i < s.topVisible.length; i++) s.topVisible[i] = topControlVisible(i);
    }

    private void focusAccessibleItem(int group, int i) {
        if (group == GRID) {
            grid.index = i;
            retargetGrid(false);
            gridDirty = true;
            click.move();
            return;
        }
        switch (group) {
            case Z_WEATHER: zone = Z_WEATHER; status.invalidate(); break;
            case Z_DRAWER:
                if (!drawer.open) openDrawer();
                drawer.index = i;
                break;
            case WIDGETS:
                widgetIndex = i;
                break;
            case Z_TOP:
                if (drawer.open) closeDrawer();
                topIndex = i;
                if (i < 5) selectTab(i);
                break;
            case Z_CARDS:
                if (drawer.open) closeDrawer();
                cardIndex = i;
                break;
            case Z_SHELF:
                if (drawer.open) closeDrawer();
                dock.index = i;
                break;
            default:
                return;
        }
        zone = group;
        retarget(false);
        if (group == Z_SHELF) click.app(); else click.move();
    }

    private List<Integer> widgets() {
        List<Integer> out = new ArrayList<>();
        if (!drawer.open) return out;
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
        layoutTopActions(L_BUTTON * k, CONTENT_R * k, L_BUTTON_CY * k);
        header.measureDateWeather(headerFrame());
        RectF weather = header.weatherBounds();
        float left = weather.isEmpty() ? MARGIN * k : weather.right + 16 * k;
        float right = CONTENT_R * k;
        for (int i = 5; i < topRects.length; i++) if (!topRects[i].isEmpty()) right = Math.min(right, topRects[i].left - 28 * k);
        contentTop = geometry.tabs(topRects, Prefs.categoryOrder(), enabledTabs, tabs(), lTab, lTabOn,
                largePostersWithoutWidgets, k, left, right);
        if (search.active) contentTop += 70 * k;
        contentBottom = platterRect.top;
        geometry.posters(cardRects, largePostersWithoutWidgets, contentTop, contentBottom, lCardMeta, k);
        heroDirty = statusDirty = true;
    }

    private void layoutTopActions(float b, float right, float cy) {
        geometry.actions(topRects, Prefs.topButtonOrder(), enabledTop, clockText, Ui.displayMedium,
                b, right, cy, L_BUTTON_GAP * k, k);
    }

    private List<Recs.Pick> baseCatalog() {
        if (tab == 2) return watching.picks();
        if (tab == 1 && genres.movies() != null) return genres.movies();
        if (tab == 3 && tvGenres.movies() != null) return tvGenres.movies();
        return recs.catalog(TAB_KIND[tab]);
    }
    private int randomGenre() { return tab == 1 ? genres.selectedId() : tab == 3 ? tvGenres.selectedId() : 0; }
    private List<Recs.Pick> currentCatalog() {
        if (search.active) return search.results;
        if (tab == 2) return watching.picks();
        List<Recs.Pick> base = baseCatalog();
        List<Recs.Pick> all = new ArrayList<>(base.subList(0, Math.min(24, base.size())));
        if (!all.isEmpty())
            all.add(randomPoster.tail(tab + ":" + randomGenre() + ":" + Ui.language, tab == 3 ? "tv" : "movie"));
        return all;
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        if (lTab == null) return;
        if (!search.active && Prefs.firstCategory() < 0) {
            cards.clear();
            recs.setPosterWindow(java.util.Collections.emptyList());
            heroDirty = true;
            return;
        }

        List<Recs.Pick> all = currentCatalog();
        int perPage = 5;
        cardPage = HomePosterWindow.clampPage(cardPage, all.size(), perPage);
        int from = HomePosterWindow.start(cardPage, all.size(), perPage);
        List<Recs.Pick> list = HomePosterWindow.select(all, from, perPage, cards);
        if (list != cards) {
            cards = list;
            cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
            if (zone == Z_CARDS && list.isEmpty() && !search.active) zone = Z_SHELF;
            // A refresh can shrink the focused page without any directional key event.
            retarget(false);
        }
        // Current page first, then the next two and previous page. Drop work from the old window.
        List<Recs.Pick> posterWindow = HomePosterWindow.prefetch(all, from, perPage);
        recs.setPosterWindow(running ? posterWindow : java.util.Collections.emptyList());
        if (running) for (Recs.Pick pick : posterWindow) recs.poster(pick);
        if (running) {
            if (search.active || tab <= 2) for (Recs.Pick pick : cards) if (pick.id > 0 && pick.watchingKey == null) recs.requestDuration(pick);
            Backdrop b = cards.isEmpty() || cards.get(0).id <= 0 ? null : recs.backdrop(cards.get(0));
            if (cards.isEmpty() || b != null) background.show(b);
        }
        heroDirty = true;
        spoken.focusMoved();    // new picks under the focused card
    }

    private void selectTab(int index) {
        if (search.active) { cancelSearchWork(); search.close(); }
        if (index == tab || !tabEnabled(index)) return;

        randomPoster.clear();
        tab = index;
        if (tab == 2) watching.requestPermission(activity);
        layoutLight();
        cardPage = 0;
        cardIndex = 0;
        updateCards();
        heroDirty = true;
    }









    private void drawLightTop(Canvas c) {
        posters.begin(k, background.night(), tab, cardPage, search.active, zone == Z_CARDS && !grid.open,
                lCardTitle, lCardMeta);
        HomeHeaderRenderer.Frame heading = headerFrame();
        if (!picksOn()) {
            if (dockContinue.visible()) dockContinue.draw(c, k, contentBottom - 20 * k, background.night());
            else if (!search.active && Prefs.categoryCount() == 0) dockBanner.draw(c, k, noTopWidgets());
            return;
        }
        header.drawTabs(c, heading);

        if (tab == 2 && !search.active && cards.isEmpty()) {
            c.drawText(watching.emptyText(), MARGIN * k, contentTop + 55 * k, lCardMeta);
        }
        int top = -1;
        for (int i = 0; i < cards.size(); i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
        for (int i = 0; i < cards.size(); i++) if (i != top) posters.draw(c, cards.get(i), cardRects[i], cardFocus[i].value, i);
        if (top >= 0) posters.draw(c, cards.get(top), cardRects[top], cardFocus[top].value, top);
    }

    /** Date and weather use the same top inset as the right-side controls. */
    private boolean showHeaderPart(String key) {
        return getContext().getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(key, !"show_date".equals(key));
    }

    private HomeHeaderRenderer.Frame headerFrame() {
        applyTone();
        headingFrame.k = k; headingFrame.night = background.night(); headingFrame.headingOpacity = search.heading.value;
        headingFrame.vpnTint = Ui.clamp(vpnColor.value);
        headingFrame.hiddenTab = genres.hidesAnchor() ? 1 : tvGenres.hidesAnchor() ? 3 : watchingMenu.hidesAnchor() ? 2 : -1;
        headingFrame.tab = tab; headingFrame.networkState = networkStatus.state(); headingFrame.wifiLevel = networkStatus.wifiLevel(); headingFrame.tabs = tabs();
        headingFrame.clockText = clockText; headingFrame.dateText = dateText; headingFrame.searchQuery = search.query;
        headingFrame.topRects = topRects; headingFrame.topFocus = topFocus; headingFrame.lightButtons = lightButtons;
        headingFrame.lDate = lDate; headingFrame.lTab = lTab; headingFrame.lTabOn = lTabOn;
        headingFrame.weatherFocused = zone == Z_WEATHER; headingFrame.weatherAmount = Ui.clamp(weatherFocus.value);
        headingFrame.showDate = showHeaderPart("show_date"); headingFrame.weatherTop = weatherTop();
        headingFrame.searchMode = search.active; headingFrame.largePostersWithoutWidgets = largePostersWithoutWidgets;
        return headingFrame;
    }

    private boolean picksOn() {
        return (Recs.hasKey(getContext()) || (!search.active && Prefs.categoryEnabled(2)))
                && (search.active || Prefs.categoryCount() > 0);
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
            case Z_WEATHER:
                if (dy > 0 && canOpenWeather()) {
                    if (picksOn() && !cards.isEmpty()) { zone = Z_CARDS; cardIndex = 0; }
                    else zone = Z_SHELF;
                } else if (dx > 0 || !canOpenWeather()) {
                    zone = Z_TOP; topIndex = firstTopControl();
                    status.invalidate();
                } else return true;
                break;
            case Z_SHELF:
                if (dy >= 0) return false;
                if (dock.items.isEmpty()) {
                    zone = Z_TOP;
                    topIndex = search.active || !picksOn() ? availableTopButton(5) : tab;
                } else if (!picksOn() || (cards.isEmpty() && true)) {
                    zone = Z_TOP;
                    topIndex = search.active || !picksOn() ? availableTopButton(5) : tab;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), dock.rects[dock.index].centerX());
                }
                break;
            case Z_CARDS:

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
                    if (!search.active && randomPoster.resetAway(cardPage * 5 + cardIndex, currentCatalog().size() - 1))
                        updateCards();
                } else if (dy < 0) {
                    zone = Z_TOP;
                    // Route by visible position, not fixed action IDs. The last poster
                    // reaches the rightmost action; the preceding poster reaches the
                    // action immediately to its left (or the same one if only one exists).
                    if (!search.active && cardIndex == 4) {
                        topIndex = posterCornerTarget(true);
                    } else if (!search.active && cardIndex == 3) {
                        topIndex = posterCornerTarget(false);
                    } else {
                        topIndex = search.active ? availableTopButton(5) : tab;
                    }
                } else {
                    zone = Z_SHELF;
                    if (!dock.items.isEmpty()) dock.index = nearest(dock.rects, dock.rects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dy < 0 && canOpenWeather()) {
                    zone = Z_WEATHER; status.invalidate();
                } else if (dx != 0) {
                    if (dx < 0 && canOpenWeather() && topIndex == firstTopControl()) {
                        zone = Z_WEATHER; status.invalidate(); break;
                    }
                    int next = nextTopControl(topIndex, dx);
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 5) selectTab(topIndex);   // category tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (!picksOn() || (cards.isEmpty() && true)) {
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
        focusSound();
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

}
