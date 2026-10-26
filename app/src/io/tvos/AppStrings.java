package io.tvos;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.SparseArray;
import java.util.Locale;

/** App-selected language, independent of Android's language; cached text never retains an Activity. */
final class AppStrings {
    private AppStrings() { }
    private static Context application;
    private static final Resources[] resources = new Resources[2];
    private static final SparseArray<String> english = new SparseArray<>(), russian = new SparseArray<>();

    static synchronized void init(Context context) {
        if (application == null) application = context.getApplicationContext();
    }

    static synchronized String get(int id, boolean ru) {
        SparseArray<String> cache = ru ? russian : english;
        String value = cache.get(id);
        if (value != null) return value;
        if (application == null) throw new IllegalStateException("AppStrings not initialized");
        int index = ru ? 1 : 0;
        if (resources[index] == null) {
            Configuration config = new Configuration(application.getResources().getConfiguration());
            config.setLocale(ru ? new Locale("ru", "RU") : Locale.US);
            resources[index] = application.createConfigurationContext(config).getResources();
        }
        value = resources[index].getString(id);
        cache.put(id, value);
        return value;
    }
}
