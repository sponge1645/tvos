package io.tvos;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.SparseArray;
import java.util.EnumMap;

/** App-selected language, independent of Android's language; cached text never retains an Activity. */
final class AppStrings {
    private AppStrings() { }
    private static Context application;
    private static final EnumMap<AppLanguage, Resources> resources = new EnumMap<>(AppLanguage.class);
    private static final EnumMap<AppLanguage, SparseArray<String>> strings = new EnumMap<>(AppLanguage.class);

    static synchronized void init(Context context) {
        if (application == null) application = context.getApplicationContext();
    }

    static String get(int id) { return get(id, AppLanguage.current()); }

    static synchronized String get(int id, AppLanguage language) {
        SparseArray<String> cache = strings.get(language);
        if (cache == null) {
            cache = new SparseArray<>();
            strings.put(language, cache);
        }
        String value = cache.get(id);
        if (value != null) return value;
        value = localizedResources(language).getString(id);
        cache.put(id, value);
        return value;
    }

    static String quantity(int id, int count) { return quantity(id, AppLanguage.current(), count); }

    /** The plural template is selected by Android; callers format it with the app locale. */
    static synchronized String quantity(int id, AppLanguage language, int count) {
        return localizedResources(language).getQuantityString(id, count);
    }

    // Called under the class monitor by get/quantity; never creates a context per frame.
    private static Resources localizedResources(AppLanguage language) {
        if (application == null) throw new IllegalStateException("AppStrings not initialized");
        Resources value = resources.get(language);
        if (value == null) {
            Configuration config = new Configuration(application.getResources().getConfiguration());
            config.setLocale(language.locale);
            value = application.createConfigurationContext(config).getResources();
            resources.put(language, value);
        }
        return value;
    }
}
