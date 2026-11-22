package com.crossmux.textplayer.glyph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

public final class RasterGlyphLruTest {
    @Test
    public void getPromotesEntryBeforeEviction() {
        RasterGlyphLru<String, String> cache = newCache(3, 100);
        cache.put("a", "1");
        cache.put("b", "22");
        cache.put("c", "333");

        assertEquals("1", cache.get("a"));
        assertEquals(RasterGlyphLru.PutResult.EVICTED_TO_FIT, cache.put("d", "4444"));
        assertEquals(Arrays.asList("c", "a", "d"), cache.keysOldestFirst());
        assertNull(cache.get("b"));
    }

    @Test
    public void byteLimitEvictsMultipleEntries() {
        RasterGlyphLru<String, String> cache = newCache(8, 5);
        cache.put("a", "11");
        cache.put("b", "22");
        cache.put("c", "333");

        assertEquals(RasterGlyphLru.PutResult.EVICTED_TO_FIT, cache.put("d", "4444"));
        assertEquals(Arrays.asList("d"), cache.keysOldestFirst());
        assertEquals(4, cache.totalBytes());
    }

    @Test
    public void updateAdjustsBytesAndRecency() {
        RasterGlyphLru<String, String> cache = newCache(2, 100);
        cache.put("a", "1");
        cache.put("b", "22");

        assertEquals(RasterGlyphLru.PutResult.UPDATED, cache.put("a", "333"));
        assertEquals(Arrays.asList("b", "a"), cache.keysOldestFirst());
        assertEquals(5, cache.totalBytes());
    }

    @Test
    public void oversizedValueDoesNotDamageExistingCache() {
        RasterGlyphLru<String, String> cache = newCache(2, 4);
        cache.put("a", "11");

        assertEquals(RasterGlyphLru.PutResult.OVERSIZED, cache.put("a", "12345"));
        assertEquals("11", cache.get("a"));
        assertEquals(2, cache.totalBytes());
    }

    private RasterGlyphLru<String, String> newCache(int entries, int bytes) {
        return new RasterGlyphLru<>(entries, bytes, String::length);
    }
}
