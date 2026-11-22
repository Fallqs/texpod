package com.crossmux.textplayer.glyph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class GlyphKeyTest {
    @Test
    public void compositeIdentityUsesTheWholeText() {
        GlyphKey first = new GlyphKey("Tex", "font", 24, 0);
        GlyphKey same = new GlyphKey("Tex", "font", 24, 0);
        GlyphKey different = new GlyphKey("pod", "font", 24, 0);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, different);
        assertEquals("Tex", first.getText());
    }

    @Test
    public void singleCodePointIdentityRemainsCompatible() {
        GlyphKey key = new GlyphKey('中', "font", 24, 0);

        assertEquals('中', key.getCodePoint());
        assertEquals("中", key.getText());
    }
}
