package com.crossmux.textplayer.glyph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RasterGlyphLru<K, V> {
    public interface ValueSizer<V> {
        int sizeOf(V value);
    }

    public enum PutResult {
        INSERTED,
        UPDATED,
        EVICTED_TO_FIT,
        OVERSIZED
    }

    private static final class Entry<V> {
        final V value;
        final int byteCost;

        Entry(V value, int byteCost) {
            this.value = value;
            this.byteCost = byteCost;
        }
    }

    private final int maxEntries;
    private final int maxBytes;
    private final ValueSizer<V> sizer;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private int totalBytes;

    public RasterGlyphLru(int maxEntries, int maxBytes, ValueSizer<V> sizer) {
        if (maxEntries <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("Cache limits must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.sizer = Objects.requireNonNull(sizer);
    }

    public synchronized V get(K key) {
        Entry<V> entry = entries.get(key);
        return entry == null ? null : entry.value;
    }

    public synchronized PutResult put(K key, V value) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        int byteCost = sizer.sizeOf(value);
        if (byteCost < 0) {
            throw new IllegalArgumentException("Negative value size");
        }
        if (byteCost > maxBytes) {
            return PutResult.OVERSIZED;
        }

        Entry<V> previous = entries.remove(key);
        boolean evicted = false;
        if (previous != null) {
            totalBytes -= previous.byteCost;
        }
        entries.put(key, new Entry<>(value, byteCost));
        totalBytes += byteCost;

        while (entries.size() > maxEntries || totalBytes > maxBytes) {
            Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
            Map.Entry<K, Entry<V>> eldest = iterator.next();
            totalBytes -= eldest.getValue().byteCost;
            iterator.remove();
            evicted = true;
        }
        if (evicted) {
            return PutResult.EVICTED_TO_FIT;
        }
        return previous == null ? PutResult.INSERTED : PutResult.UPDATED;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int totalBytes() {
        return totalBytes;
    }

    public synchronized List<K> keysOldestFirst() {
        return Collections.unmodifiableList(new ArrayList<>(entries.keySet()));
    }

    public synchronized void clear() {
        entries.clear();
        totalBytes = 0;
    }
}
