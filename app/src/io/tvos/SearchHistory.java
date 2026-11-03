package io.tvos;

import java.util.ArrayList;
import java.util.List;

/** Bounded, newest-first history; case variants of the same query share one entry. */
final class SearchHistory {
    private SearchHistory() { }
    static List<String> remember(List<String> previous, String query) {
        List<String> result = new ArrayList<>();
        String value = query.trim();
        if (!value.isEmpty()) result.add(value);
        for (String old : previous) {
            String entry = old.trim();
            if (entry.isEmpty()) continue;
            boolean duplicate = false;
            for (String existing : result) if (existing.equalsIgnoreCase(entry)) { duplicate = true; break; }
            if (!duplicate && result.size() < 8) result.add(entry);
        }
        return result;
    }
}
