package io.tvos.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ordered on/off items for categories and Menu Bar buttons; no view, focus or drawing ownership. */
public final class SettingsOrderEditor {
    public interface Store {
        int[] order();
        boolean enabled(int id);
        int count();
        void setEnabled(int id, boolean enabled);
        void saveOrder(List<Integer> order);
    }

    private final Store store;
    private final List<Integer> rows = new ArrayList<>();

    public SettingsOrderEditor(Store store) { this.store = store; }

    public int size() { return rows.size(); }
    public int get(int row) { return rows.get(row); }

    public int rebuild(int selectedId) {
        rows.clear();
        for (int id : store.order()) if (store.enabled(id)) rows.add(id);
        for (int id : store.order()) if (!store.enabled(id)) rows.add(id);
        return Math.max(0, rows.indexOf(selectedId));
    }

    /** Return the new row of the selected item; footer/invalid rows are unchanged. */
    public int toggle(int row) {
        if (row < 0 || row >= rows.size()) return row;
        int id = rows.get(row);
        boolean enable = !store.enabled(id);
        rows.remove(Integer.valueOf(id));
        if (enable) rows.add(store.count(), id);
        else rows.add(id);
        store.setEnabled(id, enable);
        store.saveOrder(rows);
        return rebuild(id);
    }

    public int move(int row, int direction) {
        if (row < 0 || row >= rows.size()) return row;
        int id = rows.get(row), to = row + direction;
        if (!store.enabled(id) || to < 0 || to >= store.count()) return row;
        Collections.swap(rows, row, to);
        store.saveOrder(rows);
        return to;
    }

}
