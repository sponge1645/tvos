package io.tvos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ordered on/off items for categories and Menu Bar buttons; no view, focus or drawing ownership. */
final class SettingsOrderEditor {
    enum Kind { CATEGORIES, MENU_BUTTONS }
    private final Kind kind;
    private final List<Integer> rows = new ArrayList<>();

    SettingsOrderEditor(Kind kind) { this.kind = kind; }

    int size() { return rows.size(); }
    int get(int row) { return rows.get(row); }

    int rebuild(int selectedId) {
        rows.clear();
        for (int id : order()) if (enabled(id)) rows.add(id);
        for (int id : order()) if (!enabled(id)) rows.add(id);
        return Math.max(0, rows.indexOf(selectedId));
    }

    /** Return the new row of the selected item; footer/invalid rows are unchanged. */
    int toggle(int row) {
        if (row < 0 || row >= rows.size()) return row;
        int id = rows.get(row);
        boolean enable = !enabled(id);
        rows.remove(Integer.valueOf(id));
        if (enable) rows.add(count(), id);
        else rows.add(id);
        if (kind == Kind.CATEGORIES) Prefs.setCategoryEnabled(id, enable);
        else Prefs.setTopButtonEnabled(id, enable);
        saveOrder();
        return rebuild(id);
    }

    int move(int row, int direction) {
        if (row < 0 || row >= rows.size()) return row;
        int id = rows.get(row), to = row + direction;
        if (!enabled(id) || to < 0 || to >= count()) return row;
        Collections.swap(rows, row, to);
        saveOrder();
        return to;
    }

    private int[] order() {
        return kind == Kind.CATEGORIES ? Prefs.categoryOrder() : Prefs.topButtonOrder();
    }

    private boolean enabled(int id) {
        return kind == Kind.CATEGORIES ? Prefs.categoryEnabled(id) : Prefs.topButtonEnabled(id);
    }

    private int count() {
        return kind == Kind.CATEGORIES ? Prefs.categoryCount() : Prefs.topButtonCount();
    }

    private void saveOrder() {
        if (kind == Kind.CATEGORIES) Prefs.setCategoryOrder(rows);
        else Prefs.setTopButtonOrder(rows);
    }
}
