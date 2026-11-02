package io.tvos.catalog;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Read-only selection policy; returned lists retain the caller's original item instances. */
public final class RecommendationSelection<T> {
    private final Function<T, String> kind, title;
    private final LongSupplier clock;

    public RecommendationSelection(Function<T, String> kind, Function<T, String> title, LongSupplier clock) {
        this.kind = kind;
        this.title = title;
        this.clock = clock;
    }

    private List<T> matching(List<T> source, String requestedKind, int limit) {
        List<T> out = new ArrayList<>();
        for (T item : source) {
            if (requestedKind == null || requestedKind.equals(kind.apply(item))) {
                out.add(item);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    public List<T> now(List<T> source, String requestedKind) {
        List<T> list = matching(source, requestedKind, Integer.MAX_VALUE);
        if (list.isEmpty()) return list;
        long hour = clock.getAsLong() / 3600_000L;
        List<T> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) {
            T item = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(title.apply(item)))) out.add(item);
        }
        return out;
    }

    public List<T> catalog(List<T> source, String requestedKind) {
        return matching(source, requestedKind, 25);
    }

    public static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    public static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? 0 : hour >= 12 && hour < 18 ? 1 : 2;
    }
}
