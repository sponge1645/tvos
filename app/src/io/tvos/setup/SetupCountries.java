package io.tvos.setup;
import java.text.Collator;
import java.util.Arrays;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
/** ISO country list, localized and sorted once when opening the picker. */
public final class SetupCountries {
    private SetupCountries() { }
    public static boolean valid(String code) {
        if (code == null) return false;
        return Arrays.asList(Locale.getISOCountries()).contains(code);
    }
    public static String name(String code, Locale language) {
        return Locale.forLanguageTag("und-" + code).getDisplayCountry(language);
    }
    public static String[] codes(Locale language) {
        String[] codes = Locale.getISOCountries();
        Collator order = Collator.getInstance(language);
        Map<String, String> names = new HashMap<>();
        for (String code : codes) names.put(code, name(code, language));
        Arrays.sort(codes, (a, b) -> order.compare(names.get(a), names.get(b)));
        return codes;
    }
}
