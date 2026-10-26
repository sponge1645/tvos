package io.tvos;

/** Compatibility decoder for historical messages. All translated copy lives in XML resources. */
final class Russian {
    private Russian() {}

    static String count(long n, String one, String few, String many) {
        long last = Math.abs(n % 100);
        String word = last >= 11 && last <= 14 ? many
                : last % 10 == 1 ? one : last % 10 >= 2 && last % 10 <= 4 ? few : many;
        return n + " " + word;
    }

    static String text(String english) {
        switch (english) {
            case "Live TV": return AppStrings.get(R.string.runtime_live_tv);
            case "All Apps": return AppStrings.get(R.string.runtime_all_apps);
            case "Settings": return AppStrings.get(R.string.runtime_settings);
            case "Home Settings": return AppStrings.get(R.string.runtime_home_settings);
            case "TV Settings": return AppStrings.get(R.string.runtime_tv_settings);
            case "HDMI Inputs": return AppStrings.get(R.string.runtime_hdmi_inputs);
            case "Inputs": return AppStrings.get(R.string.runtime_inputs);
            case "Shortcuts": return AppStrings.get(R.string.runtime_shortcuts);
            case "Weather": return AppStrings.get(R.string.runtime_weather);
            case "No weather data right now.": return AppStrings.get(R.string.runtime_no_weather_data_right_now);
            case "Sunrise ": return AppStrings.get(R.string.runtime_sunrise);
            case "Sunset ": return AppStrings.get(R.string.runtime_sunset);
            case "Crypto": return AppStrings.get(R.string.runtime_crypto);
            case "24h change": return AppStrings.get(R.string.runtime_24h_change);
            case "For You": return AppStrings.get(R.string.runtime_for_you);
            case "Couldn't open ": return AppStrings.get(R.string.runtime_couldn_t_open);
            case " of ": return AppStrings.get(R.string.runtime_of);
            case ", tab ": return AppStrings.get(R.string.runtime_tab);
            case " of 3": return AppStrings.get(R.string.runtime_of_3);
            case "Dollar": return AppStrings.get(R.string.runtime_dollar);
            case "Series": return AppStrings.get(R.string.runtime_series);
            case "prices from ": return AppStrings.get(R.string.runtime_prices_from);
            case "Starting…": return AppStrings.get(R.string.runtime_starting);
            case "Original": return AppStrings.get(R.string.runtime_original);
            case "Home Screen, ": return AppStrings.get(R.string.runtime_home_screen);
            case "Screensaver, preview": return AppStrings.get(R.string.runtime_screensaver_preview);
            case "no ads, with the new screensaver": return AppStrings.get(R.string.runtime_no_ads_with_the_new_screensaver);
            case "the TV's factory home screen": return AppStrings.get(R.string.runtime_the_tv_s_factory_home_screen);
            case ". In use": return AppStrings.get(R.string.runtime_in_use);
            case " of 2": return AppStrings.get(R.string.runtime_of_2);
            case "Your TV's home screen and screensaver": return AppStrings.get(R.string.runtime_your_tv_s_home_screen_and_screensaver);
            case "Home Screen": return AppStrings.get(R.string.runtime_home_screen_2);
            case "Screensaver": return AppStrings.get(R.string.runtime_screensaver);
            case "Preview": return AppStrings.get(R.string.runtime_preview);
            case "On the TV: tick “Always allow” and press OK.": return AppStrings.get(R.string.runtime_on_the_tv_tick_always_allow_and_press_ok);
            case "RECOMMENDATIONS": return AppStrings.get(R.string.runtime_recommendations);
            case "No TMDB key: see the README (config.properties).": return AppStrings.get(R.string.runtime_no_tmdb_key_see_the_readme_config_properties);
            case "Error: ": return AppStrings.get(R.string.runtime_error);
            case "Fetching titles…": return AppStrings.get(R.string.runtime_fetching_titles);
            case "This product uses the TMDB API but is not endorsed or certified by TMDB.": return AppStrings.get(R.string.runtime_this_product_uses_the_tmdb_api_but_is_not_endorsed_or_certified_b);
            case "COMPARISON": return AppStrings.get(R.string.runtime_comparison);
            case "Home button": return AppStrings.get(R.string.runtime_home_button);
            case "Free memory": return AppStrings.get(R.string.runtime_free_memory);
            case "Home screen memory": return AppStrings.get(R.string.runtime_home_screen_memory);
            case "NOTICES": return AppStrings.get(R.string.runtime_notices);
            case "None yet.": return AppStrings.get(R.string.runtime_none_yet);
            case "No ads, with the new screensaver": return AppStrings.get(R.string.runtime_no_ads_with_the_new_screensaver_2);
            case "The TV's factory home screen": return AppStrings.get(R.string.runtime_the_tv_s_factory_home_screen_2);
            case "Switching takes a few seconds; the screen blinks while it measures.": return AppStrings.get(R.string.runtime_switching_takes_a_few_seconds_the_screen_blinks_while_it_measures);
            case "now": return AppStrings.get(R.string.runtime_now);
            case "Measuring the original home screen…": return AppStrings.get(R.string.runtime_measuring_the_original_home_screen);
            case "Switching the home screen and screensaver…": return AppStrings.get(R.string.runtime_switching_the_home_screen_and_screensaver);
            case "Android didn't accept the new home screen": return AppStrings.get(R.string.runtime_android_didn_t_accept_the_new_home_screen);
            case "Turning off apps (": return AppStrings.get(R.string.runtime_turning_off_apps);
            case "Measuring tvOS…": return AppStrings.get(R.string.runtime_measuring_tvos);
            case "Couldn't turn on tvOS: ": return AppStrings.get(R.string.runtime_couldn_t_turn_on_tvos);
            case "Turning the apps back on…": return AppStrings.get(R.string.runtime_turning_the_apps_back_on);
            case "Turning apps back on (": return AppStrings.get(R.string.runtime_turning_apps_back_on);
            case "Restoring the original home screen…": return AppStrings.get(R.string.runtime_restoring_the_original_home_screen);
            case "Original mode: the factory home screen, screensaver and apps are back.": return AppStrings.get(R.string.runtime_original_mode_the_factory_home_screen_screensaver_and_apps_are_ba);
            case "the original home screen": return AppStrings.get(R.string.runtime_the_original_home_screen);
            case "Original mode, but I couldn't turn back on: ": return AppStrings.get(R.string.runtime_original_mode_but_i_couldn_t_turn_back_on);
            case "Couldn't go back to the original: ": return AppStrings.get(R.string.runtime_couldn_t_go_back_to_the_original);
            case "tvOS's home screen crashed 3 times in a row; I switched back to the original one.": return AppStrings.get(R.string.runtime_tvos_s_home_screen_crashed_3_times_in_a_row_i_switched_back_to_th);
            case "the home screen": return AppStrings.get(R.string.runtime_the_home_screen);
            case "the screensaver": return AppStrings.get(R.string.runtime_the_screensaver);
            case "Couldn't check tvOS: ": return AppStrings.get(R.string.runtime_couldn_t_check_tvos);
            case "The TV update turned back on ": return AppStrings.get(R.string.runtime_the_tv_update_turned_back_on);
            case "Turned off what was missing: ": return AppStrings.get(R.string.runtime_turned_off_what_was_missing);
            case "The TV was updated; tvOS is still on.": return AppStrings.get(R.string.runtime_the_tv_was_updated_tvos_is_still_on);
            case "Connecting…": return AppStrings.get(R.string.runtime_connecting);
            case "Accept “Allow USB debugging?” on the TV (tick “Always allow”).": return AppStrings.get(R.string.runtime_accept_allow_usb_debugging_on_the_tv_tick_always_allow);
            case "Language": return AppStrings.get(R.string.runtime_language);
            case "Temperature": return AppStrings.get(R.string.runtime_temperature);
            case "Theme": return AppStrings.get(R.string.runtime_theme);
            case "Automatic": return AppStrings.get(R.string.runtime_automatic);
            case "Light": return AppStrings.get(R.string.runtime_light);
            case "Dark": return AppStrings.get(R.string.runtime_dark);
            case "Weather widget": return AppStrings.get(R.string.runtime_weather_widget);
            // What TalkBack reads on the drawer's weather card (#47)
            case ". High ": return AppStrings.get(R.string.runtime_high);
            case ", low ": return AppStrings.get(R.string.runtime_low);
            case " Next hours: ": return AppStrings.get(R.string.runtime_next_hours);
            case " Sunrise ": return AppStrings.get(R.string.runtime_sunrise_2);
            case " Sunset ": return AppStrings.get(R.string.runtime_sunset_2);
            case "Quotes widget": return AppStrings.get(R.string.runtime_quotes_widget);
            case "On": return AppStrings.get(R.string.runtime_on);
            case "Off": return AppStrings.get(R.string.runtime_off);
            case "Shelf": return AppStrings.get(R.string.runtime_shelf);
            case "on the shelf, place ": return AppStrings.get(R.string.runtime_on_the_shelf_place);
            case "not on the shelf": return AppStrings.get(R.string.runtime_not_on_the_shelf);
            case "Remote Buttons": return AppStrings.get(R.string.runtime_remote_buttons);
            case "Netflix button": case "Netflix Button": return AppStrings.get(R.string.runtime_netflix_button);
            case "YouTube button": case "YouTube Button": return AppStrings.get(R.string.runtime_youtube_button);
            case "Prime Video button": case "Prime Video Button": return AppStrings.get(R.string.runtime_prime_video_button);
            case "TCL Channel button": case "TCL Channel Button": return AppStrings.get(R.string.runtime_tcl_channel_button);
            case "Nothing": return AppStrings.get(R.string.runtime_nothing);
            case "Not installed": return AppStrings.get(R.string.runtime_not_installed);
            case ", selected": return AppStrings.get(R.string.runtime_selected_2);
            case "OK or the arrows change the value.": return AppStrings.get(R.string.runtime_ok_or_the_arrows_change_the_value);
            case "OK picks, Back cancels.": return AppStrings.get(R.string.runtime_ok_picks_back_cancels);
            case "Disney and Globoplay: the TV itself controls those buttons.": return AppStrings.get(R.string.runtime_disney_and_globoplay_the_tv_itself_controls_those_buttons);
            case "These are the TCL remote's buttons: on this TV, the buttons work as they always did.": return AppStrings.get(R.string.runtime_these_are_the_tcl_remote_s_buttons_on_this_tv_the_buttons_work_as);
            case "Explore": return AppStrings.get(R.string.runtime_explore);
            case "Movies": return AppStrings.get(R.string.runtime_movies);
            case "Shows": return AppStrings.get(R.string.runtime_shows);
            case "Clear": return AppStrings.get(R.string.runtime_clear);
            case "Mostly Sunny": return AppStrings.get(R.string.runtime_mostly_sunny);
            case "Mostly Clear": return AppStrings.get(R.string.runtime_mostly_clear);
            case "Partly Cloudy": return AppStrings.get(R.string.runtime_partly_cloudy);
            case "Cloudy": return AppStrings.get(R.string.runtime_cloudy);
            case "Fog": return AppStrings.get(R.string.runtime_fog);
            case "Drizzle": return AppStrings.get(R.string.runtime_drizzle);
            case "Heavy Rain": return AppStrings.get(R.string.runtime_heavy_rain);
            case "Snow": return AppStrings.get(R.string.runtime_snow);
            case "Thunderstorms": return AppStrings.get(R.string.runtime_thunderstorms);
            case "Rain": return AppStrings.get(R.string.runtime_rain);
            case "On · ": return AppStrings.get(R.string.runtime_on_2);
            case "OK adds or removes · ← → change the order": return AppStrings.get(R.string.runtime_ok_adds_or_removes_change_the_order);
            case "Couldn't open TV Settings": return AppStrings.get(R.string.runtime_couldn_t_open_tv_settings);
            case "THIS MORNING": return AppStrings.get(R.string.runtime_this_morning);
            case "THIS AFTERNOON": return AppStrings.get(R.string.runtime_this_afternoon);
            case "TONIGHT": return AppStrings.get(R.string.runtime_tonight);
            case "Action & Adventure": return AppStrings.get(R.string.runtime_action_adventure);
            case "Sci-Fi & Fantasy": return AppStrings.get(R.string.runtime_sci_fi_fantasy);
            case "War & Politics": return AppStrings.get(R.string.runtime_war_politics);
            case "Kids": return AppStrings.get(R.string.runtime_kids);
            case "News": return AppStrings.get(R.string.runtime_news);
            case "Soap": return AppStrings.get(R.string.runtime_soap);
            case "Talk": return AppStrings.get(R.string.runtime_talk);
            case "": return AppStrings.get(R.string.runtime_legacy_fragment);
            case "tvOS's home screen ran into a problem.\nGoing back to the TV's original home screen…": return AppStrings.get(R.string.runtime_tvos_s_home_screen_ran_into_a_problem_ngoing_back_to_the_tv_s_ori);
            case "debugging is off on the TV (port 5555 closed)": return AppStrings.get(R.string.runtime_debugging_is_off_on_the_tv_port_5555_closed);
            case "debugging permission wasn't accepted on the TV": return AppStrings.get(R.string.runtime_debugging_permission_wasn_t_accepted_on_the_tv);
            case "the TV's ADB didn't respond": return AppStrings.get(R.string.runtime_the_tv_s_adb_didn_t_respond);
            case "the TV asked for TLS (wireless debugging); use USB debugging": return AppStrings.get(R.string.runtime_the_tv_asked_for_tls_wireless_debugging_use_usb_debugging);
            case "unexpected reply from ADB": return AppStrings.get(R.string.runtime_unexpected_reply_from_adb);
            case "the TV rejected the app's key": return AppStrings.get(R.string.runtime_the_tv_rejected_the_app_s_key);
            case "ADB refused the command": return AppStrings.get(R.string.runtime_adb_refused_the_command);
            case "invalid ADB message": return AppStrings.get(R.string.runtime_invalid_adb_message);
            case "ADB message too large": return AppStrings.get(R.string.runtime_adb_message_too_large);
            case "the TV's first setup saved the Wi-Fi, and turning it off would erase those networks. "
                    + "In Settings → Network & Internet, forget the network and connect again (networks out of range don't "
                    + "show there: Claude Code can remove them from your computer), then try once more.":
                return AppStrings.get(R.string.runtime_wi_fi)
                        + AppStrings.get(R.string.runtime_legacy_fragment_2)
                        + AppStrings.get(R.string.runtime_claude_code);
            default:
                // Notices are saved in the language in use when they were generated.
                String[] prefixes = {"Couldn't check tvOS: ", "Couldn't turn on tvOS: ",
                        "Couldn't go back to the original: ", "Original mode, but I couldn't turn back on: "};
                for (String p : prefixes) if (english.startsWith(p)) return text(p) + text(english.substring(p.length()));
                String shelfPrefix = "OK adds or removes (up to ";
                String shelfSuffix = "). ← → change the order.";
                if (english.startsWith(shelfPrefix) && english.endsWith(shelfSuffix)) {
                    String n = english.substring(shelfPrefix.length(), english.length() - shelfSuffix.length());
                    return AppStrings.get(R.string.runtime_ok) + n + AppStrings.get(R.string.runtime_legacy_fragment_3);
                }
                String prefix = "tvOS is on: new home screen and screensaver, ";
                if (english.startsWith(prefix) && english.endsWith(" apps turned off.")) {
                    String n = english.substring(prefix.length(), english.length() - " apps turned off.".length());
                    return AppStrings.get(R.string.runtime_tvos) + n + ".";
                }
                if (english.startsWith("On · ") && english.endsWith(" titles from the TV's apps, 3 new ones every hour.")) {
                    String n = english.substring(5, english.length() - " titles from the TV's apps, 3 new ones every hour.".length());
                    return AppStrings.get(R.string.runtime_legacy_fragment_4) + n + AppStrings.get(R.string.runtime_3);
                }
                if (english.endsWith(" min ago")) return english.substring(0, english.length() - 8) + AppStrings.get(R.string.runtime_legacy_fragment_5);
                if (english.endsWith(" h ago")) return english.substring(0, english.length() - 6) + AppStrings.get(R.string.runtime_legacy_fragment_6);
                if (english.endsWith(" days ago")) {
                    try { return count(Long.parseLong(english.substring(0, english.length() - 9)), AppStrings.get(R.string.runtime_legacy_fragment_7), AppStrings.get(R.string.runtime_legacy_fragment_8), AppStrings.get(R.string.runtime_legacy_fragment_9)) + AppStrings.get(R.string.runtime_legacy_fragment_10); }
                    catch (NumberFormatException ignored) { return english; }
                }
                return english;
        }
    }
}
