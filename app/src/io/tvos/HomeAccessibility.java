package io.tvos;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.accessibility.AccessibilityManager;
import java.util.List;

/** Home virtual-node source. Reads live UI state only on the UI thread; navigation remains with the host. */
final class HomeAccessibility implements Spoken.Source {
    static final int Z_TOP = 4, Z_CARDS = 5, Z_SHELF = 2, Z_DRAWER = 3, GRID = 9, WIDGETS = 8;
    static final int COLUMNS = HomeLayout.APP_COLUMNS;
    static final float GRID_W = HomeLayout.APP_WIDTH, GRID_H = HomeLayout.APP_HEIGHT, GRID_GAP = HomeLayout.APP_GAP,
            GRID_ROW = HomeLayout.APP_ROW, GRID_TOP = HomeLayout.APP_TOP;

    /** Reused read-only view of the current frame; list/rectangle references are never copied or retained separately. */
    static final class State {
        boolean sheetOpen, searchMode, drawerOpen, picksOn, weatherBottom, spinning;
        int tab, zone, gridIndex, topIndex, cardIndex, shelfIndex, drawerIndex, widgetIndex, networkState;
        List<HomeApps.Item> others, shelf, drawerItems, lightButtons;
        List<Recs.Pick> cards;
        RectF[] topRects, cardRects, tileRects, drawerRects;
        RectF weatherCard;
        String[] tabs;
        final boolean[] topVisible = new boolean[11];
        float k, h, scroll, heroY, statusY, tilesY, contentTop, contentBottom;
    }

    interface Host {
        void read(State state);
        void wake();
        void focus(int group, int index);
        void activate();
    }

    private final Context context;
    private final HomeData data;
    private final Host host;
    private final State state = new State();
    private final RectF a11yRect = new RectF();

    HomeAccessibility(Context context, HomeData data, Host host) {
        this.context = context;
        this.data = data;
        this.host = host;
    }

    /** Release borrowed UI references when the home screen pauses. */
    void clear() {
        state.others = state.shelf = state.drawerItems = state.lightButtons = null;
        state.cards = null;
        state.topRects = state.cardRects = state.tileRects = state.drawerRects = null;
        state.weatherCard = null;
        state.tabs = null;
    }

    private State read() { host.read(state); return state; }
    static int id(int group, int index) { return group * 1000 + index + 1; }

    @Override public void items(List<Integer> out) {
        State s = read();
        if (s.sheetOpen) {
            for (int i = 0; i < s.others.size(); i++) out.add(id(GRID, i));
            return;
        }
        if (s.picksOn && !s.searchMode) for (int id : Prefs.categoryOrder())
            if (s.topVisible[id]) out.add(id(Z_TOP, id));
        for (int id : Prefs.topButtonOrder()) if (s.topVisible[5 + id]) out.add(id(Z_TOP, 5 + id));
        if (s.picksOn) for (int i = 0; i < (s.tab == 4 ? 1 : Math.min(5, s.cards.size())); i++) out.add(id(Z_CARDS, i));
        for (int i = 0; i < s.shelf.size(); i++) out.add(id(Z_SHELF, i));
        for (int i = 0; i < s.drawerItems.size(); i++) out.add(id(Z_DRAWER, i));
        if (s.drawerOpen && s.weatherBottom) out.add(id(WIDGETS, 0));
    }

    @Override public String text(int id) {
        if (id <= 0) return "";
        State s = read();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        switch (group) {
            case GRID:
                if (i >= s.others.size()) return "";
                return label(s.others, i) + ", " + (i + 1) + AppStrings.get(R.string.home_spoken_of) + s.others.size();
            case Z_TOP:
                if (i == 10) return networkLabel(s.networkState);
                if (i == 9) return AppStrings.get(R.string.home_clock_and_profile);
                if (i >= 5) return label(s.lightButtons, i - 5);
                if (i >= s.tabs.length) return "";
                return s.tabs[i] + AppStrings.get(R.string.home_spoken_tab) + Prefs.categoryPosition(i) + AppStrings.get(R.string.home_spoken_of) + Prefs.categoryCount();
            case Z_CARDS: return s.tab == 4 ? (AppStrings.get(R.string.home_random_movie)) : i < s.cards.size() ? pickText(s.cards.get(i)) : "";
            case Z_SHELF: return label(s.shelf, i);
            case Z_DRAWER: return label(s.drawerItems, i);
            case WIDGETS: return i == 0 ? weatherText() : "";
            default: return "";
        }
    }

    @Override public boolean bounds(int id, Rect out) {
        if (id <= 0) return false;
        State s = read();
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        float dy;
        switch (group) {
            case GRID: {
                if (!s.sheetOpen || i >= s.others.size()) return false;
                float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
                float x = ((1920 - gridWidth) / 2 + (i % COLUMNS) * (GRID_W + GRID_GAP)) * s.k;
                float y = (GRID_TOP + (i / COLUMNS - s.scroll) * GRID_ROW) * s.k;
                a11yRect.set(x, y, x + GRID_W * s.k, y + GRID_H * s.k);
                dy = 0;
                break;
            }
            case Z_TOP:
                if (i >= s.topRects.length || !s.topVisible[i]) return false;
                a11yRect.set(s.topRects[i]);
                dy = (i < 5 ? s.heroY : s.statusY);
                break;
            case Z_CARDS:
                if (i >= (s.tab == 4 ? 1 : Math.min(5, s.cards.size()))) return false;
                if (s.tab == 4) {
                    RouletteRenderer.buttonBounds(a11yRect, s.k, s.contentTop, s.contentBottom);
                } else {
                    a11yRect.set(s.cardRects[i]);
                }
                dy = s.heroY;
                break;
            case Z_SHELF:
                if (i >= s.tileRects.length) return false;
                a11yRect.set(s.tileRects[i]);
                dy = s.tilesY;
                break;
            case Z_DRAWER:
                if (i >= s.drawerRects.length) return false;
                a11yRect.set(s.drawerRects[i]);
                dy = s.drawerOpen ? 0 : s.h;
                break;
            case WIDGETS:
                if (!s.drawerOpen) return false;
                if (i != 0) return false;
                a11yRect.set(s.weatherCard);
                dy = 0;
                break;
            default:
                return false;
        }
        a11yRect.offset(0, dy);
        float edge = 24 * s.k;
        if (a11yRect.top >= s.h - edge) a11yRect.set(a11yRect.left, s.h - edge, a11yRect.right, s.h);
        else if (a11yRect.bottom <= edge) a11yRect.set(a11yRect.left, 0, a11yRect.right, edge);
        a11yRect.round(out);
        return out.width() > 0 && out.height() > 0;
    }

    @Override public int focused() {
        State s = read();
        if (s.sheetOpen) return s.others.isEmpty() ? -1 : id(GRID, s.gridIndex);
        switch (s.zone) {
            case Z_TOP: return id(Z_TOP, s.topIndex);
            case Z_CARDS: return s.tab == 4 ? id(Z_CARDS, 0) : s.cardIndex < s.cards.size() ? id(Z_CARDS, s.cardIndex) : -1;
            case Z_SHELF: return s.shelf.isEmpty() ? -1 : id(Z_SHELF, s.shelfIndex);
            case Z_DRAWER: return s.drawerItems.isEmpty() ? -1 : id(Z_DRAWER, s.drawerIndex);
            case WIDGETS: return id(WIDGETS, s.widgetIndex);
            default: return -1;
        }
    }

    @Override public void focus(int id) {
        host.wake();
        if (id <= 0) return;
        State s = read();
        if (s.spinning) return;
        int group = (id - 1) / 1000, i = (id - 1) % 1000;
        if (group == GRID) {
            if (s.sheetOpen && i < s.others.size()) host.focus(group, i);
            return;
        }
        if (s.sheetOpen) return;
        switch (group) {
            case Z_DRAWER: if (i >= s.drawerItems.size()) return; break;
            case WIDGETS: if (i != 0 || !s.drawerOpen || !s.weatherBottom) return; break;
            case Z_TOP: if (i >= s.topRects.length || !s.topVisible[i]) return; break;
            case Z_CARDS: if (i >= (s.tab == 4 ? 1 : s.cards.size())) return; break;
            case Z_SHELF: if (i >= s.shelf.size()) return; break;
            default: return;
        }
        host.focus(group, i);
    }

    @Override public void click(int id) { host.activate(); }
    @Override public boolean clickable(int id) { return (id - 1) / 1000 != WIDGETS; }

    private static String label(List<HomeApps.Item> list, int i) {
        return i >= 0 && i < list.size() && list.get(i).label != null ? list.get(i).label : "";
    }

    private static String pickText(Recs.Pick p) {
        if (p == null) return "";
        StringBuilder s = new StringBuilder(p.title == null ? "" : p.title);
        if (p.meta != null && !p.meta.isEmpty()) s.append(", ").append(p.meta);
        String app = Recs.serviceName(p);
        if (app != null && !app.isEmpty()) s.append(". App: ").append(app);
        return s.toString();
    }

    private String weatherText() {
        StringBuilder s = new StringBuilder(Prefs.city().isEmpty() ? AppStrings.get(R.string.home_weather) : Prefs.city());
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) return s.append(". ").append(AppStrings.get(R.string.home_no_weather_data_right_now)).toString();
        s.append(". ").append(HomeData.degrees(wx.temp)).append(", ").append(HomeData.condition(wx.code, wx.isDay))
                .append(AppStrings.get(R.string.home_spoken_high)).append(HomeData.degrees(wx.max))
                .append(AppStrings.get(R.string.home_spoken_low)).append(HomeData.degrees(wx.min)).append('.');
        int hours = Math.min(6, wx.hours.size());
        if (hours > 0) s.append(AppStrings.get(R.string.home_spoken_next_hours));
        for (int i = 0; i < hours; i++) {
            s.append(HomeData.hour(wx.hours.get(i))).append(' ').append(HomeData.degrees(wx.hourTemp.get(i))).append(i < hours - 1 ? ", " : ".");
        }
        if (wx.sunrise != 0) s.append(AppStrings.get(R.string.home_spoken_sunrise)).append(Ui.time(wx.sunrise)).append('.');
        if (wx.sunset != 0) s.append(AppStrings.get(R.string.home_spoken_sunset)).append(Ui.time(wx.sunset)).append('.');
        return s.toString();
    }

    private static String networkLabel(int networkState) {
        String state = networkState == NetworkStatus.WIFI
                ? (AppStrings.get(R.string.home_network_wifi))
                : networkState == NetworkStatus.ETHERNET
                ? (AppStrings.get(R.string.home_network_ethernet))
                : (AppStrings.get(R.string.home_network_offline));
        return state + (AppStrings.get(R.string.home_network_action));
    }

    boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled()
                && !am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isEmpty();
    }
}
