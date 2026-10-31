package io.tvos.watching;

/** Aliases share one history quota; unknown packages remain independent Watch Next sources. */
public final class WatchingSources {
    private WatchingSources() {}

    public static String id(String pkg) {
        if ("watch.kino".equals(pkg) || "watch.kino.mod".equals(pkg)) return "kinopub";
        if ("com.seena.app".equals(pkg)) return "seena";
        if ("top.rootu.lampa".equals(pkg)) return "lampa";
        return pkg == null ? "" : pkg;
    }

    public static String knownLabel(String id) {
        switch (id) {
            case "kinopub": return "KinoPub";
            case "seena": return "Seena";
            case "lampa": return "Lampa";
            default: return "";
        }
    }
}
