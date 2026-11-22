package com.crossmux.textplayer.glyph;

public final class GlyphBitmap {
    private final int width;
    private final int height;
    private final int rowStride;
    private final int offsetX;
    private final int offsetY;
    private final byte[] pixels;

    public GlyphBitmap(int width, int height, int rowStride, int offsetX, int offsetY, byte[] pixels) {
        if (width <= 0 || height <= 0 || rowStride != (width + 7) / 8
                || pixels == null || pixels.length != rowStride * height) {
            throw new IllegalArgumentException("Invalid 1bpp glyph");
        }
        this.width = width;
        this.height = height;
        this.rowStride = rowStride;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.pixels = pixels.clone();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getRowStride() {
        return rowStride;
    }

    public int getOffsetX() {
        return offsetX;
    }

    public int getOffsetY() {
        return offsetY;
    }

    public byte[] getPixels() {
        return pixels;
    }

    public int getByteLength() {
        return pixels.length;
    }
}
