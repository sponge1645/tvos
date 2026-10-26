package io.tvos;

/** Settings text and stable screen IDs; no drawing, preferences, actions or focus state. */
final class SettingsContent {
    private SettingsContent() { }
    static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5, GENERAL = 6, WIDGETS = 7, SYSTEM = 8, ABOUT = 9, SCREENSAVER = 10, WEATHER = 11, DATE_TIME = 12, MENU_BUTTONS = 13, UPDATES = 14, TOP_PANEL = 15, ACCESSIBILITY = 16, RESET = 17, VPN_SETTINGS = 18, APPS_DOWNLOADS = 19, CATEGORY_EDIT = 20;
    static final int PICK_CITY = 0, PICK_VPN = -1;

    static String screenTitle(int screen, int settingsGroup, int pickFor, boolean russian) {
        switch (screen) {
            case GENERAL: return AppStrings.get(R.string.settings_general, russian);
            case WIDGETS: return AppStrings.get(R.string.settings_widgets, russian);
            case WEATHER: return AppStrings.get(R.string.settings_weather, russian);
            case DATE_TIME: return AppStrings.get(R.string.settings_date, russian);
            case SYSTEM: return AppStrings.get(R.string.settings_system, russian);
            case RESET: return AppStrings.get(R.string.settings_reset, russian);
            case TOP_PANEL: return AppStrings.get(R.string.settings_menu_bar, russian);
            case ACCESSIBILITY: return AppStrings.get(R.string.settings_accessibility, russian);
            case APPS_DOWNLOADS: return AppStrings.get(R.string.settings_apps, russian);
            case MENU_BUTTONS: return AppStrings.get(R.string.settings_buttons, russian);
            case UPDATES: return AppStrings.get(R.string.settings_software_updates, russian);
            case ABOUT: return AppStrings.get(R.string.settings_about, russian);
            case SCREENSAVER: return AppStrings.get(R.string.settings_screen_saver, russian);
            case HOME_CHOICE: return AppStrings.get(R.string.settings_default_launcher, russian);
            case VPN_SETTINGS: return "VPN";
            case HOME_SETTINGS: return settingsGroup == WIDGETS ? (AppStrings.get(R.string.settings_quotes, russian)) : settingsGroup == SYSTEM ? (AppStrings.get(R.string.settings_categories, russian)) : (AppStrings.get(R.string.settings_general, russian));
            case REMOTE_KEYS: return AppStrings.get(R.string.settings_remotes_and_devices, russian);
            case SHELF_EDIT: return "Dock";
            case CATEGORY_EDIT: return AppStrings.get(R.string.settings_categories, russian);
            case PICKER: return pickFor == PICK_VPN ? "VPN" : pickFor == PICK_CITY ? (AppStrings.get(R.string.settings_region, russian)) : (AppStrings.get(R.string.settings_choose_app, russian));
            default: return AppStrings.get(R.string.settings_settings, russian);
        }
    }

    static String[] menuLabels(int screen, boolean russian) {
        switch (screen) {
            case GENERAL: return new String[] {AppStrings.get(R.string.settings_about, russian), AppStrings.get(R.string.settings_appearance, russian), AppStrings.get(R.string.settings_language, russian), AppStrings.get(R.string.settings_region, russian), AppStrings.get(R.string.settings_date_time, russian), AppStrings.get(R.string.settings_temperature, russian), AppStrings.get(R.string.settings_time_format, russian), AppStrings.get(R.string.settings_navigation_clicks, russian)};
            case WIDGETS: return new String[] {AppStrings.get(R.string.settings_weather, russian), AppStrings.get(R.string.settings_quotes, russian), AppStrings.get(R.string.settings_date, russian)};
            case DATE_TIME: return new String[] {AppStrings.get(R.string.settings_show_in_menu_bar, russian)};
            case WEATHER: return new String[] {AppStrings.get(R.string.settings_show_in_menu_bar, russian), AppStrings.get(R.string.settings_show_in_bottom_menu, russian)};
            case UPDATES: return new String[] {AppStrings.get(R.string.settings_update_software, russian), AppStrings.get(R.string.settings_automatically_update, russian)};
            case SYSTEM: return new String[] {AppStrings.get(R.string.settings_software_updates, russian), AppStrings.get(R.string.settings_reset, russian), AppStrings.get(R.string.settings_restart_tvos, russian)};
            case RESET: return new String[] {AppStrings.get(R.string.settings_clear_cache, russian), AppStrings.get(R.string.settings_reset_tvos, russian)};
            case TOP_PANEL: return new String[] {AppStrings.get(R.string.settings_categories, russian), AppStrings.get(R.string.settings_buttons, russian)};
            case APPS_DOWNLOADS: return new String[] {AppStrings.get(R.string.settings_download_airplay, russian), AppStrings.get(R.string.settings_download_apple_music, russian), AppStrings.get(R.string.settings_download_tizentube, russian), AppStrings.get(R.string.settings_download_incy, russian), AppStrings.get(R.string.settings_download_seena, russian), AppStrings.get(R.string.settings_download_lampa, russian), AppStrings.get(R.string.settings_download_kinopub, russian)};
            case ACCESSIBILITY: return new String[] {AppStrings.get(R.string.settings_reduce_transparency, russian), AppStrings.get(R.string.settings_reduce_motion, russian), AppStrings.get(R.string.settings_default_launcher, russian), AppStrings.get(R.string.settings_tv_settings, russian)};
            case VPN_SETTINGS: return new String[] {AppStrings.get(R.string.settings_show_in_menu_bar, russian), AppStrings.get(R.string.settings_vpn, russian)};
            case SCREENSAVER: return new String[] {AppStrings.get(R.string.settings_current_selection, russian), AppStrings.get(R.string.settings_show_clock, russian), AppStrings.get(R.string.settings_show_location, russian), AppStrings.get(R.string.settings_show_weather, russian), AppStrings.get(R.string.settings_preview, russian)};
            case ABOUT: return new String[] {AppStrings.get(R.string.settings_name, russian), AppStrings.get(R.string.settings_model, russian), AppStrings.get(R.string.settings_serial_number, russian), "tvOS", AppStrings.get(R.string.settings_os, russian), AppStrings.get(R.string.settings_resolution, russian), AppStrings.get(R.string.settings_wi_fi_network_name, russian), AppStrings.get(R.string.settings_ip_address, russian)};
            default: return new String[] {AppStrings.get(R.string.settings_general, russian), AppStrings.get(R.string.settings_screen_saver, russian), "AirPlay", AppStrings.get(R.string.settings_remotes_and_devices, russian), AppStrings.get(R.string.settings_accessibility, russian), AppStrings.get(R.string.settings_apps, russian), "Dock", AppStrings.get(R.string.settings_menu_bar, russian), AppStrings.get(R.string.settings_widgets, russian), AppStrings.get(R.string.settings_system, russian)};
        }
    }

    static String description(int screen, int focus, int pickFor, int settingsGroup,
                          int menuButtonCount, boolean russian) {
        String info = null;
        switch (screen) {
            case MAIN:
                if (focus == 2) info = AppStrings.get(R.string.settings_help_airplay, russian);
                else if (focus == 6) info = AppStrings.get(R.string.settings_help_dock, russian);
                break;
            case GENERAL:
                if (focus == 3) info = AppStrings.get(R.string.settings_help_region, russian);
                break;
            case SCREENSAVER:
                if (focus == 3) info = AppStrings.get(R.string.settings_help_weather_city, russian);
                break;
            case APPS_DOWNLOADS:
                info = appDownloadDescription(focus, russian);
                break;
            case HOME_CHOICE:
                info = AppStrings.get(R.string.settings_help_launcher, russian);
                break;
            case CATEGORY_EDIT:
                info = AppStrings.get(R.string.settings_help_categories, russian);
                break;
            case SHELF_EDIT:
                info = AppStrings.get(R.string.settings_help_dock_editor, russian);
                break;
            case PICKER:
                info = pickFor == PICK_CITY
                        ? AppStrings.get(R.string.settings_help_region, russian)
                        : AppStrings.get(R.string.settings_help_picker, russian);
                break;
            case HOME_SETTINGS:
                info = AppStrings.get(R.string.settings_help_value_controls, russian);
                if (screen == HOME_SETTINGS && settingsGroup == SYSTEM)
                    info = AppStrings.get(R.string.settings_help_sections, russian);
                break;
            case TOP_PANEL:
                if (focus == 1) info = AppStrings.get(R.string.settings_help_menu_buttons, russian);
                break;
            case MENU_BUTTONS:
                info = focus == menuButtonCount
                        ? AppStrings.get(R.string.settings_help_vpn_app, russian)
                        : AppStrings.get(R.string.settings_help_button_editor, russian);
                break;
            case RESET:
                info = AppStrings.get(R.string.settings_help_reset, russian);
                break;
            case REMOTE_KEYS:
                info = AppStrings.get(R.string.settings_help_remote, russian);
                break;
        }
        return info;
    }

    private static String appDownloadDescription(int index, boolean russian) {
        switch (index) {
            case 0: return AppStrings.get(R.string.settings_app_airplay_description, russian);
            case 1: return AppStrings.get(R.string.settings_app_music_description, russian);
            case 2: return AppStrings.get(R.string.settings_app_tizentube_description, russian);
            case 3: return AppStrings.get(R.string.settings_app_incy_description, russian);
            case 4: return AppStrings.get(R.string.settings_app_seena_description, russian);
            case 5: return AppStrings.get(R.string.settings_app_lampa_description, russian);
            case 6: return AppStrings.get(R.string.settings_app_kinopub_description, russian);
            default: return null;
        }
    }

}
