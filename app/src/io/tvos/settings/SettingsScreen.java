package io.tvos.settings;

/** Stable screen identities and structure shared by navigation, focus and presentation. */
public final class SettingsScreen {
    private SettingsScreen() { }
    public static final int REMOTE_INPUT_ROW = 5, REMOTE_ADVANCED_ROW = 6;
    public static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, GENERAL = 6, WIDGETS = 7, SYSTEM = 8, ABOUT = 9, SCREENSAVER = 10, WEATHER = 11, DATE_TIME = 12, MENU_BUTTONS = 13, UPDATES = 14, TOP_PANEL = 15, ACCESSIBILITY = 16, RESET = 17, APPS_DOWNLOADS = 19, CATEGORY_EDIT = 20, MEDIA = 21, MOVIE_SERVICE = 22, CITY = 23, HOME_SCREEN = 24, LANGUAGE = 25;
    public static final int PICK_CITY = 0, PICK_VPN = -1, PICK_REGION = -2, PICK_TV = -3;

    /** Returns -1 for data-dependent or unknown screens; the adapter supplies their row count. */
    public static int fixedRows(int screen) {
        switch (screen) {
            case MAIN: return 10;
            case DATE_TIME: return 1;
            case MEDIA: return 3;
            case HOME_SCREEN: case WIDGETS: case RESET: case CITY:
            case UPDATES: case HOME_CHOICE: return 2;
            case WEATHER: case ACCESSIBILITY: return 3;
            case TOP_PANEL: return 5;
            case SYSTEM: return 4;
            case SCREENSAVER: return 5;
            case APPS_DOWNLOADS: return 7;
            case GENERAL: return 5;
            case LANGUAGE: return 2;
            case ABOUT: return 8;
            default: return -1;
        }
    }

    public static int parent(int child, int group, int picker) {
        switch (child) {
            case MEDIA: case GENERAL: case SCREENSAVER: case REMOTE_KEYS: case ACCESSIBILITY: case APPS_DOWNLOADS:
            case SHELF_EDIT: case TOP_PANEL: case SYSTEM:
                return MAIN;
            case HOME_SCREEN: return TOP_PANEL;
            case MOVIE_SERVICE: return MEDIA;
            case ABOUT: return GENERAL;
            case HOME_CHOICE: return SYSTEM;
            case MENU_BUTTONS: case CATEGORY_EDIT: return TOP_PANEL;
            case CITY: return WEATHER;
            case LANGUAGE: return GENERAL;
            case WIDGETS: return TOP_PANEL;
            case WEATHER: case DATE_TIME: return TOP_PANEL;
            case UPDATES: case RESET: return SYSTEM;
            case HOME_SETTINGS: return group == SYSTEM ? TOP_PANEL : group;
            case PICKER: return picker == PICK_REGION ? GENERAL : picker == PICK_TV ? MEDIA : picker == PICK_CITY ? CITY
                    : picker == PICK_VPN ? MENU_BUTTONS : REMOTE_KEYS;
            default: return -1;
        }
    }

    public static boolean hasChevron(int screen, int row, boolean automaticRegion) {
        switch (screen) {
            case MEDIA: return row != 1;
            case GENERAL: return row == 0 || row == 2 || row == 3;
            case TOP_PANEL: return row != 2;
            case WEATHER: return row == 1;
            case LANGUAGE: case SCREENSAVER: case ACCESSIBILITY: case DATE_TIME:
            case HOME_SCREEN: case MOVIE_SERVICE: case APPS_DOWNLOADS: case RESET: case UPDATES: case ABOUT: return false;
            case SYSTEM: return row != 3;
            case CITY: return row == 1 && !automaticRegion;
            default: return true;
        }
    }
}
