package com.crossmux.textplayer.glyph;

public final class PageBitmap {
    private final int width;
    private final int height;
    private final int rowStride;
    private final byte[] pixels;

    public PageBitmap(int width, int height, int rowStride, byte[] pixels) {
        long byteLength = (long) rowStride * height;
        if (width <= 0 || height <= 0 || rowStride != (width + 7) / 8
                || pixels == null || byteLength != pixels.length) {
            throw new IllegalArgumentException("Invalid 1bpp page");
        }
        this.width = width;
        this.height = height;
        this.rowStride = rowStride;
        this.pixels = pixels;
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

    public void copyTo(byte[] destination, int offset) {
        System.arraycopy(pixels, 0, destination, offset, pixels.length);
    }

    public boolean isInk(int x, int y) {
        return (pixels[y * rowStride + x / 8] & (0x80 >> (x % 8))) != 0;
    }
}
