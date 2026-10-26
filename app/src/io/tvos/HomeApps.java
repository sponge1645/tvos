package io.tvos;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What shows up on the home screen and in the "All Apps" sheet, and how to open each thing.
 *
 * On the home shelf, each app is its colored logo on a dark card: the official logo in assets/logos/<key>.png
 * (downloaded from Wikimedia Commons by tools/logos.py) or, without one, the logo lifted from the app's TV art (the
 * Android TV "banner"), saved to disk after the first time. In "All Apps", 16:9 cards with the whole TV art (or the
 * icon on a dark card). Shortcuts (live TV, inputs, All Apps, settings) are icon buttons.
 */
final class HomeApps {
    private HomeApps() {}

    static final int APP = 0, LIVE_TV = 1, INPUT = 2, ALL_APPS = 3, SETTINGS = 4, ANDROID_SETTINGS = 5;
    /** The "HDMI Inputs" button: opens the bottom drawer on the inputs (the home screen handles it). */
    static final int INPUTS = 7;
    /** Image size of each card (16:9; on screen it shows up to ~330 px wide). */
    static final int TILE_W = 384, TILE_H = 216;

    static final class Item {
        int kind;
        String key, label, pkg, inputId, number;
        String symbol;      // icon for the shortcuts and special cards
        Bitmap tile;        // 16:9 card (apps and specials)
        Bitmap logo;        // colored logo with no background (shelf); null = use the card
        Bitmap logoDark;    // version for a light background (YouTube's white icon), if any
        float logoInk;      // fraction of the logo with ink (thin logos are drawn larger, with the same "weight")
        float logoScale = 1; // size tweak by eye (compact logos need more)
        boolean connected = true;
    }

    /**
     * Apps on the home shelf, in this order, when installed (up to MAX_SHELF): key, name, package, brand color (for
     * its logo on glass) and how to lift the logo from the app's TV art: "tint" = a white logo takes the art's
     * background color (Prime Video, Globoplay); "play" = the white triangle inside the red button stays white
     * (YouTube); empty = the art's colors as they are. Last, a visual size tweak for the logo. Edit freely.
     */
    private static final String[][] SHELF = {
        {"netflix", "Netflix", "com.netflix.ninja", "E50914", "", "1"},
        {"youtube", "YouTube", "com.google.android.youtube.tv", "FF0033", "play", "1"},
        {"prime", "Prime Video", "com.amazon.amazonvideo.livingroom", "1A98FF", "tint", "0.96"},
        {"disney", "Disney+", "com.disney.disneyplus", "1838C8", "", "1"},
        {"max", "Max", "com.wbd.stream", "6C3BE0", "", "1.32"},
        {"appletv", "Apple TV", "com.apple.atve.androidtv.appletv", "B8BCC6", "", "1.1"},
        {"hulu", "Hulu", "com.hulu.livingroomplus", "1CE783", "", "1"},
        {"paramount", "Paramount+", "com.cbs.ott", "0064FF", "", "1"},
        {"peacock", "Peacock", "com.peacocktv.peacockandroid", "6E55DC", "", "1"},
        {"globoplay", "Globoplay", "com.globo.globotv", "FF5A1F", "tint", "1"},
        {"spotify", "Spotify", "com.spotify.tv.android", "1DB954", "", "1"},
    };
    static final int MAX_SHELF = 8;
    private static final String TV_SETTINGS_FAVORITE = "io.tvos.virtual.tvsettings";
    /** Live TV apps of some brands (the antenna's channels open through the standard Android request first). */
    static final String[] LIVE_TV_APPS = {"com.yandex.tv.live", "com.tcl.tv", "com.google.android.tv", "com.mediatek.wwtv.tvcenter", "com.sony.dtv.tvx"};
    /** Kept out of "All Apps" (only hidden, still installed). Add the ones your TV shows that you never use. */
    private static final Set<String> HIDDEN = new HashSet<>(java.util.Arrays.asList(
            "com.google.android.play.games",        // Google Play Games
            "com.tcl.t_solo",                       // TCL: T-Solo
            "com.tcl.waterfall.overseas",           // TCL Channel
            "com.tcl.dashboard",                    // TCL Home
            "com.tcl.ocean.instructions",           // TCL: User Guide
            "com.tcl.guard",                        // TCL: Safety Guard
            "com.homwee.factory",                   // Philco: factory menu
            "com.mstar.android.tv.disclaimercustomization",   // Philco: disclaimer
            "com.homwee.chtvmanager",               // Philco: TV Manager (cleaner)
            "com.homwee.multiscreenshare",          // Philco: Midiacast (Chromecast still works)
            "com.google.android.videos",            // Google Play Movies (discontinued)
            "com.disney.starplus",                  // Star+ (merged into Disney+)
            "com.tcl.exhibit"));                    // TCL: T-Exhibition


    /**
     * Home screen apps. `extract`: also lifts the logos that are not on disk yet (slow: background only); without it,
     * those apps keep their TV art until the next load.
     */
    static List<Item> shelf(Context c, boolean extract) {
        List<Item> items = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (String pkg : shelfPackages(c)) {
            if (items.size() >= MAX_SHELF) break;
            if (TV_SETTINGS_FAVORITE.equals(pkg)) {
                Item settings = shortcut(ANDROID_SETTINGS, "tv-settings", AppStrings.get(R.string.settings_tv_settings), "tv_settings");
                // Use the bundled settings.png artwork on the requested gray gradient.
                settings.logo = AppArtwork.ownLogoCached(c, "settings");
                if (settings.logo != null) {
                    settings.logoInk = AppArtwork.ink(settings.logo);
                    settings.logoScale = 2.0f;
                } else {
                    settings.tile = AppArtwork.tvSettingsFavoriteTile(c);
                }
                items.add(settings);
                continue;
            }
            Intent launch = launchIntent(pm, pkg);
            if (launch == null) continue;
            ResolveInfo ri = pm.resolveActivity(launch, 0);
            String[] d = known(pkg);
            Item it = new Item();
            it.kind = APP;
            it.pkg = pkg;
            if (d == null) {
                // An app picked in Settings > Shelf that isn't in SHELF: its own name and TV art.
                it.key = pkg;
                it.label = ri != null ? String.valueOf(ri.loadLabel(pm)) : pkg;
                AppBranding.Style brand = AppBranding.forPackage(pkg);
                if (brand != null) {
                    it.logo = "io.gh.reisxd.tizentube.cobalt".equals(pkg)
                            ? AppArtwork.shelfLogo(c, brand.logo, pkg, "play", extract) : AppArtwork.ownLogoCached(c, brand.logo);
                    if (it.logo != null) {
                        it.logoInk = AppArtwork.ink(it.logo);
                        it.logoScale = brand.logoScale;
                    } else it.tile = AppArtwork.appTile(c, null, pkg, 0);
                } else it.tile = AppArtwork.appTile(c, null, pkg, 0);
            } else {
                it.key = d[0];
                it.label = d[1];
                it.logo = AppArtwork.shelfLogo(c, d[0], d[2], d[4], extract);
                if (it.logo == null) it.tile = AppArtwork.appTile(c, d[0], d[2], 0xFF000000 | Integer.parseInt(d[3], 16));
                if (it.logo != null) it.logoInk = AppArtwork.ink(it.logo);
                it.logoDark = AppArtwork.ownLogoCached(c, d[0] + "-dark");
                it.logoScale = Float.parseFloat(d[5]);
            }
            items.add(it);
        }
        return items;
    }

    private static String[] known(String pkg) {
        for (String[] d : SHELF) if (d[2].equals(pkg)) return d;
        return null;
    }

    /** The shelf's packages in order: the user's list (Settings > Shelf), or the built-in one. */
    // tvOS unified Android Settings dock entry: migrate the old virtual favorite.
    static List<String> shelfPackages(Context c) {
        List<String> custom = Prefs.shelf();
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        if (custom != null) {
            for (String pkg : custom) {
                if (TV_SETTINGS_FAVORITE.equals(pkg)) pkg = "com.android.tv.settings";
                if (!out.contains(pkg)) out.add(pkg);
            }
        } else {
            for (String[] d : SHELF) {
                if (out.size() >= MAX_SHELF - 1) break;
                if (launchIntent(pm, d[2]) != null) out.add(d[2]);
            }
            out.add("com.android.tv.settings");
        }
        return out;
    }

    /** Every app the shelf could hold, as {package, name}: the ones from SHELF first, then the other TV apps by name. */
    static List<String[]> shelfCandidates(Context c) {
        PackageManager pm = c.getPackageManager();
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] d : SHELF) if (launchIntent(pm, d[2]) != null && seen.add(d[2])) out.add(new String[] {d[2], d[1]});
        List<String[]> more = new ArrayList<>();
        for (ResolveInfo ri : launcherActivities(pm)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || HIDDEN.contains(pkg) || !seen.add(pkg)) continue;
            more.add(new String[] {pkg, String.valueOf(ri.loadLabel(pm))});
        }
        Collections.sort(more, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
        out.addAll(more);
        return out;
    }

    /** Include both TV and regular Android launchers, preferring the TV activity. */
    private static List<ResolveInfo> launcherActivities(PackageManager pm) {
        List<ResolveInfo> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String category : new String[] {
                Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                if (ri.activityInfo == null || !ri.activityInfo.enabled) continue;
                if (seen.add(ri.activityInfo.packageName)) out.add(ri);
            }
        }
        return out;
    }

    /** Live TV, the HDMI inputs with a device sending a signal, and All Apps. */
    static List<Item> shortcuts(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) {
            items.add(shortcut(LIVE_TV, "live", AppStrings.get(R.string.runtime_live_tv), "tv"));
        }
        items.addAll(inputs(c));
        items.add(shortcut(ALL_APPS, "all", AppStrings.get(R.string.runtime_all_apps), "apps"));
        return items;
    }

    private static Item shortcut(int kind, String key, String label, String symbol) {
        Item it = new Item();
        it.kind = kind;
        it.key = key;
        it.label = label;
        it.symbol = symbol;
        return it;
    }

    /** Icon button (the ones in the corner of the home screen). */
    static Item action(int kind, String key, String label, String symbol) {
        return shortcut(kind, key, label, symbol);
    }

    /** tvOS settings, in the corner of the home screen. */
    static Item settings() {
        return shortcut(SETTINGS, "settings", AppStrings.get(R.string.runtime_settings), "settings");
    }

    /** Shortcuts in the bottom drawer. */
    static List<Item> drawerShortcuts() {
        List<Item> items = new ArrayList<>();
        items.add(shortcut(ALL_APPS, "all", AppStrings.get(R.string.runtime_all_apps), "apps"));
        items.add(shortcut(SETTINGS, "settings", AppStrings.get(R.string.runtime_settings), "settings"));
        items.add(shortcut(ANDROID_SETTINGS, "tv-settings", AppStrings.get(R.string.runtime_tv_settings), "tv_settings"));
        return items;
    }

    /** A streaming service's app (the "Watch" button of the featured pick). */
    static Item app(String pkg, String label) {
        Item it = new Item();
        it.kind = APP;
        it.key = pkg;
        it.pkg = pkg;
        it.label = label;
        return it;
    }

    /** For the bottom drawer: live TV and every HDMI input (the ones with no device are shown dimmed). */
    static List<Item> allInputs(Context c) {
        List<Item> items = new ArrayList<>();
        if (liveTv(c) != null) items.add(shortcut(LIVE_TV, "live", AppStrings.get(R.string.runtime_live_tv), "tv"));
        items.addAll(inputs(c, true));
        return items;
    }

    private static List<Item> inputs(Context c) {
        return inputs(c, false);
    }

    /**
     * HDMI inputs: only the ones with a device sending a signal (or all of them, with `all`). A name you gave the input
     * in the TV's settings is used as the label; without one, the name the device reports over HDMI-CEC ("PlayStation 5").
     * The icon follows what that name says the device is (see deviceSymbol).
     */
    private static List<Item> inputs(Context c, boolean all) {
        List<Item> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return out;
        List<TvInputInfo> list = new ArrayList<>();
        // A device that talks HDMI-CEC gets its own input, a child of the port's, named after the device: port input id
        // -> that name.
        Map<String, String> devices = new HashMap<>();
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_HDMI) continue;
                if (info.getParentId() == null) {
                    list.add(info);
                } else {
                    devices.put(info.getParentId(), String.valueOf(info.loadLabel(c)).trim());
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        Collections.sort(list, (a, b) -> a.getId().compareTo(b.getId()));
        Pattern digit = Pattern.compile("(\\d)");
        for (int i = 0; i < list.size(); i++) {
            TvInputInfo info = list.get(i);
            int number = i + 1;
            Matcher m = digit.matcher(String.valueOf(info.loadLabel(c)));
            if (m.find()) number = Integer.parseInt(m.group(1));
            // TCL TVs report "connected standby" for every port, with or without a device; only truly "connected"
            // (a device that is on and sending a signal) makes the input show up.
            int state = TvInputManager.INPUT_STATE_DISCONNECTED;
            try {
                state = tim.getInputState(info.getId());
            } catch (RuntimeException ignored) {
            }
            if (!all && state != TvInputManager.INPUT_STATE_CONNECTED) continue;
            CharSequence custom = null;
            try {
                custom = info.loadCustomLabel(c);
            } catch (RuntimeException ignored) {
            }
            String reported = devices.containsKey(info.getId()) ? devices.get(info.getId()) : "";
            String label = custom != null && custom.length() > 0 ? custom.toString()
                    : !reported.isEmpty() && state == TvInputManager.INPUT_STATE_CONNECTED ? reported : "HDMI " + number;
            String symbol = state == TvInputManager.INPUT_STATE_CONNECTED || custom != null
                    ? deviceSymbol(label) : "hdmi";
            Item it = shortcut(INPUT, "hdmi" + number, label, symbol);
            it.inputId = info.getId();
            it.number = String.valueOf(number);
            it.connected = state == TvInputManager.INPUT_STATE_CONNECTED;
            out.add(it);
        }
        Collections.sort(out, (a, b) -> a.number.compareTo(b.number));
        return out;
    }

    /** Words in a device's name, by the icon they pick: a game console, a streaming box, a sound system, a TV box. */
    private static final String[][] DEVICE_WORDS = {
        {"gamepad", "playstation", "ps5", "ps4", "ps3", "xbox", "nintendo", "switch", "steam deck", "console", "game"},
        {"play", "apple tv", "chromecast", "fire tv", "firetv", "roku", "shield", "mi box", "tv box", "tvbox", "stick", "streaming", "google tv", "android tv"},
        {"speaker", "soundbar", "sound bar", "receiver", "denon", "yamaha", "marantz", "onkyo", "bose", "sonos", "jbl", "audio", "home theater"},
        {"tv", "cable", "set-top", "decoder", "decodificador", "tuner", "sky", "claro", "vivo"},
    };

    /** The icon for an HDMI device, from the words in its name; a name that says nothing (or a plain port) is the HDMI plug. */
    static String deviceSymbol(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String[] group : DEVICE_WORDS) {
            for (int i = 1; i < group.length; i++) if (n.contains(group[i])) return group[0];
        }
        return "hdmi";
    }

    /** All enabled launchable apps, including apps that also appear in Dock or Sources. */
    static List<Item> others(Context c) {
        PackageManager pm = c.getPackageManager();
        String settingsPkg = settingsPackage(pm);
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(c.getPackageName());
        seen.addAll(HIDDEN);
        // A single system action works even when Settings has no launcher activity.
        if (settingsPkg != null) seen.add(settingsPkg);
        for (ResolveInfo ri : launcherActivities(pm)) {
            String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg)) continue;
            Item it = new Item();
            it.kind = APP;
            it.pkg = it.key = pkg;
            it.label = String.valueOf(ri.loadLabel(pm));
            it.tile = AppArtwork.brandedAllAppsTile(c, pkg);
            items.add(it);
        }
        // Some TV firmwares omit known apps from launcher queries, but resolve their package launch intent.
        for (String[] app : SHELF) addAllAppsPackage(c, pm, app[2], seen, items);
        for (String pkg : new String[] {"com.google.android.youtube", "com.android.vending",
                "com.seena.app", "watch.kino", "watch.kino.mod", "top.rootu.lampa", "io.gh.reisxd.tizentube.cobalt",
                "llc.itdev.incy", "io.github.jqssun.airplay", "com.applemusicktv"})
            addAllAppsPackage(c, pm, pkg, seen, items);
        if (settingsPkg != null) {
            Item settings = shortcut(ANDROID_SETTINGS, "tv-settings",
                    AppStrings.get(R.string.runtime_tv_settings), "tv_settings");
            settings.pkg = settingsPkg;
            settings.tile = AppArtwork.tvSettingsFavoriteTile(c);
            items.add(settings);
        }
        Collections.sort(items, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        AppArtwork.pruneTiles(seen);
        return items;
    }

    private static String settingsPackage(PackageManager pm) {
        try {
            ResolveInfo ri = pm.resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0);
            return ri != null && ri.activityInfo != null && ri.activityInfo.enabled
                    ? ri.activityInfo.packageName : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private static void addAllAppsPackage(Context c, PackageManager pm, String pkg,
                                           Set<String> seen, List<Item> items) {
        if (seen.contains(pkg)) return;
        try {
            ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
            if (!app.enabled || launchIntent(pm, pkg) == null) return;
            Item it = new Item();
            it.kind = APP;
            it.pkg = it.key = pkg;
            it.label = String.valueOf(pm.getApplicationLabel(app));
            it.tile = AppArtwork.brandedAllAppsTile(c, pkg);
            items.add(it);
            seen.add(pkg);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {}
    }

    private static Item special(Context c, int kind, String key, String label, String symbol) {
        Item it = shortcut(kind, key, label, symbol);
        it.tile = AppArtwork.symbolTile(c, key, symbol);
        return it;
    }

    /** Opens the item with the system zoom transition growing out of the card (the app starts opening right away). */
    static boolean open(Activity a, Item it, View from, int x, int y, int w, int h) {
        try {
            Intent intent;
            switch (it.kind) {
                case APP:
                    intent = launchIntent(a.getPackageManager(), it.pkg);
                    if (intent != null) intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    break;
                case LIVE_TV:
                    intent = liveTv(a);
                    break;
                case INPUT:
                    Intent yaosHdmi = new Intent(
                        Intent.ACTION_VIEW,
                        android.net.Uri.parse("live-tv://android.media.tv/passthrough/" +
                            android.net.Uri.encode(it.inputId))
                    );
                    yaosHdmi.setPackage("com.yandex.tv.live");
                    if (yaosHdmi.resolveActivity(a.getPackageManager()) != null) {
                        intent = yaosHdmi;
                    } else {
                        intent = new Intent(
                            Intent.ACTION_VIEW,
                            TvContract.buildChannelUriForPassthroughInput(it.inputId)
                        );
                    }
                    break;
                case SETTINGS:
                    intent = new Intent().setComponent(new ComponentName(a, SettingsActivity.class));
                    break;
                case ANDROID_SETTINGS:
                    intent = new Intent(Settings.ACTION_SETTINGS);
                    break;
                default:
                    return false;
            }
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return AppLaunch.start(a, intent,
                    ActivityOptions.makeScaleUpAnimation(from, x, y, Math.max(w, 1), Math.max(h, 1)).toBundle(),
                    "App item launch");
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("App item preparation: " + e.getClass().getSimpleName());
            return false;
        } catch (RuntimeException e) {
            AppLog.unexpected("App item preparation", e);
            return false;
        }
    }

    /**
     * Live TV: tune the antenna input directly. MediaTek TV Center otherwise restores the last source used, which can
     * be HDMI, so its explicit activity receives the tuner URI. Other TV apps keep the standard channel-list request.
     */
    static Intent liveTv(Context c) {
        PackageManager pm = c.getPackageManager();

        // Ya OS: штатное открытие телеканалов.
        Intent yaos = new Intent("yandex.intent.action.TV_CHANNEL");
        yaos.setPackage("com.yandex.tv.live");
        if (yaos.resolveActivity(pm) != null) return yaos;



        String tuner = tunerInput(c);
        Intent mediaTek = launchIntent(pm, "com.mediatek.wwtv.tvcenter");
        if (mediaTek != null && tuner != null) {
            return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(tuner))
                    .setComponent(mediaTek.getComponent());
        }
        if (tuner != null) {
            Intent channels = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(tuner));
            if (channels.resolveActivity(pm) != null) return channels;
        }
        if (mediaTek != null) return mediaTek;
        for (String pkg : LIVE_TV_APPS) {
            Intent app = launchIntent(pm, pkg);
            if (app != null) return app;
        }
        return null;
    }

    /**
     * The TV's antenna (tuner) input, or null. The TV maker's hardware tuner comes first: system apps also declare
     * tuner inputs (TCL: AirPlay), and Android names the hardware ones "…/HW<id>".
     */
    static String tunerInput(Context c) {
        TvInputManager tim = (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        if (tim == null) return null;
        String system = null, fallback = null;
        try {
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.getType() != TvInputInfo.TYPE_TUNER || info.isPassthroughInput()) continue;
                if (info.getServiceInfo() != null && info.getServiceInfo().applicationInfo != null
                        && (info.getServiceInfo().applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    if (info.getId().contains("/HW")) return info.getId();
                    if (system == null) system = info.getId();
                }
                if (fallback == null) fallback = info.getId();
            }
        } catch (RuntimeException ignored) {
        }
        return system != null ? system : fallback;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        return AppLaunch.launchIntent(pm, pkg);
    }

}
