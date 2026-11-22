package com.crossmux.textplayer.glyph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class DeviceGlyphCacheTest {
    @Test
    public void duplicateGlyphsBindOnceAndUseLastOccurrenceForRecency() {
        DeviceGlyphCache<String> cache = new DeviceGlyphCache<>(4, 20);

        DeviceGlyphCache.Plan<String> plan = cache.planGrid(Arrays.asList(
                demand("a", 2), demand("b", 3), demand("a", 2)));

        assertEquals(2, plan.getBindings().size());
        assertEquals(Arrays.asList(1, 0, 1), plan.getSlotTokens());
        assertUncommitted(cache);
        cache.commitDisplayed(plan);
        assertEquals(Arrays.asList("b", "a"), cache.keysOldestFirst());
        assertEquals(1, cache.getRevision());
    }

    @Test
    public void hitPageSendsNoGlyphsButAdvancesRevisionAndRecency() {
        DeviceGlyphCache<String> cache = new DeviceGlyphCache<>(4, 20);
        cache.commitDisplayed(cache.planGrid(Arrays.asList(demand("a", 2), demand("b", 3))));
        long epoch = cache.getEpoch();

        DeviceGlyphCache.Plan<String> plan = cache.planGrid(Arrays.asList(demand("b", 3)));

        assertTrue(plan.getBindings().isEmpty());
        assertEquals(epoch, plan.getTargetEpoch());
        assertEquals(2, plan.getTargetRevision());
        cache.commitDisplayed(plan);
        assertEquals(Arrays.asList("a", "b"), cache.keysOldestFirst());
    }

    @Test
    public void evictionRebindsLruSlotButProtectsWorkingSet() {
        DeviceGlyphCache<String> cache = new DeviceGlyphCache<>(2, 20);
        cache.commitDisplayed(cache.planGrid(Arrays.asList(demand("a", 2), demand("b", 3))));

        DeviceGlyphCache.Plan<String> plan = cache.planGrid(Arrays.asList(
                demand("a", 2), demand("c", 4)));

        assertFalse(plan.isReset());
        assertEquals(1, plan.getBindings().size());
        DeviceGlyphCache.Binding<String> binding = plan.getBindings().get(0);
        assertEquals("c", binding.getKey());
        assertEquals(1, binding.getSlotId());
        cache.commitDisplayed(plan);
        assertEquals(Arrays.asList("a", "c"), cache.keysOldestFirst());
    }

    @Test
    public void byteEvictionCoalescesAdjacentExtents() {
        DeviceGlyphCache<String> cache = new DeviceGlyphCache<>(3, 6);
        cache.commitDisplayed(cache.planGrid(Arrays.asList(
                demand("a", 2), demand("b", 2), demand("c", 2))));

        DeviceGlyphCache.Plan<String> plan = cache.planGrid(Arrays.asList(
                demand("a", 2), demand("d", 4)));

        assertFalse(plan.isReset());
        DeviceGlyphCache.Binding<String> binding = plan.getBindings().get(0);
        assertEquals(2, binding.getPoolOffset());
        assertEquals(4, binding.getByteLength());
        cache.commitDisplayed(plan);
        assertEquals(Arrays.asList("a", "d"), cache.keysOldestFirst());
        assertEquals(6, cache.usedBytes());
    }

    @Test
    public void fragmentedIncrementalCacheResetsWhenWholePageFits() {
        DeviceGlyphCache<String> cache = new DeviceGlyphCache<>(3, 8);
        cache.commitDisplayed(cache.planGrid(Arrays.asList(
                demand("a", 2), demand("b", 2), demand("c", 2))));

        DeviceGlyphCache.Plan<String> plan = cache.planGrid(Arrays.asList(
                demand("a", 2), demand("c", 2), demand("d", 4)));

        assertTrue(plan.isReset());
        assertEquals(3, plan.getBindings().size());
        assertEquals(2, plan.getTargetEpoch());
        assertEquals(1, plan.getTargetRevision());
        cache.commitDisplayed(plan);
        assertEquals(Arrays.asList("a", "c", "d"), cache.keysOldestFirst());
        assertEquals(8, cache.usedBytes());
    }

    @Test
    public void oversizedWorkingSetUsesBitmapWithoutChangingCache() {
        DeviceGlyphCache<String> cache = new DeviceGlyphCache<>(3, 6);
        cache.commitDisplayed(cache.planGrid(Arrays.asList(demand("old", 2))));
        long epoch = cache.getEpoch();
        long revision = cache.getRevision();

        DeviceGlyphCache.Plan<String> plan = cache.planGrid(Arrays.asList(
                demand("a", 4), demand("b", 4)));

        assertEquals(DeviceGlyphCache.Encoding.BITMAP, plan.getEncoding());
        cache.commitDisplayed(plan);
        assertEquals(epoch, cache.getEpoch());
        assertEquals(revision, cache.getRevision());
        assertEquals(Arrays.asList("old"), cache.keysOldestFirst());
    }

    @Test
    public void stalePlanCannotCommitTwice() {
        DeviceGlyphCache<String> cache = new DeviceGlyphCache<>(2, 20);
        DeviceGlyphCache.Plan<String> first = cache.planGrid(Arrays.asList(demand("a", 2)));
        cache.commitDisplayed(first);

        try {
            cache.commitDisplayed(first);
            fail("Second commit must be rejected");
        } catch (IllegalStateException expected) {
            assertEquals(1, cache.getRevision());
        }
    }

    @Test
    public void protocolCountersStopAtUint32Limit() throws Exception {
        DeviceGlyphCache<String> revisionCache = new DeviceGlyphCache<>(2, 20);
        setCounter(revisionCache, "revision", 0xffff_ffffL);
        try {
            revisionCache.planGrid(Arrays.asList(demand("a", 2)));
            fail("Revision must not wrap");
        } catch (IllegalStateException expected) {
            assertEquals(0xffff_ffffL, revisionCache.getRevision());
        }

        DeviceGlyphCache<String> epochCache = new DeviceGlyphCache<>(3, 8);
        setCounter(epochCache, "epoch", 0xffff_ffffL);
        epochCache.commitDisplayed(epochCache.planGrid(
                Arrays.asList(demand("a", 2), demand("b", 2), demand("c", 2))));
        try {
            epochCache.planGrid(Arrays.asList(demand("a", 2), demand("c", 2), demand("d", 4)));
            fail("Epoch must not wrap");
        } catch (IllegalStateException expected) {
            assertEquals(0xffff_ffffL, epochCache.getEpoch());
        }
    }

    private void setCounter(DeviceGlyphCache<String> cache, String name, long value) throws Exception {
        java.lang.reflect.Field field = DeviceGlyphCache.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(cache, value);
    }

    private DeviceGlyphCache.Demand<String> demand(String key, int bytes) {
        return new DeviceGlyphCache.Demand<>(key, bytes);
    }

    private void assertUncommitted(DeviceGlyphCache<String> cache) {
        assertEquals(0, cache.size());
        assertEquals(0, cache.getRevision());
    }
}
