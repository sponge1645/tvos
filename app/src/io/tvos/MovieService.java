package io.tvos;

import java.util.function.Predicate;

/** Persisted provider identity; shared by settings and poster launch routes. */
final class MovieService {
    private MovieService() { }
    static final String KEY = "movie_service";
    private static final String[] IDS = {"", "kinopub", "lampa", "seena", "vk"};
    static int count() { return IDS.length; }
    static String id(int index) { return index >= 0 && index < IDS.length ? IDS[index] : ""; }
    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 0;
    }
    static String label(int index, AppLanguage language) {
        switch (index) {
            case 1: return "KinoPub";
            case 2: return "Lampa";
            case 3: return "Seena";
            case 4: return "VK";
            default: return AppStrings.get(R.string.settings_ask_every_time, language);
        }
    }
    static String[] labels(AppLanguage language) {
        String[] labels = new String[count()];
        for (int i = 0; i < labels.length; i++) labels[i] = label(i, language);
        return labels;
    }
    static String resolve(String service, Predicate<String> available) {
        String[] packages;
        switch (service) {
            case "kinopub": packages = new String[]{"watch.kino", "watch.kino.mod"}; break;
            case "lampa": packages = new String[]{"top.rootu.lampa"}; break;
            case "seena": packages = new String[]{"com.seena.app"}; break;
            case "vk": packages = new String[]{"com.vk.tv"}; break;
            default: return "";
        }
        for (String pkg : packages) if (available.test(pkg)) return pkg;
        return "";
    }
}
