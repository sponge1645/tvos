package io.tvos;

import static io.tvos.SettingsContent.*;

/** Settings input decisions. The view applies effects and keeps animation/lifecycle ownership. */
final class SettingsActions {
    private SettingsActions() { }

    enum Action {
        NONE, NAME, HOME_MODE, SELECT_CITY, SELECT_VPN, SELECT_KEY, CATEGORY, SHELF,
        KEY_PICKER, CITY_PICKER, HOME_ADJUST, AIRPLAY, REMOTES, SHELF_EDITOR, DOWNLOAD,
        DATE_SETTINGS, MENU_ADJUST, CHECK_UPDATE, AUTO_UPDATE, MENU_BUTTON,
        VPN_TOGGLE, VPN_PICKER, DATE_TOGGLE, WEATHER_TOGGLE, PREVIEW, CATEGORY_EDITOR,
        BUTTON_EDITOR, HOME_CHOICE, TV_SETTINGS, RESET_CONFIRM, RESTART,
        GENERAL_SCREEN(GENERAL), SCREENSAVER_SCREEN(SCREENSAVER),
        ACCESSIBILITY_SCREEN(ACCESSIBILITY), DOWNLOADS_SCREEN(APPS_DOWNLOADS),
        TOP_PANEL_SCREEN(TOP_PANEL), WIDGETS_SCREEN(WIDGETS), SYSTEM_SCREEN(SYSTEM),
        ABOUT_SCREEN(ABOUT), DATE_SCREEN(DATE_TIME), WEATHER_SCREEN(WEATHER),
        VPN_SCREEN(VPN_SETTINGS), UPDATES_SCREEN(UPDATES), RESET_SCREEN(RESET);

        final int targetScreen;
        Action() { targetScreen = -1; }
        Action(int screen) { targetScreen = screen; }
    }

    enum Adjustment {
        NONE, HOME, WEATHER, DATE, DREAM_SOURCE, DREAM_CLOCK, DREAM_LOCATION, DREAM_WEATHER,
        AUTO_UPDATE, TRANSPARENCY, MOTION, VPN, MENU_BUTTON, GENERAL
    }

    static boolean canAdjust(int screen, int row, int buttonCount) {
        switch (screen) {
            case HOME_SETTINGS: case WEATHER: case DATE_TIME: return true;
            case GENERAL: return row == 1 || row == 2 || row == 5 || row == 6 || row == 7;
            case SCREENSAVER: return row >= 0 && row < 4;
            case UPDATES: return row == 1;
            case ACCESSIBILITY: return row < 2;
            case VPN_SETTINGS: return row == 0;
            case MENU_BUTTONS: return row < buttonCount;
            default: return false;
        }
    }

    static Adjustment adjustment(int screen, int row) {
        switch (screen) {
            case HOME_SETTINGS: return Adjustment.HOME;
            case WEATHER: return Adjustment.WEATHER;
            case DATE_TIME: return Adjustment.DATE;
            case SCREENSAVER:
                switch (row) {
                    case 0: return Adjustment.DREAM_SOURCE;
                    case 1: return Adjustment.DREAM_CLOCK;
                    case 2: return Adjustment.DREAM_LOCATION;
                    case 3: return Adjustment.DREAM_WEATHER;
                    default: return Adjustment.NONE;
                }
            case UPDATES: return row == 1 ? Adjustment.AUTO_UPDATE : Adjustment.NONE;
            case ACCESSIBILITY: return row == 0 ? Adjustment.TRANSPARENCY : row == 1 ? Adjustment.MOTION : Adjustment.NONE;
            case VPN_SETTINGS: return row == 0 ? Adjustment.VPN : Adjustment.NONE;
            case MENU_BUTTONS: return Adjustment.MENU_BUTTON;
            case GENERAL: return Adjustment.GENERAL;
            default: return Adjustment.NONE;
        }
    }

    static Action activation(int screen, int row, int pickFor, boolean citySetting,
                             int buttonCount, boolean vpnEnabled) {
        switch (screen) {
            case ABOUT: return Action.NAME;
            case HOME_CHOICE: return Action.HOME_MODE;
            case PICKER: return pickFor == PICK_CITY ? Action.SELECT_CITY : pickFor == PICK_VPN ? Action.SELECT_VPN : Action.SELECT_KEY;
            case CATEGORY_EDIT: return Action.CATEGORY;
            case SHELF_EDIT: return Action.SHELF;
            case REMOTE_KEYS: return Action.KEY_PICKER;
            case HOME_SETTINGS: return citySetting ? Action.CITY_PICKER : Action.HOME_ADJUST;
            case MAIN:
                switch (row) {
                    case 0: return Action.GENERAL_SCREEN;
                    case 1: return Action.SCREENSAVER_SCREEN;
                    case 2: return Action.AIRPLAY;
                    case 3: return Action.REMOTES;
                    case 4: return Action.ACCESSIBILITY_SCREEN;
                    case 5: return Action.DOWNLOADS_SCREEN;
                    case 6: return Action.SHELF_EDITOR;
                    case 7: return Action.TOP_PANEL_SCREEN;
                    case 8: return Action.WIDGETS_SCREEN;
                    case 9: return Action.SYSTEM_SCREEN;
                    default: return Action.NONE;
                }
            case APPS_DOWNLOADS: return Action.DOWNLOAD;
            case GENERAL: return row == 3 ? Action.CITY_PICKER : row == 0 ? Action.ABOUT_SCREEN : row == 4 ? Action.DATE_SETTINGS : Action.MENU_ADJUST;
            case WIDGETS: return row == 1 ? Action.DATE_SCREEN : row == 0 ? Action.WEATHER_SCREEN : Action.NONE;
            case UPDATES: return row == 0 ? Action.CHECK_UPDATE : Action.AUTO_UPDATE;
            case MENU_BUTTONS: return row == buttonCount ? Action.VPN_SCREEN : Action.MENU_BUTTON;
            case VPN_SETTINGS: return row == 0 ? Action.VPN_TOGGLE : vpnEnabled ? Action.VPN_PICKER : Action.NONE;
            case DATE_TIME: return Action.DATE_TOGGLE;
            case WEATHER: return Action.WEATHER_TOGGLE;
            case SCREENSAVER: return row == 4 ? Action.PREVIEW : row >= 0 && row < 4 ? Action.MENU_ADJUST : Action.NONE;
            case TOP_PANEL: return row == 0 ? Action.CATEGORY_EDITOR : row == 1 ? Action.BUTTON_EDITOR : Action.NONE;
            case ACCESSIBILITY: return row == 0 || row == 1 ? Action.MENU_ADJUST : row == 2 ? Action.TV_SETTINGS : Action.NONE;
            case RESET: return Action.RESET_CONFIRM;
            case SYSTEM: return row == 0 ? Action.UPDATES_SCREEN : row == 1 ? Action.RESET_SCREEN : row == 2 ? Action.HOME_CHOICE : row == 3 ? Action.RESTART : Action.NONE;
            default: return Action.NONE;
        }
    }

    static final class Target {
        final int screen, row;
        Target(int screen, int row) { this.screen = screen; this.row = row; }
    }

    static Target back(int screen, int pickFor, int group, int buttonCount) {
        switch (screen) {
            case MAIN: return null;
            case PICKER: return pickFor == PICK_CITY ? new Target(GENERAL, 3)
                    : pickFor == PICK_VPN ? new Target(VPN_SETTINGS, 1) : new Target(REMOTE_KEYS, pickFor - 1);
            case HOME_CHOICE: return new Target(SYSTEM, 2);
            case SCREENSAVER: return new Target(MAIN, 1);
            case ABOUT: return new Target(GENERAL, 0);
            case REMOTE_KEYS: return new Target(MAIN, 3);
            case MENU_BUTTONS: return new Target(TOP_PANEL, 1);
            case CATEGORY_EDIT: return new Target(TOP_PANEL, 0);
            case VPN_SETTINGS: return new Target(MENU_BUTTONS, buttonCount);
            case UPDATES: return new Target(SYSTEM, 0);
            case HOME_SETTINGS: return new Target(group == SYSTEM ? TOP_PANEL : group, 0);
            case RESET: return new Target(SYSTEM, 1);
            case WEATHER: return new Target(WIDGETS, 0);
            case DATE_TIME: return new Target(WIDGETS, 1);
            case SHELF_EDIT: return new Target(MAIN, 6);
            case APPS_DOWNLOADS: return new Target(MAIN, 5);
            default: return new Target(MAIN, screen == GENERAL ? 0 : screen == ACCESSIBILITY ? 4 : screen == TOP_PANEL ? 7 : screen == WIDGETS ? 8 : screen == SYSTEM ? 9 : 0);
        }
    }
}
