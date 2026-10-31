package io.tvos.version;

import java.util.Locale;

/** Display identity only; Android installation order continues to use versionCode. */
public final class SoftwareBuild {
    private SoftwareBuild() { }
    public static final int SEQUENCE = 121;

    public static String display(String version) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) return version;
        String[] parts = version.split("\\.");
        try {
            int minor = Integer.parseInt(parts[1]);
            StringBuilder letter = new StringBuilder();
            do { letter.insert(0, (char) ('A' + minor % 26)); minor = minor / 26 - 1; } while (minor >= 0);
            return version + " (" + parts[0] + letter + String.format(Locale.ROOT, "%03d", SEQUENCE) + ")";
        } catch (NumberFormatException e) { return version; }
    }
}
