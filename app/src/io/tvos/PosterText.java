package io.tvos;

import java.util.Locale;
import java.util.Objects;
import java.util.WeakHashMap;

/** UI-thread cache of poster text, independent of geometry, focus and device language. */
final class PosterText {
    static final int MAX_ENTRIES = 128;
    // Values must never retain their Pick key: the catalog owns each title's lifetime.
    private final WeakHashMap<Recs.Pick, Entry> entries = new WeakHashMap<>();

    Entry get(Recs.Pick pick) {
        Entry found = entries.get(pick);
        if (found != null && found.matches(pick)) return found;
        // Also bound the cache when the catalog itself keeps many Pick objects alive.
        if (entries.size() >= MAX_ENTRIES) entries.clear();
        Entry next = new Entry(pick);
        entries.put(pick, next);
        return next;
    }

    void clear() { entries.clear(); }

    static final class Entry {
        private final String kind, movieOrigin, tvOrigin, duration, meta;
        private final long ratingBits;
        private final String detail, fallback;
        final String rating;
        final int badgeColor;

        Entry(Recs.Pick pick) {
            kind = pick.kind;
            movieOrigin = pick.movieOrigin;
            tvOrigin = pick.tvOrigin;
            duration = pick.duration;
            meta = pick.meta;
            double value = pick.rating;
            ratingBits = Double.doubleToLongBits(value);
            String origin = "";
            if ("tv".equals(kind)) origin = text(tvOrigin);
            else if ("movie".equals(kind)) origin = text(movieOrigin);
            // Preserve normalization of older on-disk metadata, including series.
            origin = origin.replace(" г.", "").replace(" г", "").replace(", ", " · ");
            if ("movie".equals(kind)) {
                String runtime = text(duration);
                detail = origin.isEmpty() ? runtime : runtime.isEmpty() ? origin : origin + " · " + runtime;
            } else detail = origin;
            fallback = detail.isEmpty() ? text(meta) : detail;
            rating = value > 0.0 ? String.format(Locale.US, "%.1f", value) : "";
            // Original badge color follows the displayed, rounded value, not the raw rating.
            badgeColor = rating.isEmpty() ? 0 : ratingBadgeColor(Double.parseDouble(rating));
        }

        private boolean matches(Recs.Pick pick) {
            return ratingBits == Double.doubleToLongBits(pick.rating)
                    && Objects.equals(kind, pick.kind)
                    && Objects.equals(movieOrigin, pick.movieOrigin)
                    && Objects.equals(tvOrigin, pick.tvOrigin)
                    && Objects.equals(duration, pick.duration)
                    && Objects.equals(meta, pick.meta);
        }

        String detail(boolean allowFallback) { return allowFallback ? fallback : detail; }
    }

    private static String text(String value) { return value == null ? "" : value; }

    private static int ratingBadgeColor(double r) {
        if (r >= 9.8) return 0xFF1EA1F2;
        if (r >= 9.0) return 0xFF186A3A;
        if (r >= 8.0) return 0xFF27B463;
        if (r >= 7.0) return 0xFFF4D03F;
        if (r >= 6.0) return 0xFFF39C13;
        if (r >= 4.1) return 0xFFE74C3C;
        return 0xFF633974;
    }
}
