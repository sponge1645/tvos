package io.tvos;

import io.tvos.watching.WatchingSources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Source identities stay separate from TMDB; latest entries with a separate limit for each service. */
final class WatchingItems {
    private WatchingItems() {}

    static List<Recs.Pick> latest(List<Recs.Pick> entries) {
        return latest(entries, "all");
    }

    static List<Recs.Pick> latest(List<Recs.Pick> entries, String source) {
        List<Recs.Pick> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong((Recs.Pick p) -> p.watchingUpdated).reversed()
                .thenComparing(p -> p.watchingKey));
        Set<String> seen = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        List<Recs.Pick> result = new ArrayList<>();
        for (Recs.Pick pick : sorted) {
            String service = WatchingSources.id(pick.pkg);
            if (!"all".equals(source) && !service.equals(source)) continue;
            int count = counts.containsKey(service) ? counts.get(service) : 0;
            if (count < 10 && seen.add(service + ":" + pick.watchingKey)) {
                result.add(pick);
                counts.put(service, count + 1);
            }
        }
        return result;
    }

    static String trace(List<Recs.Pick> picks) {
        int fingerprint = 1;
        for (Recs.Pick pick : picks) fingerprint = 31 * fingerprint + java.util.Objects.hash(
                pick.pkg, pick.watchingKey, pick.title, pick.watchingUpdated,
                pick.watchingRemaining, pick.watchingSeason, pick.watchingEpisode);
        return "cards=" + picks.size() + ", fingerprint=" + Integer.toHexString(fingerprint);
    }

    static long remaining(long duration, long position) {
        if (duration <= 0) return -1;
        long millis = duration - Math.min(duration, Math.max(0, position));
        return millis / 60_000 + (millis % 60_000 == 0 ? 0 : 1);
    }
}
