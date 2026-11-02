package io.tvos;

import android.graphics.RectF;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Dock item order and its focus/drag animation identities. UI effects stay with the caller. */
final class HomeDockState<T> {
    private final BiPredicate<T, T> sameKey;
    List<T> items = new ArrayList<>();
    Spring[] focus = new Spring[0], slide = new Spring[0];
    RectF[] rects = new RectF[0];
    int index, liftedIndex = -1;
    long wiggleAt;
    final Spring lift = new Spring(0.3f, 0.72f, 0);

    HomeDockState(BiPredicate<T, T> sameKey) { this.sameKey = sameKey; }

    /** Refresh objects even with unchanged keys, retaining animation state in that case. */
    boolean replace(List<T> list) {
        boolean same = list.size() == items.size();
        for (int i = 0; same && i < list.size(); i++) same = sameKey.test(list.get(i), items.get(i));
        items = list;
        if (same && focus.length == list.size()) return false;
        focus = new Spring[list.size()];
        rects = new RectF[list.size()];
        slide = new Spring[list.size()];
        for (int i = 0; i < focus.length; i++) {
            focus[i] = new Spring(0.34f, 0.74f, 0);
            slide[i] = new Spring(0.3f, 0.72f, 0);
            rects[i] = new RectF();
        }
        index = Math.min(index, Math.max(0, list.size() - 1));
        return true;
    }

    void begin(long now) {
        liftedIndex = index;
        wiggleAt = now;
        lift.target = 1;
    }

    boolean step(float dt, boolean editing) {
        boolean moving = false;
        for (Spring spring : slide) moving |= spring.step(dt);
        moving |= lift.step(dt);
        if (editing || lift.value > 0.001f) moving = true;
        if (!editing && !moving) liftedIndex = -1;
        return moving;
    }

    void swap(int from, int to) {
        T item = items.get(from);
        items.set(from, items.get(to));
        items.set(to, item);
        Spring f = focus[from]; focus[from] = focus[to]; focus[to] = f;
        Spring s = slide[from]; slide[from] = slide[to]; slide[to] = s;
        float distance = rects[from].left - rects[to].left;
        slide[to].value += distance;
        slide[from].value -= distance;
        index = liftedIndex = to;
    }

    /** Return to the captured order without jumping the moving tiles. Null commits the order. */
    void finish(List<T> before) {
        lift.target = 0;
        if (before == null || before.size() != items.size()) return;
        T selected = items.get(index);
        int n = items.size();
        Spring[] restoredFocus = new Spring[n], restoredSlide = new Spring[n];
        for (int a = 0; a < n; a++) {
            int b = items.indexOf(before.get(a));
            if (b < 0) b = a;
            restoredFocus[a] = focus[b];
            restoredSlide[a] = slide[b];
            restoredSlide[a].value += rects[b].left - rects[a].left;
        }
        items = before;
        focus = restoredFocus;
        slide = restoredSlide;
        index = liftedIndex = items.indexOf(selected);
        if (index < 0) index = liftedIndex = 0;
    }
}
