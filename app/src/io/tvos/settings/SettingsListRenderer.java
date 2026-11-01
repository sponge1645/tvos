package io.tvos.settings;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** List row presentation. Receives draw data only; never reads launcher settings or navigation. */
public final class SettingsListRenderer {
    public enum Layout { STANDARD, ROOT, ABOUT }
    public enum Kind { NORMAL, DETAIL, PICKER, SHELF }
    public enum Marker { NONE, CHEVRON, SERVICE_CHECK, CHOICE_CHECK, PICKER_CHECK, SPINNER }
    public interface Check {
        float width(float height);
        void draw(Canvas canvas, float x, float y, float size, int color);
    }
    public interface Spinner { void draw(Canvas canvas, float x, float y, float scale, int color); }

    /** Reused immediate draw input; reset before each row, never retained as UI state. */
    public static final class Row {
        public String label, value;
        public float focus;
        public Kind kind;
        public Marker marker;
        public boolean disabled, fitValue, showValue, markerAfterValue;
        public float valueInset;
        public Row set(String label, String value, float focus) {
            this.label = label;
            this.value = value;
            this.focus = focus;
            kind = Kind.NORMAL;
            marker = Marker.NONE;
            disabled = fitValue = false;
            showValue = true;
            markerAfterValue = false;
            valueInset = 13;
            return this;
        }
    }

    private static final int INK = 0xFF1C1C1E;
    private final SettingsRowRenderer capsules;
    private final SettingsTextRenderer text;
    private final Check check;
    private final Spinner spinner;

    public SettingsListRenderer(SettingsRowRenderer capsules, SettingsTextRenderer text, Check check, Spinner spinner) {
        this.capsules = capsules;
        this.text = text;
        this.check = check;
        this.spinner = spinner;
    }

    public void beginShelf(Paint labelPaint) { text.beginShelf(labelPaint); }

    public void draw(Canvas c, RectF bounds, float scale, float baseline, boolean light,
            Paint labelPaint, Paint valuePaint, Row row) {
        float f = row.kind == Kind.DETAIL ? 0 : capsules.draw(c, bounds, scale, light, row.focus);
        int labelColor = row.disabled ? 0x66808080 : blend(normalInk(light), INK, f);
        labelPaint.setColor(labelColor);
        if (row.kind == Kind.PICKER)
            text.drawPickerLabel(c, row.label, bounds, scale, baseline, row.marker == Marker.PICKER_CHECK, labelPaint);
        else if (row.kind == Kind.SHELF)
            text.drawShelfLabel(c, row.label, bounds, scale, baseline, labelPaint);
        else text.drawRowLabel(c, row.label, bounds, scale, baseline, labelPaint);

        if (!row.markerAfterValue) drawMarker(c, bounds, scale, light, f, row.marker);
        if (row.showValue) {
            valuePaint.setColor(row.disabled ? 0x66808080 : valueInk(light, f));
            if (row.kind == Kind.DETAIL) {
                text.drawTracked(c, row.value, bounds.right - 30 * scale + 20 * scale, baseline, valuePaint, .02f);
            } else {
                String value = row.fitValue ? text.fitRowValue(row.label, row.value, bounds, scale, labelPaint, valuePaint) : row.value;
                text.drawRowValue(c, value, bounds, scale, baseline, row.valueInset, valuePaint);
            }
        }
        if (row.markerAfterValue) drawMarker(c, bounds, scale, light, f, row.marker);
    }

    private void drawMarker(Canvas c, RectF bounds, float scale, boolean light, float f, Marker marker) {
        switch (marker) {
            case CHEVRON: capsules.drawChevron(c, bounds, scale); break;
            case SPINNER: spinner.draw(c, bounds.right - 43 * scale, bounds.centerY(), scale,
                    f > .5f ? 0xFF777777 : secondaryInk(light)); break;
            case SERVICE_CHECK: check.draw(c, bounds.right - 43 * scale, bounds.centerY(), 24 * scale,
                    blend(normalInk(light), INK, f)); break;
            case CHOICE_CHECK: case PICKER_CHECK: {
                float size = 26 * scale;
                float width = check.width(size);
                int ink = marker == Marker.CHOICE_CHECK ? arrowInk(light) : 0xFFFFFFFF;
                check.draw(c, bounds.right - 30 * scale - width / 2, bounds.centerY(), size, blend(ink, INK, f));
                break;
            }
            default: break;
        }
    }

    private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186, ROW_H = 62;
    private static final float ROW_GAP = 22, NAME_OFFSET = 3, DETAILS_OFFSET = 13, RIGHT_EXTENSION = 6;

    public static void approvedBounds(RectF out, int row, float scale) {
        float y = (LIST_TOP + row * (ROW_H + ROW_GAP) + NAME_OFFSET) * scale;
        out.set(LIST_X * scale, y, (LIST_R + RIGHT_EXTENSION) * scale, y + ROW_H * scale);
    }
    public static void scrollingBounds(RectF out, int absoluteRow, int logicalOffset, float position, float scale) {
        approvedBounds(out, absoluteRow - logicalOffset, scale);
        out.offset(0, (logicalOffset - position) * (ROW_H + ROW_GAP) * scale);
    }
    public static void menuBounds(RectF out, int row, Layout layout, float position, float scale) {
        float y = (LIST_TOP + row * (ROW_H + ROW_GAP)
                + (layout == Layout.ABOUT ? (row == 0 ? NAME_OFFSET : DETAILS_OFFSET) : NAME_OFFSET)) * scale;
        if (layout == Layout.ROOT) y -= position * (ROW_H + ROW_GAP) * scale;
        out.set(LIST_X * scale, y, LIST_R * scale, y + ROW_H * scale);
        if (layout != Layout.ABOUT || row == 0) out.right += RIGHT_EXTENSION * scale;
    }
    public static void accessibilityBounds(RectF out, int row, Layout layout, float rootPosition,
            boolean scrolling, int logicalOffset, float position, float scale) {
        float top = LIST_TOP + row * (ROW_H + ROW_GAP)
                + (layout == Layout.ABOUT && row != 0 ? DETAILS_OFFSET : NAME_OFFSET);
        if (layout == Layout.ROOT) top -= rootPosition * (ROW_H + ROW_GAP);
        out.set(LIST_X * scale, top * scale, (LIST_R + RIGHT_EXTENSION) * scale, (top + ROW_H) * scale);
        if (scrolling) out.offset(0, (logicalOffset - position) * (ROW_H + ROW_GAP) * scale);
    }

    public static int normalInk(boolean light) { return light ? 0xE5162730 : 0xF2FFFFFF; }
    public static int secondaryInk(boolean light) { return light ? 0xFF758390 : 0xFF6C7B81; }
    public static int valueInk(boolean light, float focus) { return light ? blend(secondaryInk(true), 0xFF999999, focus) : 0xFF999999; }
    private static int arrowInk(boolean light) { return light ? 0x8A34444C : 0x66FFFFFF; }
    public static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }

    public static int paintRow(int pass, int first, int end, int active) {
        if (active < first || active >= end) return first + pass;
        if (pass == end - first - 1) return active;
        int row = first + pass;
        return row >= active ? row + 1 : row;
    }
}
