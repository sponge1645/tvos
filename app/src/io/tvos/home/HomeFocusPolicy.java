package io.tvos.home;

import java.util.function.IntPredicate;
import java.util.function.IntSupplier;

/** Saved visual order and action fallbacks; no view, preferences or Android dependency. */
public final class HomeFocusPolicy {
    private HomeFocusPolicy() { }

    public static int next(int current, int direction, int[] categories, int[] buttons, IntPredicate visible) {
        int previous = -1;
        for (int position = 0; position < categories.length + buttons.length; position++) {
            int id = position < categories.length ? categories[position] : 5 + buttons[position - categories.length];
            if (!visible.test(id)) continue;
            if (id == current && direction < 0) return previous < 0 ? current : previous;
            if (previous == current && direction > 0) return id;
            previous = id;
        }
        return current;
    }

    public static int available(int preferred, int[] buttons, IntPredicate enabled, IntSupplier fallback) {
        if (preferred < 5 || (preferred <= 10 && enabled.test(preferred - 5))) return preferred;
        for (int id : buttons) if (enabled.test(id)) return 5 + id;
        return fallback.getAsInt();
    }

    public static int corner(boolean rightmost, int[] buttons, IntPredicate enabled, IntSupplier fallback) {
        int first = -1, last = -1;
        for (int id : buttons) if (enabled.test(id)) {
            if (first < 0) first = 5 + id;
            last = 5 + id;
        }
        return last >= 0 ? (rightmost ? last : first) : fallback.getAsInt();
    }
}
