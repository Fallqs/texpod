package com.crossmux.textplayer.layout;

public final class ReaderProfile {
    public static final int TEXT_SIZE_PX = 24;
    public static final int MIN_TEXT_SIZE_PX = 18;
    public static final int MAX_TEXT_SIZE_PX = 36;
    public static final int CELL_WIDTH = 14;
    public static final int WIDE_GLYPH_CELLS = 2;
    public static final int CELL_HEIGHT = 36;
    public static final int BASELINE_X = 2;
    public static final int BASELINE_Y = 26;
    public static final int MAX_RAW_TOKENS = 2048;

    private ReaderProfile() {
    }

    public static int clampTextSize(int textSizePx) {
        return Math.max(MIN_TEXT_SIZE_PX, Math.min(MAX_TEXT_SIZE_PX, textSizePx));
    }

    public static int cellWidth(int textSizePx) {
        return (clampTextSize(textSizePx) + 5) / 2;
    }

    public static int cellHeight(int textSizePx) {
        return clampTextSize(textSizePx) * 3 / 2;
    }

    public static int baselineY(int textSizePx) {
        return clampTextSize(textSizePx) + 2;
    }

    public static GridLayout phonePreview() {
        return phonePreview(TEXT_SIZE_PX);
    }

    public static GridLayout phonePreview(int textSizePx) {
        int cellWidth = cellWidth(textSizePx);
        int cellHeight = cellHeight(textSizePx);
        return new GridLayout(24, 36, (480 - 48) / cellWidth, (800 - 72) / cellHeight,
                cellWidth, cellHeight, BASELINE_X, baselineY(textSizePx), 480, 800);
    }

    public static GridLayout fromCapabilities(int safeX, int safeY, int safeWidth, int safeHeight,
            int viewportWidth, int viewportHeight) {
        return fromCapabilities(safeX, safeY, safeWidth, safeHeight, viewportWidth, viewportHeight, TEXT_SIZE_PX);
    }

    public static GridLayout fromCapabilities(int safeX, int safeY, int safeWidth, int safeHeight,
            int viewportWidth, int viewportHeight, int textSizePx) {
        int cellWidth = cellWidth(textSizePx);
        int cellHeight = cellHeight(textSizePx);
        return new GridLayout(safeX, safeY, safeWidth / cellWidth, safeHeight / cellHeight,
                cellWidth, cellHeight, BASELINE_X, baselineY(textSizePx), viewportWidth, viewportHeight);
    }
}
