package com.crossmux.textplayer.session;

import com.crossmux.textplayer.protocol.WireProtocol;

public final class ReaderDiagnostics {
    public record Link(int viewportWidth, int viewportHeight, int columns, int rows, int fontSizePx,
            int mtu, int frameBytes, int slots, int poolBytes, int maxTokens, byte timingFlags, boolean pageBurst,
            boolean sdGlyphs) {}

    public record Page(long pushSeq, WireProtocol.MessageType encoding, int bytes, int fragments, long startedMs,
            long encodeStartedMs, long queuedMs, long writesMs, long displayedMs, long occupiedSlots,
            boolean highPrioritySubmitted, WireProtocol.PageTimings reader) {
        public long encodeMs() { return queuedMs - encodeStartedMs; }
        public long totalMs() { return displayedMs - startedMs; }
        public int gattValueBytes() { return bytes + WireProtocol.START_METADATA_SIZE + fragments * WireProtocol.HEADER_SIZE; }
        public double payloadKbps() { return writesMs > 0 ? bytes * 8.0 / writesMs : 0; }
    }

    private Page pending;
    private Page last;

    public Page pending() { return pending; }
    public Page last() { return last; }

    public void reset() {
        pending = null;
        last = null;
    }

    public void cancelPending() { pending = null; }

    public void begin(long id, WireProtocol.MessageType encoding, int bytes, int fragments, long startedMs,
            long encodeStartedMs, long queuedMs) {
        pending = new Page(id, encoding, bytes, fragments, startedMs, encodeStartedMs, queuedMs, -1, -1, 0, false,
                null);
    }

    public void writesComplete(long id, long firstWriteMs, long lastCallbackMs, boolean highPrioritySubmitted) {
        long durationMs = lastCallbackMs - firstWriteMs;
        if (pending != null && pending.pushSeq() == id) {
            pending = withWrites(pending, durationMs, highPrioritySubmitted);
        } else if (last != null && last.pushSeq() == id) {
            last = withWrites(last, durationMs, highPrioritySubmitted);
        }
    }

    private static Page withWrites(Page p, long durationMs, boolean highPrioritySubmitted) {
        return new Page(p.pushSeq(), p.encoding(), p.bytes(), p.fragments(), p.startedMs(), p.encodeStartedMs(),
                p.queuedMs(), durationMs, p.displayedMs(), p.occupiedSlots(), highPrioritySubmitted, p.reader());
    }

    public void displayed(long id, long nowMs, long occupiedSlots, WireProtocol.PageTimings reader) {
        if (pending == null || pending.pushSeq() != id) {
            return;
        }
        Page p = pending;
        last = new Page(id, p.encoding(), p.bytes(), p.fragments(), p.startedMs(), p.encodeStartedMs(), p.queuedMs(),
                p.writesMs(), nowMs, occupiedSlots, p.highPrioritySubmitted(), reader);
        pending = null;
    }
}
