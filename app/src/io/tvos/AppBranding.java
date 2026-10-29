package io.tvos;

import java.util.HashMap;
import java.util.Map;

/** Presentation only. Package aliases here never rewrite launch targets, preferences or APK trust. */
final class AppBranding {
    private AppBranding() {}

    static final class Style {
        final int top, bottom, mode;
        final String logo;
        final float logoScale, dockBoost, allAppsBoost;
        Style(int top, int bottom, int mode, String logo, float logoScale, float dockBoost, float allAppsBoost) {
            this.top = top; this.bottom = bottom; this.mode = mode; this.logo = logo;
            this.logoScale = logoScale; this.dockBoost = dockBoost; this.allAppsBoost = allAppsBoost;
        }
    }

    private static final Map<String, Style> STYLES = new HashMap<>();
    static {
        add("com.android.vending", 0xFF52ABEF, 0xFF2D6AD4, "googleplay", 1f, 1f, 1.2f);
        add("com.yandex.devices.appstore", 0xFF52ABEF, 0xFF2D6AD4, "googleplay", 1f, 1f, 1.2f);
        add("netflix", 0xFF0A0A0A, 0xFF000000, 0);
        add("youtube", 0xFF1C1C1E, 0xFF09090A, 0);
        add("io.gh.reisxd.tizentube.cobalt", 0xFFFFFFFF, 0xFFFFFFFF, "tizentube", 1f, 1.05f, 1f);
        add("watch.kino", 0xFFFFFFFF, 0xFFFFFFFF, "kinopub", 1f, 1.32f, 1.32f);
        add("com.seena.app", 0xFF007AFF, 0xFF007AFF, "seena", 1f, 1f, 1f);
        add("top.rootu.lampa", 0xFF313130, 0xFF161613, "lampa", 1f, 1f, 1f);
        add("tv-settings", 0xFFA5A6AB, 0xFF656368, "settings", 2f, 1.6632f, 1f);
        add("com.applemusicktv", 0xFFEB576D, 0xFFEA333C, "music", 1f, 1f, 1.25f);
        add("com.android.tv.settings", 0xFFA5A6AB, 0xFF656368, "settings", 2.4f, 1f, 1f);
        add("io.github.jqssun.airplay", 0xFFF79E1F, 0xFFFF6933, "airplay", 1f, 1f, 1f);
        add("llc.itdev.incy", 0xFF024824, 0xFF0B1B11, "incy", 1f, 1f, 1f);
        add("globoplay", 0xFFFF8A1F, 0xFFFF2D5A, 1);
        add("prime", 0xFF2AA8FF, 0xFF0A68D4, 1);
        add("disney", 0xFF173584, 0xFF08143F, 0);
        add("max", 0xFF5C30E8, 0xFF221684, 0);
    }

    private static void add(String key, int top, int bottom, int mode) {
        STYLES.put(key, new Style(top, bottom, mode, null, 1f, 1f, 1f));
    }

    private static void add(String key, int top, int bottom, String logo, float logoScale, float dockBoost, float allAppsBoost) {
        STYLES.put(key, new Style(top, bottom, 0, logo, logoScale, dockBoost, allAppsBoost));
    }

    static Style get(String key) {
        return STYLES.get("watch.kino.mod".equals(key) ? "watch.kino" : key);
    }

    static Style forPackage(String pkg) {
        Style style = get(pkg);
        return style != null && style.logo != null ? style : null;
    }

    static Style forAllApps(String pkg) {
        // System Settings keeps its existing dedicated system-action card.
        if ("com.android.tv.settings".equals(pkg) || "tv-settings".equals(pkg)) return null;
        return forPackage(pkg);
    }

    static float dockBoost(String pkg, String key) {
        if ("com.android.vending".equals(pkg) || "com.yandex.devices.appstore".equals(pkg)) return 1.2f;
        if ("com.android.tv.settings".equals(pkg)) return 1.4553f;
        if ("com.applemusicktv".equals(pkg)) return 1.25f;
        Style style = get(key);
        return style == null ? 1f : style.dockBoost;
    }
}
