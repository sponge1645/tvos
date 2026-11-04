package io.tvos;

/** Six-column search grid policy, independent of Android focus and scrolling heuristics. */
final class SearchGridNavigation {
    private SearchGridNavigation() { }
    static int horizontal(int index, int count, int step) {
        int target = index + step;
        return target >= 0 && target < count && target / 6 == index / 6 ? target : index;
    }
    static int vertical(int index, int count, int column, int step) {
        int row = index / 6 + step;
        if (row < 0) return -1; // Search field.
        if (row * 6 >= count) return index;
        return Math.min(row * 6 + Math.max(0, Math.min(5, column)), count - 1);
    }
}
