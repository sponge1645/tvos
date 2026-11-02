package io.tvos;

import java.util.ArrayList;
import java.util.List;

/** Search presentation session. Request scheduling and page/focus navigation have separate owners. */
final class HomeSearchState<T> {
    boolean active;
    String query = "";
    List<T> results = new ArrayList<>();
    final Spring heading = new Spring(0.42f, 1f, 0);

    /** Reopening input preserves results and page; entering a new session resets them. */
    boolean begin() {
        boolean fresh = !active;
        if (fresh) results = new ArrayList<>();
        active = true;
        return fresh;
    }

    void changed(String text) { query = text; results = new ArrayList<>(); }
    void accept(List<T> found) { results = found; }
    /** Keep the last query for a later opening, as before. */
    void close() { active = false; results.clear(); }

    void inputOpen(boolean open) {
        if (open && !active) heading.snap(0);
        heading.target = open || !active ? 0 : 1;
    }

    boolean tickHeading(float dt) {
        if (!active) { heading.snap(0); return false; }
        return heading.step(dt);
    }
}
