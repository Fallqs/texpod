package com.crossmux.textplayer.transport;

import com.crossmux.textplayer.protocol.WireProtocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Main-thread history of GATT value metadata; it never retains the values themselves. */
public final class TrafficMonitor {
    public static final int CAPACITY = 512;
    static final int NOT_SUBMITTED = -1;
    static final int NO_CALLBACK = -2;

    public record Packet(long sequence, boolean tx, long elapsedMs, int bytes, int typeCode,
            long objectId, int offset, long writeToCallbackMs, long dispatchMs, int gattStatus) {
        public boolean notSubmitted() { return tx && gattStatus == NOT_SUBMITTED; }
        public boolean noCallback() { return tx && gattStatus == NO_CALLBACK; }
    }

    public record Snapshot(List<Packet> packets, long total) {
        public long omitted() { return total - packets.size(); }
    }

    private final ArrayDeque<Packet> packets = new ArrayDeque<>(CAPACITY);
    private long originMs;
    private long total;
    private long revision;

    public void reset(long nowMs) {
        packets.clear();
        originMs = nowMs;
        total = 0;
        revision++;
    }

    public long revision() { return revision; }

    public Snapshot latest(int limit) {
        int count = Math.min(limit, packets.size());
        var result = new ArrayList<Packet>(count);
        var newest = packets.descendingIterator();
        while (result.size() < count) {
            result.add(newest.next());
        }
        Collections.reverse(result);
        return new Snapshot(result, total);
    }

    void written(byte[] frame, long startedMs, long callbackMs, long handledMs, int status) {
        record(frame, true, callbackMs, callbackMs - startedMs, handledMs - callbackMs, status);
    }

    void unfinishedWrite(byte[] frame, long nowMs, int status) {
        record(frame, true, nowMs, -1, -1, status);
    }

    void received(byte[] frame, long callbackMs, long handledMs) {
        record(frame, false, callbackMs, -1, handledMs - callbackMs, 0);
    }

    private void record(byte[] frame, boolean tx, long eventMs, long writeToCallbackMs, long dispatchMs,
            int status) {
        boolean hasHeader = frame.length >= WireProtocol.HEADER_SIZE && frame[0] == WireProtocol.VERSION;
        if (packets.size() == CAPACITY) {
            packets.removeFirst();
        }
        packets.addLast(new Packet(++total, tx, eventMs - originMs, frame.length,
                hasHeader ? frame[1] & 0xff : -1, hasHeader ? WireProtocol.readLe32(frame, 8) : -1,
                hasHeader ? WireProtocol.readLe16(frame, 12) : -1, writeToCallbackMs, dispatchMs, status));
        revision++;
    }
}
