package io.tvos.setup;

/** First-run decisions and stable wizard steps; independent of Android storage/UI. */
public final class SetupState {
    private SetupState() { }
    public static final int HELLO = 0, LANGUAGE = 1, REGION = 2, WIFI = 3, UPDATES = 4, TV = 5, HOME = 6;
    public static boolean needed(boolean complete, boolean started, boolean upgrade, boolean configured) {
        return !complete && (started || !upgrade || !configured);
    }
    public static int step(int value) { return Math.max(HELLO, Math.min(HOME, value)); }
    public static String language(String system) { return system != null && system.toLowerCase(java.util.Locale.ROOT).startsWith("ru") ? "ru" : "en"; }
}
