package io.tvos.setup;

/** First-run decisions and stable wizard steps; independent of Android storage/UI. */
public final class SetupState {
    private SetupState() { }
    public static final int HELLO = 0, LANGUAGE = 1, REGION = 2, WIFI = 3, UPDATES = 4, TV = 5, HOME = 6, WEATHER = 7;
    public static boolean needed(boolean complete, boolean started, boolean upgrade, boolean configured) {
        return !complete && (started || !upgrade || !configured);
    }
    public static int step(int value) { int step = value == WEATHER ? WEATHER : Math.max(HELLO, Math.min(HOME, value)); return step == UPDATES ? TV : step; }
    public static int next(int value) { return value == WIFI ? WEATHER : value == WEATHER ? TV : step(value + 1); }
    public static int previous(int value) { return value == TV ? WEATHER : value == WEATHER ? WIFI : Math.max(HELLO, value - 1); }
    public static boolean backgroundTransition(int from, int to) {
        return from >= HELLO && from <= REGION && to >= HELLO && to <= REGION && from != to;
    }
    public static String language(String system) { return system != null && system.toLowerCase(java.util.Locale.ROOT).startsWith("ru") ? "ru" : "en"; }
}
