package com.crossmux.textplayer.glyph;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.crossmux.textplayer.layout.GridLayout;
import com.crossmux.textplayer.layout.GridPager;
import com.crossmux.textplayer.protocol.WireProtocol;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public final class PageBitmapEncoderTest {
    @Test
    public void rendersGlyphsIntoViewportWithoutChangingHeaderIdentity() {
        GridLayout layout = new GridLayout(0, 0, 2, 2, 4, 4, 0, 0, 8, 8);
        GridPager pager = new GridPager(2, 2, 5);
        pager.append("a");
        GlyphProvider provider = key -> new GlyphBitmap(1, 1, 1, 0, 0, new byte[] {(byte) 0x80});
        PageBitmapEncoder encoder = new PageBitmapEncoder(provider, layout, "test", 4, 0);

        byte[] body = encoder.encode(pager.pageAt(0), 9);

        assertNotNull(body);
        assertEquals(16 + 8, body.length);
        assertEquals(9, WireProtocol.readLe32(body, 0));
        assertEquals(1, WireProtocol.readLe16(body, 8));
        assertEquals((byte) 0x80, body[16]);
        for (int index = 17; index < body.length; index++) {
            assertEquals(0, body[index]);
        }
    }

    @Test
    public void localPreviewIsExactlyTheBitmapSentToTheReader() {
        GridLayout layout = new GridLayout(2, 3, 4, 3, 4, 4, 1, 0, 16, 16);
        GridPager pager = new GridPager(4, 3, 32);
        pager.append("ab\ncd");
        GlyphProvider provider = key -> new GlyphBitmap(2, 1, 1, 0, 0, new byte[] {(byte) 0x80});
        RasterGlyphLru<GlyphKey, GlyphBitmap> cache = new RasterGlyphLru<>(16, 128,
                GlyphBitmap::getByteLength);
        PageBitmapEncoder encoder = new PageBitmapEncoder(provider, cache, layout, "test", 4, 0);

        PageBitmap preview = encoder.rasterize(pager.pageAt(0));
        byte[] body = encoder.encode(pager.pageAt(0), 27);
        byte[] transported = new byte[16 + preview.getRowStride() * preview.getHeight()];
        preview.copyTo(transported, 16);
        WireProtocol.writeLe32(transported, 0, 27);
        WireProtocol.writeLe32(transported, 4, 1);
        WireProtocol.writeLe16(transported, 8, preview.getRowStride());
        WireProtocol.writeLe16(transported, 10, preview.getWidth());
        WireProtocol.writeLe16(transported, 12, preview.getHeight());

        assertArrayEquals(transported, body);
        assertEquals(preview.getWidth(), WireProtocol.readLe16(body, 10));
        assertEquals(preview.getHeight(), WireProtocol.readLe16(body, 12));
    }

    @Test
    public void asciiRunUsesMeasuredSpanBeforeTheNextGlyph() {
        GridLayout layout = new GridLayout(0, 0, 4, 2, 4, 4, 0, 0, 16, 8);
        GridPager pager = new GridPager(4, 2, 16,
                new com.crossmux.textplayer.layout.TextRunPacker((text, start, end) -> (end - start) * 3, 4));
        pager.append("ab中");
        GlyphProvider provider = key -> new GlyphBitmap(1, 1, 1, 0, 0, new byte[] {(byte) 0x80});
        PageBitmapEncoder encoder = new PageBitmapEncoder(provider, layout, "test", 4, 0);

        byte[] body = encoder.encode(pager.pageAt(0), 1);

        assertNotNull(body);
        assertEquals(2, pager.pageAt(0).getTokens().get(0).getAdvanceCells());
        assertEquals((byte) 0x80, body[16]);
        assertEquals((byte) 0x80, body[17]);
    }

    @Test
    public void punctuationPairAdvancesOneCellInPreview() {
        GridLayout layout = new GridLayout(0, 0, 2, 1, 4, 4, 0, 0, 8, 4);
        GridPager pager = new GridPager(2, 1, 16);
        pager.append("：“中");
        GlyphProvider provider = key -> new GlyphBitmap(4, 1, 1, 0, 0,
                new byte[] {(byte) (key.getText().equals("：“") ? 0xa0 : 0x80)});
        PageBitmapEncoder encoder = new PageBitmapEncoder(provider, layout, "test", 4, 0);

        assertEquals((byte) 0xa8, encoder.encode(pager.pageAt(0), 1)[16]);
    }

    @Test
    public void previewAndTransportShareTheBoundedGlyphLru() {
        GridLayout layout = new GridLayout(0, 0, 2, 2, 4, 4, 0, 0, 8, 8);
        GridPager pager = new GridPager(2, 2, 5);
        pager.append("a");
        AtomicInteger rasterized = new AtomicInteger();
        GlyphProvider provider = key -> {
            rasterized.incrementAndGet();
            return new GlyphBitmap(1, 1, 1, 0, 0, new byte[] {(byte) 0x80});
        };
        RasterGlyphLru<GlyphKey, GlyphBitmap> cache = new RasterGlyphLru<>(16, 128,
                GlyphBitmap::getByteLength);
        PageBitmapEncoder encoder = new PageBitmapEncoder(provider, cache, layout, "test", 4, 0);

        encoder.rasterize(pager.pageAt(0));
        encoder.encode(pager.pageAt(0), 1);
        encoder.rasterize(pager.pageAt(0));

        assertEquals(1, rasterized.get());
        assertEquals(1, cache.size());
        assertEquals(1, cache.totalBytes());
    }
}
