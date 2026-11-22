package com.crossmux.textplayer.glyph;

public interface GlyphProvider {
    GlyphBitmap rasterize(GlyphKey key);
}
