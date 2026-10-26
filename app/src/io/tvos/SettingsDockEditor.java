package io.tvos;

import java.util.ArrayList;
import java.util.List;

/** Dock selection and candidate ordering; the view owns the scroll window and focused row. */
final class SettingsDockEditor {
    private final List<String[]> rows = new ArrayList<>();
    private final List<String> order = new ArrayList<>();

    void open(List<String> selected, List<String[]> candidates) {
        order.clear();
        order.addAll(selected);
        rebuild(candidates);
    }

    int size() { return rows.size(); }
    String[] get(int row) { return rows.get(row); }
    int position(String pkg) { return order.indexOf(pkg); }

    /** Selected apps first, in Dock order, then the remaining candidates in source order. */
    private void rebuild(List<String[]> candidates) {
        rows.clear();
        for (String pkg : order) {
            for (String[] candidate : candidates) if (candidate[0].equals(pkg)) { rows.add(candidate); break; }
        }
        for (String[] candidate : candidates) if (!order.contains(candidate[0])) rows.add(candidate);
    }

    /** Return the new absolute candidate row, or -1 when the selection did not change. */
    int toggle(int row) {
        if (row < 0 || row >= rows.size()) return -1;
        String pkg = rows.get(row)[0];
        if (order.contains(pkg)) order.remove(pkg);
        else if (order.size() < HomeApps.MAX_SHELF) order.add(pkg);
        else return -1;
        return changed(pkg);
    }

    int move(int row, int direction) {
        if (row < 0 || row >= rows.size()) return -1;
        String pkg = rows.get(row)[0];
        int at = order.indexOf(pkg), to = at + direction;
        if (at < 0 || to < 0 || to >= order.size()) return -1;
        order.remove(at);
        order.add(to, pkg);
        return changed(pkg);
    }

    private int changed(String pkg) {
        rebuild(new ArrayList<>(rows));
        Prefs.setShelf(order);
        for (int i = 0; i < rows.size(); i++) if (rows.get(i)[0].equals(pkg)) return i;
        return -1;
    }
}
