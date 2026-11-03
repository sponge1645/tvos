package io.tvos.catalog;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** ISO country identity and TMDB country filters, independent of Android preferences. */
public final class RecommendationRegion {
    private static final Set<String> COUNTRIES = new HashSet<>(Arrays.asList(Locale.getISOCountries()));
    private RecommendationRegion() { }
    public static String normalize(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return COUNTRIES.contains(value) ? value : "";
    }
    public static String resolve(String selected, String configured) {
        String country = normalize(selected);
        if (country.isEmpty()) country = normalize(configured);
        return country.isEmpty() ? "RU" : country;
    }
    public static String filter(String kind, String region) {
        String country = normalize(region);
        if (country.isEmpty()) return "";
        // Movie region filters release-country context; TV has no equivalent release-country parameter.
        return "movie".equals(kind) ? "&region=" + country
                : "&watch_region=" + country + "&with_watch_monetization_types=flatrate%7Cfree%7Cads%7Crent%7Cbuy";
    }
}
