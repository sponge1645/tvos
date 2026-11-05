package io.tvos;

import android.graphics.RectF;

/** Directional focus routing above the Dock. The view owns rendering and key/hold lifecycles. */
final class HomeNavigation {
    static final class State {
        int zone, tab, topIndex, cardIndex, cardPage, dockIndex, dockCount, cardCount;
        boolean picksOn, weatherAvailable, headerDirty, moved;
        float weatherX;
        RectF[] dockRects, cardRects, topRects;
    }
    interface Host {
        int catalogSize();
        int firstTopControl();
        int availableTopButton(int preferred);
        int posterCornerTarget(boolean rightmost);
        int nextTopControl(int current, int direction);
        boolean topControlVisible(int id);
        boolean resetRandom(int index, int last);
        /** Apply selection, refresh content, then read back clamped indices and current geometry. */
        void refresh(State state);
        void selectTab(State state, int tab);
    }
    final State state = new State();
    private final Host host;
    private static final int Z_SHELF = HomeAccessibility.Z_SHELF, Z_CARDS = HomeAccessibility.Z_CARDS,
            Z_TOP = HomeAccessibility.Z_TOP, Z_WEATHER = HomeAccessibility.Z_WEATHER;

    HomeNavigation(Host host) {
        this.host = host;
    }

    boolean move(State s, int dx, int dy) {
        s.headerDirty = s.moved = false;
        switch (s.zone) {
            case Z_WEATHER:
                if (dy > 0 && s.weatherAvailable) {
                    if (s.picksOn && s.cardCount != 0) { s.zone = Z_CARDS; s.cardIndex = 0; }
                    else { s.zone = Z_SHELF; if (s.dockCount != 0) s.dockIndex = 0; }
                } else if (dx > 0 || !s.weatherAvailable) {
                    s.zone = Z_TOP; s.topIndex = host.firstTopControl();
                    s.headerDirty = true;
                } else return true;
                break;
            case Z_SHELF:
                if (dy >= 0) return false;
                if (s.dockCount == 0) {
                    s.zone = Z_TOP;
                    s.topIndex = !s.picksOn ? host.availableTopButton(5) : s.tab;
                } else if (!s.picksOn || s.cardCount == 0) {
                    if (s.picksOn) {
                        s.zone = Z_TOP; s.topIndex = s.tab;
                    } else if (s.dockIndex == s.dockCount - 1 && s.dockIndex > 0) {
                        s.zone = Z_TOP; s.topIndex = host.posterCornerTarget(true);
                        s.headerDirty = true;
                    } else focusHeaderAbove(s, s.dockRects[s.dockIndex].centerX());
                } else {
                    s.zone = Z_CARDS;
                    s.cardIndex = nearest(s.cardRects, s.cardCount, s.dockRects[s.dockIndex].centerX());
                }
                break;
            case Z_CARDS:

                if (dx != 0) {
                    int next = Math.max(0, Math.min(s.cardCount - 1, s.cardIndex + dx));
                    if (next == s.cardIndex) {
                        int total = host.catalogSize();
                        int perPage = 5;
                        if (dx > 0 && (s.cardPage + 1) * perPage < total) {
                            s.cardPage++;
                            s.cardIndex = 0;
                        } else if (dx < 0 && s.cardPage > 0) {
                            s.cardPage--;
                            s.cardIndex = perPage - 1;
                        } else return true;
                        host.refresh(s);
                        // host.refresh(s) clamps focus for the last (possibly incomplete) page.
                    } else s.cardIndex = next;
                    if (host.resetRandom(s.cardPage * 5 + s.cardIndex, host.catalogSize() - 1))
                        host.refresh(s);
                } else if (dy < 0) {
                    s.zone = Z_TOP;
                    // Route by visible position, not fixed action IDs. The last poster
                    // reaches the rightmost action; the preceding poster reaches the
                    // action immediately to its left (or the same one if only one exists).
                    if (s.cardIndex == 4) {
                        s.topIndex = host.posterCornerTarget(true);
                    } else if (s.cardIndex == 3) {
                        s.topIndex = host.posterCornerTarget(false);
                    } else {
                        s.topIndex = s.tab;
                    }
                } else {
                    s.zone = Z_SHELF;
                    if (s.dockCount != 0) s.dockIndex = nearest(s.dockRects, s.dockRects.length, s.cardRects[s.cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dy < 0) return true; // All header controls share one row.
                if (dx != 0) {
                    if (dx < 0 && s.weatherAvailable && s.topIndex == host.firstTopControl()) {
                        s.zone = Z_WEATHER; s.headerDirty = true; break;
                    }
                    int next = host.nextTopControl(s.topIndex, dx);
                    if (next == s.topIndex) return true;
                    s.topIndex = next;
                    if (s.topIndex < 5) host.selectTab(s, s.topIndex);   // category tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (!s.picksOn || s.cardCount == 0) {
                        s.zone = Z_SHELF;
                        if (s.dockCount != 0) s.dockIndex = nearest(s.dockRects, s.dockRects.length, s.topRects[s.topIndex].centerX());
                    } else {
                        s.zone = Z_CARDS;
                        s.cardIndex = nearest(s.cardRects, s.cardCount, s.topRects[s.topIndex].centerX());
                    }
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        if (s.zone == Z_TOP && !host.topControlVisible(s.topIndex)) s.zone = Z_SHELF;
        s.moved = true;
        return true;
    }

    private void focusHeaderAbove(State s, float x) {
        int target = -1; float distance = Float.MAX_VALUE;
        for (int i = 0; i < s.topRects.length; i++) if (host.topControlVisible(i)) {
            float d = Math.abs(s.topRects[i].centerX() - x);
            if (d < distance) { distance = d; target = i; }
        }
        if (s.weatherAvailable && Math.abs(s.weatherX - x) < distance) {
            s.zone = Z_WEATHER;
        } else {
            s.zone = Z_TOP; s.topIndex = target >= 0 ? target : host.availableTopButton(5);
        }
        s.headerDirty = true;
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
