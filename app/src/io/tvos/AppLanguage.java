package io.tvos;

import java.util.Locale;

/** Single catalog and runtime selection. Prefs owns persistence; Ui keeps transitional aliases only. */
enum AppLanguage {
    RUSSIAN("ru", "Русский", new Locale("ru", "RU"), "EEEE, d MMMM", "HH:mm"),
    ENGLISH("en", "English", Locale.US, "EEEE, MMMM d", "h a");

    final String code, nativeName, datePattern, hourPattern, apiTag;
    final Locale locale;
    private static final AppLanguage[] ORDER = values();
    private static volatile AppLanguage selected = ENGLISH;

    AppLanguage(String code, String nativeName, Locale locale, String datePattern, String hourPattern) {
        this.code = code;
        this.nativeName = nativeName;
        this.locale = locale;
        this.datePattern = datePattern;
        this.hourPattern = hourPattern;
        this.apiTag = locale.toLanguageTag();
    }

    static AppLanguage current() { return selected; }

    static AppLanguage select(String code) {
        AppLanguage language = from(code);
        selected = language;
        return language;
    }

    /** Preserve normalization of old saved choices; unsupported codes fall back to English. */
    static AppLanguage from(String code) {
        String value = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        for (AppLanguage language : ORDER) if (value.startsWith(language.code)) return language;
        return ENGLISH;
    }

    static AppLanguage step(String code, int direction) {
        int offset = Math.floorMod(direction, ORDER.length);
        return ORDER[(from(code).ordinal() + offset) % ORDER.length];
    }

    static int count() { return ORDER.length; }

    /** Preferred language first, then every other supported language; caller owns the returned array. */
    static String[] searchOrder(String code) {
        AppLanguage preferred = from(code);
        String[] codes = new String[ORDER.length];
        codes[0] = preferred.code;
        int at = 1;
        for (AppLanguage language : ORDER) if (language != preferred) codes[at++] = language.code;
        return codes;
    }

    /** Resolves known external/older messages and genre names; translated copy is stored in XML. */
    String legacyText(String english) { return this == RUSSIAN ? Russian.text(english) : english; }
}
