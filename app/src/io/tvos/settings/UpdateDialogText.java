package io.tvos.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Bounded paragraph wrapping, including unbroken errors and supplementary Unicode characters. */
public final class UpdateDialogText {
    private UpdateDialogText() { }

    public static String[] wrap(String[] paragraphs, float width, int limit, ToDoubleFunction<String> measure) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String[] blocks = paragraph.split("\\n", -1);
            for (String block : blocks) {
                String rest = block.trim();
                if (rest.isEmpty()) {
                    lines.add("");
                } else {
                    while (!rest.isEmpty()) {
                        if (lines.size() == limit) return truncate(lines, width, measure);
                        int end = 0, space = -1;
                        while (end < rest.length()) {
                            int next = rest.offsetByCodePoints(end, 1);
                            if (measure.applyAsDouble(rest.substring(0, next)) > width) break;
                            if (Character.isWhitespace(rest.codePointAt(end))) space = end;
                            end = next;
                        }
                        if (end == rest.length()) { lines.add(rest); break; }
                        if (end == 0) end = rest.offsetByCodePoints(0, 1);
                        int cut = space > 0 ? space : end;
                        lines.add(rest.substring(0, cut).trim());
                        rest = rest.substring(cut).trim();
                    }
                }
                if (lines.size() > limit) return truncate(lines.subList(0, limit), width, measure);
            }
        }
        return lines.toArray(new String[0]);
    }

    private static String[] truncate(List<String> lines, float width, ToDoubleFunction<String> measure) {
        String last = lines.get(lines.size() - 1);
        while (!last.isEmpty() && measure.applyAsDouble(last + "…") > width)
            last = last.substring(0, last.offsetByCodePoints(last.length(), -1));
        lines.set(lines.size() - 1, last + "…");
        return lines.toArray(new String[0]);
    }
}
