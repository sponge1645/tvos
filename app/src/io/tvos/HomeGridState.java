package io.tvos;

import java.util.ArrayList;
import java.util.List;

/** All Apps selection, focus and scroll springs. No View, preferences or platform effects. */
final class HomeGridState<T> {
    private final int columns, visibleRows;
    List<T> items = new ArrayList<>();
    Spring[] focus = new Spring[0];
    final Spring sheet = new Spring(0.38f, 0.9f, 0);
    final Spring scroll = new Spring(0.34f, 1f, 0);
    boolean open;
    int index;

    HomeGridState(int columns, int visibleRows) { this.columns = columns; this.visibleRows = visibleRows; }

    void replace(List<T> list) {
        items = list;
        focus = new Spring[list.size()];
        for (int i = 0; i < focus.length; i++) focus[i] = new Spring(0.3f, 0.82f, 0);
        index = Math.min(index, Math.max(0, list.size() - 1));
    }

    void retarget(boolean snap) {
        for (int i = 0; i < focus.length; i++) {
            focus[i].target = open && i == index ? 1 : 0;
            if (snap) focus[i].snap(focus[i].target);
        }
        int rows = Math.max(1, (items.size() + columns - 1) / columns);
        int visible = Math.min(rows, visibleRows);
        int r = index / columns;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
    }

    boolean move(int dx, int dy) {
        int n = items.size();
        if (n == 0) return false;
        int r = index / columns, col = index % columns;
        int rows = (n + columns - 1) / columns;
        int next = index;
        if (dx != 0) {
            int candidate = index + dx;
            if (candidate >= 0 && candidate < n && candidate / columns == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * columns + col);
        }
        if (next == index) return false;
        index = next;
        return true;
    }
}
