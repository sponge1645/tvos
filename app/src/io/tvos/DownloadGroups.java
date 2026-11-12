package io.tvos;

/** Visual order is independent of the stable download catalog/action indices. */
final class DownloadGroups {
    private DownloadGroups() { }
    static final int[] ORDER = {0, 3, 4, 1, 2, 5, 6, 7};
    static float position(int row) {
        switch (row) {
            case 0: return 0;
            case 3: return 1;
            case 4: return 2;
            case 1: return 3.5f;
            case 2: return 4.5f;
            case 5: return 6;
            case 6: return 7;
            default: return 8;
        }
    }
    static int move(int row, int direction) {
        for (int n = 0; n < ORDER.length; n++) if (ORDER[n] == row)
            return ORDER[Math.max(0, Math.min(ORDER.length - 1, n + direction))];
        return ORDER[0];
    }
}
