package io.tvos.watching;

import java.util.Locale;

/** Verified corrections for ambiguous publisher labels without release metadata. */
public final class ResumeArtworkIdentity {
    private ResumeArtworkIdentity() { }

    /** Supplied release dates always win; corrections never reinterpret source IDs. */
    public static String year(String pkg, String title, String suppliedYear) {
        if (suppliedYear != null && !suppliedYear.isEmpty()) return suppliedYear;
        if (!"kinopub".equals(WatchingSources.id(pkg)) || title == null) return "";
        String label = title.trim().toLowerCase(Locale.ROOT).replaceAll("\\s*/\\s*", "/");
        // Title/year identities explicitly verified by the user. KinoPub's localized
        // Buddy label differs from TMDB's and matches the unrelated 2013 movie.
        if ("приятель/buddy".equals(label)) return "2026";
        if ("сумерки/twilight".equals(label)) return "2008";
        return "";
    }
}
