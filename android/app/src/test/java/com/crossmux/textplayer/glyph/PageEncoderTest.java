package com.crossmux.textplayer.glyph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.crossmux.textplayer.layout.GridPager;
import com.crossmux.textplayer.layout.TextRunPacker;
import com.crossmux.textplayer.protocol.WireProtocol;

import org.junit.Test;

public final class PageEncoderTest {
    @Test
    public void encodesBindingsPixelsAndTerminalToken() {
        GridPager pager = new GridPager(4, 2, 16);
        pager.append("aba");
        PageEncoder encoder = newEncoder(4, 64);

        PageEncoder.EncodedPage page = encoder.encode(pager.pageAt(0), 7, 1);

        assertNotNull(page);
        assertEquals(DeviceGlyphCache.Encoding.GRID, page.getEncoding());
        assertEquals(7, WireProtocol.readLe32(page.getBody(), 0));
        assertEquals(2, WireProtocol.readLe16(page.getBody(), 24));
        assertEquals(4, WireProtocol.readLe16(page.getBody(), 26));
        assertEquals(-3, (short) WireProtocol.readLe16(page.getBody(), page.getBody().length - 2));
        assertEquals(32 + 40 + 4 + 8, page.getBody().length);
        encoder.commitDisplayed(page);
        assertEquals(1, encoderCommitRevision(encoder));
    }

    @Test
    public void repeatedGlyphsUseDeviceCacheOnNextPage() {
        GridPager pager = new GridPager(2, 2, 5);
        pager.append("ab");
        PageEncoder encoder = newEncoder(4, 64);
        encoder.commitDisplayed(encoder.encode(pager.pageAt(0), 0, 1));
        pager.markDelivered(pager.pageAt(0).getPageId());
        pager.append("cd");

        PageEncoder.EncodedPage page = encoder.encode(pager.pageAt(1), 0, 2);

        assertEquals(2, WireProtocol.readLe16(page.getBody(), 24));
        assertEquals(1, WireProtocol.readLe32(page.getBody(), 12));
        assertEquals(2, WireProtocol.readLe32(page.getBody(), 20));
    }

    @Test
    public void oversizedWorkingSetRequestsBitmapWithoutCacheCommit() {
        GridPager pager = new GridPager(4, 2, 16);
        pager.append("abc");
        PageEncoder encoder = newEncoder(2, 64);

        PageEncoder.EncodedPage page = encoder.encode(pager.pageAt(0), 0, 1);

        assertEquals(DeviceGlyphCache.Encoding.BITMAP, page.getEncoding());
        assertNull(page.getBody());
        encoder.commitDisplayed(page);
        assertEquals(0, encoderCommitRevision(encoder));
    }

    @Test
    public void asciiRunEncodesOpcodeSlotAndMeasuredSpan() {
        GridPager pager = new GridPager(4, 2, 16,
                new TextRunPacker((text, start, end) -> (end - start) * 12, 24));
        pager.append("abba");
        PageEncoder encoder = newEncoder(4, 64);

        PageEncoder.EncodedPage page = encoder.encode(pager.pageAt(0), 0, 1);

        assertNotNull(page);
        assertEquals(1, WireProtocol.readLe16(page.getBody(), 24));
        assertEquals(4, WireProtocol.readLe16(page.getBody(), 26));
        int tokens = 32 + 20 + 2;
        assertEquals(-4, (short) WireProtocol.readLe16(page.getBody(), tokens));
        int slot = WireProtocol.readLe16(page.getBody(), tokens + 2);
        assertEquals(2, WireProtocol.readLe16(page.getBody(), tokens + 4));
        assertEquals(-3, (short) WireProtocol.readLe16(page.getBody(), tokens + 6));
        assertEquals(tokens + 8, page.getBody().length);
        encoder.commitDisplayed(page);

        pager.markDelivered(pager.pageAt(0).getPageId());
        pager.append("abba");
        PageEncoder.EncodedPage cached = encoder.encode(pager.pageAt(1), 0, 2);
        assertEquals(0, WireProtocol.readLe16(cached.getBody(), 24));
        int cachedTokens = cached.getBody().length - 8;
        assertEquals(-4, (short) WireProtocol.readLe16(cached.getBody(), cachedTokens));
        assertEquals(slot, WireProtocol.readLe16(cached.getBody(), cachedTokens + 2));
    }

    @Test
    public void punctuationPairUsesOneCachedGlyphAndOneWireToken() {
        GridPager pager = new GridPager(2, 1, 16);
        pager.append("：“：“");
        GlyphProvider provider = key -> {
            assertEquals("：“", key.getText());
            return new GlyphBitmap(8, 2, 1, 0, -1, new byte[] {0, 1});
        };
        PageEncoder encoder = new PageEncoder(provider, new RasterGlyphLru<>(32, 4096, GlyphBitmap::getByteLength),
                new DeviceGlyphCache<>(4, 64), "test-font", 24, 0);
        byte[] body = encoder.encode(pager.pageAt(0), 0, 1).getBody();

        assertEquals(1, WireProtocol.readLe16(body, 24));
        assertEquals(3, WireProtocol.readLe16(body, 26));
        assertEquals(WireProtocol.readLe16(body, 54), WireProtocol.readLe16(body, 56));
        assertEquals(-3, (short) WireProtocol.readLe16(body, 58));
    }

    private PageEncoder newEncoder(int slots, int bytes) {
        GlyphProvider provider = key -> {
            int codePoint = key.getCodePoint();
            return new GlyphBitmap(8, 2, 1, 0, -1,
                    new byte[] {(byte) codePoint, (byte) (codePoint + 1)});
        };
        return new PageEncoder(provider, new RasterGlyphLru<>(32, 4096, GlyphBitmap::getByteLength),
                new DeviceGlyphCache<>(slots, bytes), "test-font", 24, 0);
    }

    private long encoderCommitRevision(PageEncoder encoder) {
        GridPager pager = new GridPager(1, 1, 2);
        pager.append("z");
        PageEncoder.EncodedPage page = encoder.encode(pager.pageAt(0), 0, 99);
        return WireProtocol.readLe32(page.getBody(), 12);
    }
}
