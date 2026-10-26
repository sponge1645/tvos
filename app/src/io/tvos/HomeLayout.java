package io.tvos;

/** Home geometry in a 1920 x 1080 reference space; callers scale by screen width. */
final class HomeLayout {
    private HomeLayout() { }
    static final float WIDTH = 1920, HEIGHT = 1080, EDGE = 40, RIGHT = WIDTH - EDGE;
    static final float TOP = 50, DOCK_BOTTOM = 60, BUTTON_SCALE = 0.9f;
    static final float BUTTON = 68 * BUTTON_SCALE, HEADER_CY = TOP + BUTTON / 2;
    static final float TAB_TOP = 158, TAB_HEIGHT = 68;
    static final float POSTER_TITLE_BASELINE = 46, POSTER_META_BASELINE = 85;
    static final float DOCK_PAD = 40, DOCK_GAP = 48, DOCK_ASPECT = 5f / 3;
    static final int FULL_DOCK_COUNT = 6;
    static final float DOCK_CONTENT_WIDTH = WIDTH - 2 * EDGE - 2 * DOCK_PAD;
    static final float MAX_TILE_WIDTH = (DOCK_CONTENT_WIDTH - (FULL_DOCK_COUNT - 1) * DOCK_GAP) / FULL_DOCK_COUNT;
    static final float MAX_TILE_HEIGHT = MAX_TILE_WIDTH / DOCK_ASPECT;
    static final float DOCK_LAYER_TOP = 720, CARDS_LAYER_HEIGHT = 808;
    static final float WEATHER_HEIGHT = 300;
    static final int APP_COLUMNS = 5;
    static final float APP_GAP = 40;
    static final float APP_WIDTH = (WIDTH - 2 * EDGE - (APP_COLUMNS - 1) * APP_GAP) / APP_COLUMNS;
    static final float APP_HEIGHT = APP_WIDTH * 9 / 16;
    static final float APP_ROW = APP_HEIGHT + 70, APP_TOP = TOP + BUTTON + 40;

    static float dockTileWidth(int count, float scale) {
        float available = DOCK_CONTENT_WIDTH - Math.max(0, count - 1) * DOCK_GAP;
        return Math.min(MAX_TILE_WIDTH, available / Math.max(1, count)) * scale;
    }

    static float dockBottom(float screenHeight, float scale) { return screenHeight - DOCK_BOTTOM * scale; }
    /** Center the whole visible block, including captions, in the free vertical band. */
    static float centeredTop(float top, float bottom, float blockHeight) {
        return (top + bottom - blockHeight) / 2f;
    }

    static float weatherTop(float screenHeight, float scale) { return screenHeight - (EDGE + WEATHER_HEIGHT) * scale; }
}
