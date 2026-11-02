package io.tvos.home;

import java.util.ArrayList;
import java.util.List;

/** Stateless poster page and prefetch policy; the caller owns page, focus and catalog state. */
public final class HomePosterWindow {
    private HomePosterWindow() { }

    public static int clampPage(int page, int size, int perPage) {
        int pages = Math.max(1, (size + perPage - 1) / perPage);
        return Math.min(page, pages - 1);
    }

    public static int start(int page, int size, int perPage) { return Math.min(size, page * perPage); }

    /** Object identity is intentional: a new localized object with the same ID must replace the old card. */
    public static <T> List<T> select(List<T> catalog, int from, int perPage, List<T> current) {
        int end = Math.min(catalog.size(), from + perPage);
        boolean same = current.size() == end - from;
        for (int i = 0; same && i < current.size(); i++) same = catalog.get(from + i) == current.get(i);
        return same ? current : new ArrayList<>(catalog.subList(from, end));
    }

    /** Current page, next two pages, then previous page, preserving request priority. */
    public static <T> List<T> prefetch(List<T> catalog, int from, int perPage) {
        int ahead = Math.min(catalog.size(), from + perPage * 3);
        List<T> result = new ArrayList<>(catalog.subList(from, ahead));
        result.addAll(catalog.subList(Math.max(0, from - perPage), from));
        return result;
    }
}
