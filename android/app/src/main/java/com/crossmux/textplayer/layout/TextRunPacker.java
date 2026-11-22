package com.crossmux.textplayer.layout;

public final class TextRunPacker {
    public interface Measurer {
        int measure(String text, int start, int end);
    }

    public static final class Run {
        private final String text;
        private final int advanceCells;
        private final boolean wrapBefore;

        private Run(String text, int advanceCells, boolean wrapBefore) {
            this.text = text;
            this.advanceCells = advanceCells;
            this.wrapBefore = wrapBefore;
        }

        public String getText() {
            return text;
        }

        public int getAdvanceCells() {
            return advanceCells;
        }

        public boolean isWrapBefore() {
            return wrapBefore;
        }
    }

    private final Measurer measurer;
    private final int cellWidth;
    private final int maxRunWidth;
    private final int wideGlyphCells;

    public TextRunPacker(Measurer measurer, int cellWidth) {
        this(measurer, cellWidth, Integer.MAX_VALUE);
    }

    public TextRunPacker(Measurer measurer, int cellWidth, int maxRunWidth) {
        this(measurer, cellWidth, maxRunWidth, 1);
    }

    public TextRunPacker(Measurer measurer, int cellWidth, int maxRunWidth, int wideGlyphCells) {
        if (measurer == null || cellWidth <= 0 || maxRunWidth <= 0 || wideGlyphCells <= 0) {
            throw new IllegalArgumentException("A measurer and positive run widths are required");
        }
        this.measurer = measurer;
        this.cellWidth = cellWidth;
        this.maxRunWidth = maxRunWidth;
        this.wideGlyphCells = wideGlyphCells;
    }

    int wideGlyphCells() {
        return wideGlyphCells;
    }

    public Run nextRun(String text, int availableCells, int columns) {
        return nextRun(text, 0, availableCells, columns);
    }

    Run nextRun(String text, int start, int availableCells, int columns) {
        if (text == null || start < 0 || start >= text.length() || availableCells <= 0 || availableCells > columns) {
            throw new IllegalArgumentException("Invalid ASCII run or available row");
        }
        int availableWidth = widthForCells(availableCells);
        int rowWidth = widthForCells(columns);
        boolean wrapBefore = shouldWrap(text, start, availableWidth, rowWidth);
        int widthLimit = Math.min(wrapBefore ? rowWidth : availableWidth, maxRunWidth);
        int end = fitEnd(text, start, widthLimit);
        if (end == start && availableCells < columns && !wrapBefore) {
            wrapBefore = true;
            end = fitEnd(text, start, Math.min(rowWidth, maxRunWidth));
        }
        if (end == start) {
            throw new IllegalArgumentException("A character is wider than the grid row");
        }
        if (end < text.length()) {
            for (int index = end - 1; index >= start; index--) {
                if (text.charAt(index) == ' ') {
                    end = index + 1;
                    break;
                }
            }
        }
        String runText = text.substring(start, end);
        int measuredWidth = measurer.measure(text, start, end);
        int advanceCells = (int) Math.max(1, (measuredWidth + (long) cellWidth - 1) / cellWidth);
        if (advanceCells > columns) {
            throw new IllegalArgumentException("ASCII run exceeds the grid row");
        }
        return new Run(runText, advanceCells, wrapBefore);
    }

    private int widthForCells(int cells) {
        return (int) Math.min(Integer.MAX_VALUE, cells * (long) cellWidth);
    }

    private boolean shouldWrap(String text, int start, int availableWidth, int rowWidth) {
        if (availableWidth >= rowWidth) {
            return false;
        }
        int rowEnd = fitEnd(text, start, rowWidth);
        int offset = start;
        while (offset < rowEnd && text.charAt(offset) == ' ') {
            offset++;
        }
        int wordStart = offset;
        while (offset < rowEnd && text.charAt(offset) != ' ') {
            offset++;
        }
        return wordStart < offset && (offset == text.length() || text.charAt(offset) == ' ')
                && measurer.measure(text, start, offset) > availableWidth;
    }

    private int fitEnd(String text, int start, int maxWidth) {
        int low = start;
        int high = start + 1;
        while (measurer.measure(text, start, high) <= maxWidth) {
            if (high == text.length()) {
                return high;
            }
            low = high;
            high = start + (int) Math.min(text.length() - start, 2L * (high - start));
        }
        high--;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (measurer.measure(text, start, middle) <= maxWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }
}
