package com.crossmux.textplayer.glyph;

import com.crossmux.textplayer.layout.GridLayout;
import com.crossmux.textplayer.layout.GridPager;
import com.crossmux.textplayer.protocol.WireProtocol;

public final class PageBitmapEncoder {
    private final GlyphProvider provider;
    private final RasterGlyphLru<GlyphKey, GlyphBitmap> rasterCache;
    private final GridLayout layout;
    private final String fontId;
    private final float textSizePx;
    private final int style;

    public PageBitmapEncoder(GlyphProvider provider, GridLayout layout, String fontId, float textSizePx,
            int style) {
        this(provider, null, layout, fontId, textSizePx, style);
    }

    public PageBitmapEncoder(GlyphProvider provider, RasterGlyphLru<GlyphKey, GlyphBitmap> rasterCache,
            GridLayout layout, String fontId, float textSizePx, int style) {
        this.provider = provider;
        this.rasterCache = rasterCache;
        this.layout = layout;
        this.fontId = fontId;
        this.textSizePx = textSizePx;
        this.style = style;
    }

    public byte[] encode(GridPager.Page page, long requestId) {
        PageBitmap bitmap = rasterize(page);
        if (bitmap == null) {
            return null;
        }
        int stride = (layout.viewportWidth + 7) / 8;
        byte[] body = new byte[16 + stride * layout.viewportHeight];
        WireProtocol.writeLe32(body, 0, requestId);
        WireProtocol.writeLe32(body, 4, page.getPageId());
        WireProtocol.writeLe16(body, 8, stride);
        WireProtocol.writeLe16(body, 10, layout.viewportWidth);
        WireProtocol.writeLe16(body, 12, layout.viewportHeight);
        WireProtocol.writeLe16(body, 14, 0);
        bitmap.copyTo(body, 16);
        return body;
    }

    public PageBitmap rasterize(GridPager.Page page) {
        int stride = (layout.viewportWidth + 7) / 8;
        byte[] pixels = new byte[stride * layout.viewportHeight];
        GridLayout.Cursor cursor = new GridLayout.Cursor();
        for (GridPager.Token token : page.getTokens()) {
            if (token.getType() == GridPager.TokenType.GLYPH
                    || token.getType() == GridPager.TokenType.ASCII_RUN) {
                GlyphKey key = GlyphKey.forToken(token, fontId, textSizePx, style);
                GlyphBitmap bitmap = rasterCache == null ? provider.rasterize(key) : cachedGlyph(key);
                if (bitmap == null) {
                    return null;
                }
                drawGlyph(pixels, stride, cursor.value, bitmap);
            }
            if (token.isSpanningGlyph()) {
                if (!layout.advanceCells(token.getAdvanceCells(), cursor)) {
                    return null;
                }
                continue;
            }
            int wireToken = token.getType() == GridPager.TokenType.GLYPH ? 0
                    : token.getType() == GridPager.TokenType.SPACE ? -1 : -2;
            if (!layout.advance(wireToken, cursor)) {
                return null;
            }
        }
        return new PageBitmap(layout.viewportWidth, layout.viewportHeight, stride, pixels);
    }

    private GlyphBitmap cachedGlyph(GlyphKey key) {
        GlyphBitmap bitmap = rasterCache.get(key);
        if (bitmap == null) {
            bitmap = provider.rasterize(key);
            if (bitmap != null) {
                rasterCache.put(key, bitmap);
            }
        }
        return bitmap;
    }

    private void drawGlyph(byte[] pixels, int stride, int cursor, GlyphBitmap bitmap) {
        int anchorX = layout.anchorX(cursor);
        int anchorY = layout.anchorY(cursor);
        for (int y = 0; y < bitmap.getHeight(); y++) {
            int targetY = anchorY + bitmap.getOffsetY() + y;
            if (targetY < 0 || targetY >= layout.viewportHeight) {
                continue;
            }
            byte[] glyphPixels = bitmap.getPixels();
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if ((glyphPixels[y * bitmap.getRowStride() + x / 8] & (0x80 >> (x % 8))) == 0) {
                    continue;
                }
                int targetX = anchorX + bitmap.getOffsetX() + x;
                if (targetX < 0 || targetX >= layout.viewportWidth) {
                    continue;
                }
                pixels[targetY * stride + targetX / 8] |= (byte) (0x80 >> (targetX % 8));
            }
        }
    }
}
