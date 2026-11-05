package io.tvos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Data and saved selection for the settings picker; loading, scrolling and drawing belong to the view. */
final class SettingsPicker {
    final List<String> labels = new ArrayList<>();
    final List<String> keys = new ArrayList<>();
    int kind, current = -1;

    private void begin(int which) {
        kind = which; current = -1;
        labels.clear(); keys.clear();
    }

    void countries(int which, String[] codes, String selected, Function<String, String> name) {
        begin(which);
        for (String code : codes) {
            if (code.equals(selected)) current = keys.size();
            keys.add(code); labels.add(name.apply(code));
        }
    }

    void tvProviders(int which, List<String[]> found, String selected, String automatic) {
        begin(which);
        keys.add("automatic"); labels.add(automatic);
        for (String[] target : found) { keys.add(target[0]); labels.add(target[1]); }
        current = keys.indexOf(selected.isEmpty() ? "automatic" : selected);
    }

    void cities(int which, List<String[]> found) {
        begin(which);
        for (String[] city : found) labels.add(city[0]);
    }

    void applications(int which, List<String> packages, List<String> names, String selected, String unconfigured) {
        begin(which);
        labels.addAll(names);
        if (!labels.isEmpty()) labels.set(0, unconfigured);
        current = packages.indexOf(selected);
    }
}
