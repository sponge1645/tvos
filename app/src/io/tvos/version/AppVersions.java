package io.tvos.version;

/** Numeric release names only. Unknown formats are never treated as an installed version match. */
public final class AppVersions {
    private AppVersions() {}

    public static Integer compare(String left, String right) {
        int[] a = numeric(left), b = numeric(right);
        if (a == null || b == null) return null;
        for (int i = 0; i < a.length; i++) {
            int order = Integer.compare(a[i], b[i]);
            if (order != 0) return order;
        }
        return 0;
    }

    private static int[] numeric(String value) {
        if (value == null) return null;
        value = value.trim().replaceFirst("^[vV]", "");
        if (!value.matches("[0-9]+(\\.[0-9]+){0,3}")) return null;
        int[] result = new int[4];
        String[] parts = value.split("\\.");
        try {
            for (int i = 0; i < parts.length; i++) result[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return result;
    }
}
