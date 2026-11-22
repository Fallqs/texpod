package com.crossmux.textplayer.layout;

public final class GridLayout {
    public final int originX;
    public final int originY;
    public final int columns;
    public final int rows;
    public final int cellWidth;
    public final int cellHeight;
    public final int baselineOffsetX;
    public final int baselineOffsetY;
    public final int viewportWidth;
    public final int viewportHeight;

    public GridLayout(int originX, int originY, int columns, int rows, int cellWidth, int cellHeight,
            int baselineOffsetX, int baselineOffsetY, int viewportWidth, int viewportHeight) {
        this.originX = originX;
        this.originY = originY;
        this.columns = columns;
        this.rows = rows;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.baselineOffsetX = baselineOffsetX;
        this.baselineOffsetY = baselineOffsetY;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    public boolean isValid() {
        if (columns <= 0 || rows <= 0 || cellWidth <= 0 || cellHeight <= 0 || originX < 0 || originY < 0
                || viewportWidth <= 0 || viewportHeight <= 0) {
            return false;
        }
        long right = (long) originX + (long) columns * cellWidth;
        long bottom = (long) originY + (long) rows * cellHeight;
        if (right > viewportWidth || bottom > viewportHeight) {
            return false;
        }
        long firstX = (long) originX + baselineOffsetX;
        long firstY = (long) originY + baselineOffsetY;
        long lastX = firstX + (long) (columns - 1) * cellWidth;
        long lastY = firstY + (long) (rows - 1) * cellHeight;
        return firstX >= 0 && firstY >= 0 && lastX >= 0 && lastY >= 0 && firstX < viewportWidth
                && firstY < viewportHeight && lastX < viewportWidth && lastY < viewportHeight;
    }

    public int cellCount() {
        return columns * rows;
    }

    public int anchorX(int cursor) {
        return originX + cursor % columns * cellWidth + baselineOffsetX;
    }

    public int anchorY(int cursor) {
        return originY + cursor / columns * cellHeight + baselineOffsetY;
    }

    public boolean advance(int token, Cursor cursor) {
        if (token >= 0 || token == -1) {
            return advanceCells(1, cursor);
        }
        if (token == -2) {
            int next = (cursor.value / columns + 1) * columns;
            if (next > cellCount()) {
                return false;
            }
            cursor.value = next;
            return true;
        }
        if (token == -3) {
            cursor.value = cellCount();
            return true;
        }
        return false;
    }

    public boolean advanceCells(int cells, Cursor cursor) {
        if (cells <= 0 || cells > columns || cursor.value < 0 || cursor.value > cellCount() - cells
                || cursor.value % columns + cells > columns) {
            return false;
        }
        cursor.value += cells;
        return true;
    }

    public static final class Cursor {
        public int value;
    }
}
