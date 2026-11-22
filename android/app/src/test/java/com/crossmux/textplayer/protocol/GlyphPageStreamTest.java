package com.crossmux.textplayer.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class GlyphPageStreamTest {
    @Test
    public void encodesFirmwareGoldenVector() {
        GlyphPageStream page = new GlyphPageStream(5, 27);
        page.glyph(1, 0x1234);
        page.positioned(2, 0x5678, -3, 5);
        page.advance(2);
        page.newline();
        page.glyph(0, 0xffff);
        assertArrayEquals(new byte[] {
                7, 0, 0, 0, 4, 3, 2, 1, 5, 0, 1, 0x34, 0x12,
                0x12, 0x78, 0x56, (byte) 0xfd, (byte) 0xff, 5, 0,
                0x20, 2, 0, 0x21, 0, (byte) 0xff, (byte) 0xff
        }, page.encode(7, 0x01020304));
        assertThrows(IllegalArgumentException.class, page::newline);
    }

    @Test
    public void rejectsInvalidOperandsOrOversizeObjects() {
        GlyphPageStream page = new GlyphPageStream(8, 13);
        assertThrows(IllegalArgumentException.class, () -> page.glyph(16, 4));
        assertThrows(IllegalArgumentException.class, () -> page.glyph(0, 0x10000));
        assertThrows(IllegalArgumentException.class, () -> page.positioned(0, 4, 32768, 0));
        assertThrows(IllegalArgumentException.class, () -> page.advance(0));
        assertThrows(IllegalArgumentException.class, () -> page.encode(0, 0));
        page.glyph(0, 0);
        assertThrows(IllegalArgumentException.class, page::newline);
    }
}
