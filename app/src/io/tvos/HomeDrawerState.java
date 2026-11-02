package io.tvos;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Drawer entries, leading input group, focus and opening motion; no application or View dependency. */
final class HomeDrawerState<T> {
    private final BiPredicate<T, T> sameEntry;
    private final Predicate<T> isInput;
    List<T> items = new ArrayList<>();
    Spring[] focus = new Spring[0];
    RectF[] rects = new RectF[0];
    final RectF weather = new RectF();
    final Spring motion = new Spring(0.46f, 0.92f, 0);
    float extraHeight;
    int inputs, index;
    boolean open;

    HomeDrawerState(BiPredicate<T, T> sameEntry, Predicate<T> isInput) {
        this.sameEntry = sameEntry;
        this.isInput = isInput;
    }

    /** Replaces data without resetting focus when the count stays the same. Returns visual identity changes. */
    boolean replace(List<T> list) {
        int leading = 0;
        while (leading < list.size() && isInput.test(list.get(leading))) leading++;
        boolean same = list.size() == items.size();
        for (int i = 0; same && i < list.size(); i++) same = sameEntry.test(list.get(i), items.get(i));
        items = list;
        inputs = leading;
        if (focus.length != list.size()) {
            focus = new Spring[list.size()];
            rects = new RectF[list.size()];
            for (int i = 0; i < focus.length; i++) {
                focus[i] = new Spring(0.28f, 0.85f, 0);
                rects[i] = new RectF();
            }
            index = Math.min(index, Math.max(0, list.size() - 1));
        }
        return !same;
    }

    void open(int selected) {
        open = true;
        motion.target = 1;
        index = selected;
        for (int i = 0; i < focus.length; i++) focus[i].snap(i == index ? 1 : 0);
    }

    void close() {
        open = false;
        motion.target = 0;
        for (Spring f : focus) f.snap(0);
    }
}
