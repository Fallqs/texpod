package com.crossmux.textplayer.glyph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

public final class DeviceGlyphCache<K> {
    public enum Encoding {
        GRID,
        BITMAP
    }

    public static final class Demand<K> {
        private final K key;
        private final int byteLength;

        public Demand(K key, int byteLength) {
            this.key = Objects.requireNonNull(key);
            if (byteLength <= 0) {
                throw new IllegalArgumentException("Glyph bytes must be positive");
            }
            this.byteLength = byteLength;
        }

        public K getKey() {
            return key;
        }

        public int getByteLength() {
            return byteLength;
        }
    }

    public static final class Binding<K> {
        private final K key;
        private final int slotId;
        private final int poolOffset;
        private final int byteLength;

        private Binding(K key, int slotId, int poolOffset, int byteLength) {
            this.key = key;
            this.slotId = slotId;
            this.poolOffset = poolOffset;
            this.byteLength = byteLength;
        }

        public K getKey() {
            return key;
        }

        public int getSlotId() {
            return slotId;
        }

        public int getPoolOffset() {
            return poolOffset;
        }

        public int getByteLength() {
            return byteLength;
        }
    }

    public static final class Plan<K> {
        private final DeviceGlyphCache<K> owner;
        private final Encoding encoding;
        private final long baseEpoch;
        private final long baseRevision;
        private final long targetEpoch;
        private final long targetRevision;
        private final boolean reset;
        private final List<Binding<K>> bindings;
        private final List<Integer> slotTokens;
        private final LinkedHashMap<K, Entry> candidateEntries;

        private Plan(DeviceGlyphCache<K> owner, Encoding encoding, long baseEpoch, long baseRevision,
                long targetEpoch, long targetRevision, boolean reset, List<Binding<K>> bindings,
                List<Integer> slotTokens, LinkedHashMap<K, Entry> candidateEntries) {
            this.owner = owner;
            this.encoding = encoding;
            this.baseEpoch = baseEpoch;
            this.baseRevision = baseRevision;
            this.targetEpoch = targetEpoch;
            this.targetRevision = targetRevision;
            this.reset = reset;
            this.bindings = Collections.unmodifiableList(new ArrayList<>(bindings));
            this.slotTokens = Collections.unmodifiableList(new ArrayList<>(slotTokens));
            this.candidateEntries = candidateEntries;
        }

        public Encoding getEncoding() {
            return encoding;
        }

        public long getBaseEpoch() {
            return baseEpoch;
        }

        public long getBaseRevision() {
            return baseRevision;
        }

        public long getTargetEpoch() {
            return targetEpoch;
        }

        public long getTargetRevision() {
            return targetRevision;
        }

        public boolean isReset() {
            return reset;
        }

        public List<Binding<K>> getBindings() {
            return bindings;
        }

        public List<Integer> getSlotTokens() {
            return slotTokens;
        }
    }

    private static final class Entry {
        final int slotId;
        final int poolOffset;
        final int byteLength;

        Entry(int slotId, int poolOffset, int byteLength) {
            this.slotId = slotId;
            this.poolOffset = poolOffset;
            this.byteLength = byteLength;
        }
    }

    private static final class Attempt<K> {
        final LinkedHashMap<K, Entry> entries;
        final ArrayList<Binding<K>> bindings = new ArrayList<>();
        final TreeSet<Integer> freeSlots = new TreeSet<>();
        final TreeMap<Integer, Integer> freeExtents = new TreeMap<>();
        final int maxSlots;
        final int maxBytes;

        Attempt(LinkedHashMap<K, Entry> initialEntries, int maxSlots, int maxBytes) {
            this.maxSlots = maxSlots;
            this.maxBytes = maxBytes;
            entries = new LinkedHashMap<>(initialEntries);
            rebuildFreeSpace();
        }

        boolean insert(K key, int byteLength, java.util.Set<K> protectedKeys) {
            while (freeSlots.isEmpty() || findExtent(byteLength) == null) {
                K evictedKey = null;
                for (K candidateKey : entries.keySet()) {
                    if (!protectedKeys.contains(candidateKey)) {
                        evictedKey = candidateKey;
                        break;
                    }
                }
                if (evictedKey == null) {
                    return false;
                }
                Entry evicted = entries.remove(evictedKey);
                freeSlots.add(evicted.slotId);
                addFreeExtent(evicted.poolOffset, evicted.byteLength);
            }

            int slotId = freeSlots.pollFirst();
            int poolOffset = findExtent(byteLength);
            removeFreeExtent(poolOffset, byteLength);
            Entry inserted = new Entry(slotId, poolOffset, byteLength);
            entries.put(key, inserted);
            bindings.add(new Binding<>(key, slotId, poolOffset, byteLength));
            return true;
        }

        private Integer findExtent(int byteLength) {
            for (Map.Entry<Integer, Integer> extent : freeExtents.entrySet()) {
                if (extent.getValue() >= byteLength) {
                    return extent.getKey();
                }
            }
            return null;
        }

        private void removeFreeExtent(int offset, int byteLength) {
            int remaining = freeExtents.remove(offset) - byteLength;
            if (remaining > 0) {
                freeExtents.put(offset + byteLength, remaining);
            }
        }

        private void addFreeExtent(int offset, int byteLength) {
            int start = offset;
            int end = offset + byteLength;
            Map.Entry<Integer, Integer> previous = freeExtents.floorEntry(start);
            if (previous != null && previous.getKey() + previous.getValue() == start) {
                start = previous.getKey();
                end = Math.max(end, start + previous.getValue());
                freeExtents.remove(previous.getKey());
            }
            Map.Entry<Integer, Integer> next = freeExtents.ceilingEntry(start);
            if (next != null && next.getKey() == end) {
                end += next.getValue();
                freeExtents.remove(next.getKey());
            }
            freeExtents.put(start, end - start);
        }

        private void rebuildFreeSpace() {
            freeSlots.clear();
            freeExtents.clear();
            for (int slot = 0; slot < maxSlots; slot++) {
                freeSlots.add(slot);
            }
            int previousEnd = 0;
            ArrayList<Entry> sorted = new ArrayList<>(entries.values());
            sorted.sort((left, right) -> Integer.compare(left.poolOffset, right.poolOffset));
            for (Entry entry : sorted) {
                if (entry.poolOffset < previousEnd || entry.poolOffset + entry.byteLength > maxBytes) {
                    throw new IllegalStateException("Invalid mirrored bitmap extent");
                }
                if (entry.poolOffset > previousEnd) {
                    freeExtents.put(previousEnd, entry.poolOffset - previousEnd);
                }
                previousEnd = entry.poolOffset + entry.byteLength;
                freeSlots.remove(entry.slotId);
            }
            if (previousEnd < maxBytes) {
                freeExtents.put(previousEnd, maxBytes - previousEnd);
            }
        }
    }

    private final int maxSlots;
    private final int maxBytes;
    private long epoch = 1;
    private long revision;
    private LinkedHashMap<K, Entry> entries = new LinkedHashMap<>();

    public DeviceGlyphCache(int maxSlots, int maxBytes) {
        if (maxSlots <= 0 || maxSlots > 32_768 || maxBytes <= 0) {
            throw new IllegalArgumentException("Invalid device cache limits");
        }
        this.maxSlots = maxSlots;
        this.maxBytes = maxBytes;
    }

    public synchronized Plan<K> planGrid(List<Demand<K>> tokenDemands) {
        LinkedHashMap<K, Demand<K>> lastOccurrenceOrder = new LinkedHashMap<>();
        for (Demand<K> demand : tokenDemands) {
            Demand<K> previous = lastOccurrenceOrder.remove(demand.getKey());
            if (previous != null && previous.byteLength != demand.byteLength) {
                throw new IllegalArgumentException("GlyphKey cannot change bitmap size");
            }
            lastOccurrenceOrder.put(demand.getKey(), demand);
        }

        Attempt<K> incremental = new Attempt<>(entries, maxSlots, maxBytes);
        if (populate(incremental, lastOccurrenceOrder)) {
            return gridPlan(incremental, lastOccurrenceOrder, tokenDemands, false, epoch,
                    nextNumber(revision, "revision"));
        }

        Attempt<K> reset = new Attempt<>(new LinkedHashMap<K, Entry>(), maxSlots, maxBytes);
        if (populate(reset, lastOccurrenceOrder)) {
            return gridPlan(reset, lastOccurrenceOrder, tokenDemands, true, nextNumber(epoch, "epoch"), 1);
        }
        return planBitmap();
    }

    public synchronized Plan<K> planBitmap() {
        return new Plan<>(this, Encoding.BITMAP, epoch, revision, epoch, revision, false,
                Collections.emptyList(), Collections.emptyList(), null);
    }

    public synchronized void commitDisplayed(Plan<K> plan) {
        if (plan.owner != this || plan.baseEpoch != epoch || plan.baseRevision != revision) {
            throw new IllegalStateException("Stale or foreign cache plan");
        }
        switch (plan.encoding) {
            case GRID:
                entries = new LinkedHashMap<>(plan.candidateEntries);
                epoch = plan.targetEpoch;
                revision = plan.targetRevision;
                break;
            case BITMAP:
                break;
        }
    }

    public synchronized long getEpoch() {
        return epoch;
    }

    public synchronized long getRevision() {
        return revision;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int usedBytes() {
        int bytes = 0;
        for (Entry entry : entries.values()) {
            bytes += entry.byteLength;
        }
        return bytes;
    }

    public synchronized List<K> keysOldestFirst() {
        return Collections.unmodifiableList(new ArrayList<>(entries.keySet()));
    }

    private Plan<K> gridPlan(Attempt<K> attempt, LinkedHashMap<K, Demand<K>> lastOccurrenceOrder,
            List<Demand<K>> tokenDemands, boolean reset, long targetEpoch, long targetRevision) {
        LinkedHashMap<K, Entry> ordered = new LinkedHashMap<>();
        for (Map.Entry<K, Entry> existing : attempt.entries.entrySet()) {
            if (!lastOccurrenceOrder.containsKey(existing.getKey())) {
                ordered.put(existing.getKey(), existing.getValue());
            }
        }
        for (K key : lastOccurrenceOrder.keySet()) {
            ordered.put(key, attempt.entries.get(key));
        }

        ArrayList<Integer> slotTokens = new ArrayList<>(tokenDemands.size());
        for (Demand<K> demand : tokenDemands) {
            slotTokens.add(ordered.get(demand.getKey()).slotId);
        }
        return new Plan<>(this, Encoding.GRID, epoch, revision, targetEpoch, targetRevision, reset,
                attempt.bindings, slotTokens, ordered);
    }

    private boolean populate(Attempt<K> attempt, LinkedHashMap<K, Demand<K>> lastOccurrenceOrder) {
        for (Demand<K> demand : lastOccurrenceOrder.values()) {
            if (attempt.entries.containsKey(demand.getKey())) {
                Entry existing = attempt.entries.get(demand.getKey());
                if (existing.byteLength != demand.byteLength) {
                    throw new IllegalArgumentException("GlyphKey cannot change bitmap size");
                }
                continue;
            }
            if (!attempt.insert(demand.getKey(), demand.byteLength, lastOccurrenceOrder.keySet())) {
                return false;
            }
        }
        return true;
    }

    private static long nextNumber(long value, String name) {
        if (value >= 0xffff_ffffL) {
            throw new IllegalStateException("Cache " + name + " exhausted; start a new session");
        }
        return value + 1;
    }
}
