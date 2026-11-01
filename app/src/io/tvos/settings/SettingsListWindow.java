package io.tvos.settings;

/** Logical list window; selection, data loading and animated position belong to the caller. */
public final class SettingsListWindow {
    private final int capacity;
    private int offset;

    public SettingsListWindow(int capacity) { this.capacity = capacity; }
    public int capacity() { return capacity; }
    public int offset() { return offset; }
    public int visibleRows(int size) { return Math.min(capacity, size); }
    public int absoluteRow(int row) { return offset + row; }
    public void reset() { offset = 0; }

    /** Preserve three context rows before the selected picker item where possible. */
    public int openPicker(int selected, int size) {
        int at = Math.max(0, selected);
        offset = Math.max(0, Math.min(at - 3, size - visibleRows(size)));
        return at - offset;
    }

    /** Follow an item after reordering; a rejected edit leaves selection unchanged. */
    public int keepVisible(int absoluteRow, int rows, int focus) {
        if (absoluteRow < 0) return focus;
        if (absoluteRow < offset) offset = absoluteRow;
        else if (absoluteRow >= offset + rows) offset = absoluteRow - rows + 1;
        return absoluteRow - offset;
    }

    public boolean canMove(int direction, int focus, int rows, int size, boolean scrolling) {
        return direction < 0 ? focus > 0 || (scrolling && offset > 0)
                : focus < rows - 1 || (scrolling && offset + rows < size);
    }

    public int move(int direction, int focus, int rows, int size, boolean scrolling) {
        if (direction < 0) {
            if (focus > 0) return focus - 1;
            if (scrolling && offset > 0) offset--;
        } else {
            if (focus < rows - 1) return focus + 1;
            if (scrolling && offset + rows < size) offset++;
        }
        return focus;
    }

    // Overscan both edges so rows physically enter the viewport's fade bands.
    public int firstDrawn(float position) {
        return Math.max(0, (int) Math.floor(position) - 1);
    }
    public int endDrawn(float position, int size) {
        return Math.min(size, (int) Math.ceil(position) + capacity + 1);
    }
}
