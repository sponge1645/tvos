package io.tvos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Source identities stay separate from TMDB; one latest entry per KinoPub title. */
final class WatchingItems {
    private WatchingItems() {}

    static List<Recs.Pick> latest(List<Recs.Pick> entries) {
        List<Recs.Pick> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong((Recs.Pick p) -> p.watchingUpdated).reversed()
                .thenComparing(p -> p.watchingKey));
        Set<String> seen = new HashSet<>();
        List<Recs.Pick> result = new ArrayList<>();
        for (Recs.Pick pick : sorted) {
            if (seen.add(pick.watchingKey)) result.add(pick);
            if (result.size() == 10) break;
        }
        return result;
    }

    static long remaining(long duration, long position) {
        if (duration <= 0) return -1;
        long millis = duration - Math.min(duration, Math.max(0, position));
        return millis / 60_000 + (millis % 60_000 == 0 ? 0 : 1);
    }
}
