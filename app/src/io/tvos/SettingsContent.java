package io.tvos;

/** Settings text and stable screen IDs; no drawing, preferences, actions or focus state. */
final class SettingsContent {
    private SettingsContent() { }
    static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, GENERAL = 6, WIDGETS = 7, SYSTEM = 8, ABOUT = 9, SCREENSAVER = 10, WEATHER = 11, DATE_TIME = 12, MENU_BUTTONS = 13, UPDATES = 14, TOP_PANEL = 15, ACCESSIBILITY = 16, RESET = 17, VPN_SETTINGS = 18, APPS_DOWNLOADS = 19, CATEGORY_EDIT = 20;
    static final int PICK_CITY = 0, PICK_VPN = -1;

    static String screenTitle(int screen, int settingsGroup, int pickFor) {
        return screenTitle(screen, settingsGroup, pickFor, AppLanguage.current());
    }

    static String[] menuLabels(int screen) { return menuLabels(screen, AppLanguage.current()); }

    static String description(int screen, int focus, int pickFor, int settingsGroup, int menuButtonCount) {
        return description(screen, focus, pickFor, settingsGroup, menuButtonCount, AppLanguage.current());
    }

    static String screenTitle(int screen, int settingsGroup, int pickFor, AppLanguage language) {
        switch (screen) {
            case GENERAL: return AppStrings.get(R.string.settings_general, language);
            case WIDGETS: return AppStrings.get(R.string.settings_widgets, language);
            case WEATHER: return AppStrings.get(R.string.settings_weather, language);
            case DATE_TIME: return AppStrings.get(R.string.settings_date, language);
            case SYSTEM: return AppStrings.get(R.string.settings_system, language);
            case RESET: return AppStrings.get(R.string.settings_reset, language);
            case TOP_PANEL: return AppStrings.get(R.string.settings_menu_bar, language);
            case ACCESSIBILITY: return AppStrings.get(R.string.settings_accessibility, language);
            case APPS_DOWNLOADS: return AppStrings.get(R.string.settings_apps, language);
            case MENU_BUTTONS: return AppStrings.get(R.string.settings_buttons, language);
            case UPDATES: return AppStrings.get(R.string.settings_software_updates, language);
            case ABOUT: return AppStrings.get(R.string.settings_about, language);
            case SCREENSAVER: return AppStrings.get(R.string.settings_screen_saver, language);
            case HOME_CHOICE: return AppStrings.get(R.string.settings_default_launcher, language);
            case VPN_SETTINGS: return "VPN";
            case HOME_SETTINGS: return settingsGroup == WIDGETS ? (AppStrings.get(R.string.settings_quotes, language)) : settingsGroup == SYSTEM ? (AppStrings.get(R.string.settings_categories, language)) : (AppStrings.get(R.string.settings_general, language));
            case REMOTE_KEYS: return AppStrings.get(R.string.settings_remotes_and_devices, language);
            case SHELF_EDIT: return "Dock";
            case CATEGORY_EDIT: return AppStrings.get(R.string.settings_categories, language);
            case PICKER: return pickFor == PICK_VPN ? "VPN" : pickFor == PICK_CITY ? (AppStrings.get(R.string.settings_region, language)) : (AppStrings.get(R.string.settings_choose_app, language));
            default: return AppStrings.get(R.string.settings_settings, language);
        }
    }

    static String[] menuLabels(int screen, AppLanguage language) {
        switch (screen) {
            case GENERAL: return new String[] {AppStrings.get(R.string.settings_about, language), AppStrings.get(R.string.settings_appearance, language), AppStrings.get(R.string.settings_language, language), AppStrings.get(R.string.settings_region, language), AppStrings.get(R.string.settings_date_time, language), AppStrings.get(R.string.settings_temperature, language), AppStrings.get(R.string.settings_time_format, language), AppStrings.get(R.string.settings_navigation_clicks, language)};
            case WIDGETS: return new String[] {AppStrings.get(R.string.settings_weather, language), AppStrings.get(R.string.settings_quotes, language), AppStrings.get(R.string.settings_date, language)};
            case DATE_TIME: return new String[] {AppStrings.get(R.string.settings_show_in_menu_bar, language)};
            case WEATHER: return new String[] {AppStrings.get(R.string.settings_show_in_menu_bar, language), AppStrings.get(R.string.settings_show_in_bottom_menu, language)};
            case UPDATES: return new String[] {AppStrings.get(R.string.settings_update_software, language), AppStrings.get(R.string.settings_automatically_update, language)};
            case SYSTEM: return new String[] {AppStrings.get(R.string.settings_software_updates, language), AppStrings.get(R.string.settings_reset, language), AppStrings.get(R.string.settings_restart_tvos, language)};
            case RESET: return new String[] {AppStrings.get(R.string.settings_clear_cache, language), AppStrings.get(R.string.settings_reset_tvos, language)};
            case TOP_PANEL: return new String[] {AppStrings.get(R.string.settings_categories, language), AppStrings.get(R.string.settings_buttons, language)};
            case APPS_DOWNLOADS: return new String[] {AppStrings.get(R.string.settings_download_airplay, language), AppStrings.get(R.string.settings_download_apple_music, language), AppStrings.get(R.string.settings_download_tizentube, language), AppStrings.get(R.string.settings_download_incy, language), AppStrings.get(R.string.settings_download_seena, language), AppStrings.get(R.string.settings_download_lampa, language), AppStrings.get(R.string.settings_download_kinopub, language)};
            case ACCESSIBILITY: return new String[] {AppStrings.get(R.string.settings_reduce_transparency, language), AppStrings.get(R.string.settings_reduce_motion, language), AppStrings.get(R.string.settings_default_launcher, language), AppStrings.get(R.string.settings_tv_settings, language)};
            case VPN_SETTINGS: return new String[] {AppStrings.get(R.string.settings_show_in_menu_bar, language), AppStrings.get(R.string.settings_vpn, language)};
            case SCREENSAVER: return new String[] {AppStrings.get(R.string.settings_current_selection, language), AppStrings.get(R.string.settings_show_clock, language), AppStrings.get(R.string.settings_show_location, language), AppStrings.get(R.string.settings_show_weather, language), AppStrings.get(R.string.settings_preview, language)};
            case ABOUT: return new String[] {AppStrings.get(R.string.settings_name, language), AppStrings.get(R.string.settings_model, language), AppStrings.get(R.string.settings_serial_number, language), "tvOS", AppStrings.get(R.string.settings_os, language), AppStrings.get(R.string.settings_resolution, language), AppStrings.get(R.string.settings_wi_fi_network_name, language), AppStrings.get(R.string.settings_ip_address, language)};
            default: return new String[] {AppStrings.get(R.string.settings_general, language), AppStrings.get(R.string.settings_screen_saver, language), "AirPlay", AppStrings.get(R.string.settings_remotes_and_devices, language), AppStrings.get(R.string.settings_accessibility, language), AppStrings.get(R.string.settings_apps, language), "Dock", AppStrings.get(R.string.settings_menu_bar, language), AppStrings.get(R.string.settings_widgets, language), AppStrings.get(R.string.settings_system, language)};
        }
    }

    static String description(int screen, int focus, int pickFor, int settingsGroup,
                          int menuButtonCount, AppLanguage language) {
        String info = null;
        switch (screen) {
            case MAIN:
                if (focus == 2) info = AppStrings.get(R.string.settings_help_airplay, language);
                else if (focus == 6) info = AppStrings.get(R.string.settings_help_dock, language);
                break;
            case GENERAL:
                if (focus == 3) info = AppStrings.get(R.string.settings_help_region, language);
                break;
            case SCREENSAVER:
                if (focus == 3) info = AppStrings.get(R.string.settings_help_weather_city, language);
                break;
            case APPS_DOWNLOADS:
                info = appDownloadDescription(focus, language);
                break;
            case HOME_CHOICE:
                info = AppStrings.get(R.string.settings_help_launcher, language);
                break;
            case CATEGORY_EDIT:
                info = AppStrings.get(R.string.settings_help_categories, language);
                break;
            case SHELF_EDIT:
                info = AppStrings.get(R.string.settings_help_dock_editor, language);
                break;
            case PICKER:
                info = pickFor == PICK_CITY
                        ? AppStrings.get(R.string.settings_help_region, language)
                        : AppStrings.get(R.string.settings_help_picker, language);
                break;
            case HOME_SETTINGS:
                info = AppStrings.get(R.string.settings_help_value_controls, language);
                if (screen == HOME_SETTINGS && settingsGroup == SYSTEM)
                    info = AppStrings.get(R.string.settings_help_sections, language);
                break;
            case TOP_PANEL:
                if (focus == 1) info = AppStrings.get(R.string.settings_help_menu_buttons, language);
                break;
            case MENU_BUTTONS:
                info = focus == menuButtonCount
                        ? AppStrings.get(R.string.settings_help_vpn_app, language)
                        : AppStrings.get(R.string.settings_help_button_editor, language);
                break;
            case RESET:
                info = AppStrings.get(R.string.settings_help_reset, language);
                break;
            case REMOTE_KEYS:
                info = AppStrings.get(R.string.settings_help_remote, language);
                break;
        }
        return info;
    }

    private static String appDownloadDescription(int index, AppLanguage language) {
        switch (index) {
            case 0: return AppStrings.get(R.string.settings_app_airplay_description, language);
            case 1: return AppStrings.get(R.string.settings_app_music_description, language);
            case 2: return AppStrings.get(R.string.settings_app_tizentube_description, language);
            case 3: return AppStrings.get(R.string.settings_app_incy_description, language);
            case 4: return AppStrings.get(R.string.settings_app_seena_description, language);
            case 5: return AppStrings.get(R.string.settings_app_lampa_description, language);
            case 6: return AppStrings.get(R.string.settings_app_kinopub_description, language);
            default: return null;
        }
    }

}
