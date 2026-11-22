package com.crossmux.textplayer.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

public final class WireProtocol {
    public static final int VERSION = 0;
    public static final int HEADER_SIZE = 14;
    public static final int START_METADATA_SIZE = 6;
    public static final int START = 1;
    public static final int END = 2;
    public static final int MAX_OBJECT_BYTES = 65_535;
    public static final int MAX_FRAME_BYTES = 240;
    public static final int MAX_PAYLOAD_BYTES = MAX_FRAME_BYTES - HEADER_SIZE;
    public static final int CAP_PAGE_TIMINGS = 1 << 2;
    public static final int CAP_PIPELINE_TIMINGS = 1 << 3;
    public static final int CAP_CONNECTION_INTERVAL = 1 << 4;
    public static final int CAP_PAGE_BURST = 1 << 5;
    public static final int CAP_SD_GLYPHS = 1 << 6;
    public static final int CAP_GLYPH_TURN_TIMINGS = 1 << 7;
    public static final int SESSION_PAGE_BURST = 1 << 3;
    public static final int SESSION_SD_GLYPHS = 1 << 4;
    public static final int SESSION_GLYPH_TURN_TIMINGS = 1 << 5;
    public static final int SESSION_PREPARE_PAGES = 1 << 6;
    public static final int HELLO_EXTENSIONS = 1;
    public static final int CAP_PREPARE_PAGES = 1;
    public static final int NAV_REQUEST_BYTES = 10;
    public static final int MAX_PREPARED_PAGE_BYTES = 2048;
    public static final int MAX_PREPARED_PAGE_FRAMES = 12;
    public static final int MAX_FONT_CHUNK_BYTES = 8192;
    public static final int FONT_STATUS_BYTES = 5;
    public static final int ERROR_FONT_REQUIRED = 34;
    public static final int PAGE_BURST_FRAMES = 6;
    public static final int PAGE_CREDIT_BYTES = Short.BYTES;
    public static final int SESSION_PAGE_TIMINGS = 1;
    public static final int SESSION_PIPELINE_TIMINGS = 1 << 1;
    public static final int SESSION_CONNECTION_INTERVAL = 1 << 2;
    private static final byte SESSION_TIMING_FLAGS = SESSION_PAGE_TIMINGS | SESSION_PIPELINE_TIMINGS;
    public static final int START_SESSION_BYTES = 28;
    public static final int DISPLAYED_BYTES = 20;
    public static final int PROFILED_DISPLAYED_BYTES = DISPLAYED_BYTES + 5 * Integer.BYTES;
    public static final int PIPELINE_DISPLAYED_BYTES = PROFILED_DISPLAYED_BYTES + 3 * Integer.BYTES;
    public static final int LINK_DISPLAYED_BYTES = PIPELINE_DISPLAYED_BYTES + 2 * Short.BYTES;
    public static final int GLYPH_TURN_DISPLAYED_BYTES = LINK_DISPLAYED_BYTES + 2 * Integer.BYTES + 2 * Short.BYTES;
    private static final long NO_TURN_TIMING = 0xffff_ffffL;

    public static byte timingFlags(int capabilities, boolean sdGlyphs) {
        if ((capabilities & CAP_PAGE_TIMINGS) == 0) {
            return 0;
        }
        if ((capabilities & CAP_PIPELINE_TIMINGS) == 0) {
            return SESSION_PAGE_TIMINGS;
        }
        if ((capabilities & CAP_CONNECTION_INTERVAL) == 0) {
            return SESSION_TIMING_FLAGS;
        }
        int glyphTurn = sdGlyphs && (capabilities & (CAP_SD_GLYPHS | CAP_GLYPH_TURN_TIMINGS))
                == (CAP_SD_GLYPHS | CAP_GLYPH_TURN_TIMINGS) ? SESSION_GLYPH_TURN_TIMINGS : 0;
        return (byte) (SESSION_TIMING_FLAGS | SESSION_CONNECTION_INTERVAL | glyphTurn);
    }

    public static int displayedBytes(int flags) {
        if ((flags & SESSION_GLYPH_TURN_TIMINGS) != 0) {
            return GLYPH_TURN_DISPLAYED_BYTES;
        }
        if ((flags & SESSION_CONNECTION_INTERVAL) != 0) {
            return LINK_DISPLAYED_BYTES;
        }
        if ((flags & SESSION_PIPELINE_TIMINGS) != 0) {
            return PIPELINE_DISPLAYED_BYTES;
        }
        return (flags & SESSION_PAGE_TIMINGS) != 0 ? PROFILED_DISPLAYED_BYTES : DISPLAYED_BYTES;
    }

    public static boolean matchesDisplayed(byte[] body, int flags, long pushSeq, long pageId, long epoch,
            long revision) {
        return body.length == displayedBytes(flags) && readLe32(body, 0) == pushSeq && readLe32(body, 4) == pageId
                && readLe32(body, 8) == epoch && readLe32(body, 12) == revision;
    }

    public record ReceiveTimings(long arrivalSpanMs, long stagingUs, long lastArrivalToStagedMs) {}

    public record ConnectionIntervals(int firstUnits, int lastUnits) {
        public double firstMs() { return firstUnits * 1.25; }
        public double lastMs() { return lastUnits * 1.25; }
    }

    public record GlyphTurnTimings(long turnMs, long glyphReadUs, int cacheHits, int cacheMisses) {
        public boolean hasTurn() { return turnMs != NO_TURN_TIMING; }
    }

    public record PageTimings(long receiveMs, long prepareMs, long renderQueueMs, long drawMs, long refreshMs,
            ReceiveTimings rx, ConnectionIntervals intervals, GlyphTurnTimings glyphTurn) {
        public PageTimings(long receiveMs, long prepareMs, long renderQueueMs, long drawMs, long refreshMs) {
            this(receiveMs, prepareMs, renderQueueMs, drawMs, refreshMs, null, null, null);
        }

        public long stagedToRefreshMs() {
            return prepareMs + renderQueueMs + drawMs + refreshMs;
        }

        public static PageTimings fromDisplayed(byte[] body) {
            int bytes = body.length;
            if (bytes != PROFILED_DISPLAYED_BYTES && bytes != PIPELINE_DISPLAYED_BYTES
                    && bytes != LINK_DISPLAYED_BYTES && bytes != GLYPH_TURN_DISPLAYED_BYTES) {
                return null;
            }
            ReceiveTimings rx = bytes >= PIPELINE_DISPLAYED_BYTES
                    ? new ReceiveTimings(readLe32(body, 40), readLe32(body, 44), readLe32(body, 48)) : null;
            ConnectionIntervals intervals = bytes >= LINK_DISPLAYED_BYTES
                    ? new ConnectionIntervals(readLe16(body, 52), readLe16(body, 54)) : null;
            int glyphOffset = LINK_DISPLAYED_BYTES;
            GlyphTurnTimings glyphTurn = bytes == GLYPH_TURN_DISPLAYED_BYTES
                    ? new GlyphTurnTimings(readLe32(body, glyphOffset), readLe32(body, glyphOffset + 4),
                            readLe16(body, glyphOffset + 8), readLe16(body, glyphOffset + 10)) : null;
            return new PageTimings(readLe32(body, 20), readLe32(body, 24), readLe32(body, 28),
                    readLe32(body, 32), readLe32(body, 36), rx, intervals, glyphTurn);
        }
    }

    public static int frameCount(int bodyBytes, int payloadLimit) {
        return (bodyBytes + START_METADATA_SIZE + payloadLimit - 1) / payloadLimit;
    }

    public static int gattPayloadLimit(int mtu) {
        return Math.max(0, Math.min(MAX_FRAME_BYTES, mtu - 3) - HEADER_SIZE);
    }

    public enum MessageType {
        HELLO(1),
        CAPABILITIES(2),
        START_SESSION(3),
        PAGE_GRID(4),
        PAGE_BITMAP(5),
        REQUEST_PAGE(6),
        DISPLAYED(7),
        NO_PAGE(8),
        ERROR(9),
        PAIRING_RESPONSE(10),
        PAGE_CREDIT(11),
        PAGE_GLYPHS(12),
        FONT_QUERY(13),
        FONT_BEGIN(14),
        FONT_CHUNK(15),
        FONT_END(16),
        FONT_STATUS(17),
        PAGE_PREPARED(18);

        final int code;

        MessageType(int code) {
            this.code = code;
        }

        public boolean isPage() {
            return this == PAGE_GRID || this == PAGE_BITMAP || this == PAGE_GLYPHS;
        }

        public static MessageType fromCode(int code) {
            for (MessageType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
            return null;
        }
    }

    public static final class Frame {
        public MessageType type;
        public int flags;
        public long sessionId;
        public long objectId;
        public int offset;
        public byte[] payload;
    }

    public enum AssemblyStatus {
        NEED_MORE,
        COMPLETE,
        ERROR
    }

    public static final class AssemblyResult {
        public final AssemblyStatus status;
        public final MessageType type;
        public final long sessionId;
        public final long objectId;
        public final int bodyLength;
        public final long expectedCrc;

        private AssemblyResult(AssemblyStatus status, MessageType type, long sessionId, long objectId,
                int bodyLength, long expectedCrc) {
            this.status = status;
            this.type = type;
            this.sessionId = sessionId;
            this.objectId = objectId;
            this.bodyLength = bodyLength;
            this.expectedCrc = expectedCrc;
        }
    }

    public interface Sink {
        boolean write(byte[] data, int offset, int length);
    }

    public static final class ObjectAssembler {
        private MessageType type = MessageType.ERROR;
        private long sessionId;
        private long objectId;
        private long expectedCrc;
        private int total;
        private int received;
        private CRC32 crc;
        private boolean active;

        public AssemblyResult receive(Frame frame, Sink sink) {
            if ((frame.flags & START) != 0) {
                return start(frame, sink);
            }
            if (!active || frame.type != type || frame.sessionId != sessionId || frame.objectId != objectId
                    || frame.offset != received || (frame.payload.length == 0 && (frame.flags & END) == 0)) {
                return fail();
            }
            return append(frame, sink, 0);
        }

        public boolean isActive() {
            return active;
        }

        public void reset() {
            active = false;
            received = 0;
            total = 0;
            crc = null;
        }

        private AssemblyResult start(Frame frame, Sink sink) {
            if (active || frame.offset != 0 || frame.payload.length < START_METADATA_SIZE) {
                return fail();
            }
            int newTotal = (int) readLe16(frame.payload, 0);
            if (newTotal > MAX_OBJECT_BYTES) {
                return fail();
            }
            type = frame.type;
            sessionId = frame.sessionId;
            objectId = frame.objectId;
            expectedCrc = readLe32(frame.payload, 2);
            total = newTotal;
            received = 0;
            crc = new CRC32();
            active = true;
            return append(frame, sink, START_METADATA_SIZE);
        }

        private AssemblyResult append(Frame frame, Sink sink, int metadataSize) {
            int bodyBytes = frame.payload.length - metadataSize;
            if (bodyBytes > total - received || !sink.write(frame.payload, metadataSize, bodyBytes)) {
                return fail();
            }
            crc.update(frame.payload, metadataSize, bodyBytes);
            received += bodyBytes;
            if ((frame.flags & END) == 0) {
                return result(AssemblyStatus.NEED_MORE);
            }
            if (received != total || crc.getValue() != expectedCrc) {
                return fail();
            }
            active = false;
            return result(AssemblyStatus.COMPLETE);
        }

        private AssemblyResult result(AssemblyStatus status) {
            return new AssemblyResult(status, type, sessionId, objectId, received, expectedCrc);
        }

        private AssemblyResult fail() {
            active = false;
            return result(AssemblyStatus.ERROR);
        }
    }

    private WireProtocol() {
    }

    public static int readLe16(byte[] data, int offset) {
        return (data[offset] & 0xff) | (data[offset + 1] & 0xff) << 8;
    }

    public static long readLe32(byte[] data, int offset) {
        return ((long) data[offset] & 0xff) | ((long) data[offset + 1] & 0xff) << 8
                | ((long) data[offset + 2] & 0xff) << 16 | ((long) data[offset + 3] & 0xff) << 24;
    }

    public static void writeLe16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    public static void writeLe32(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    public static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        return crc.getValue();
    }

    public static void writeFrameHeader(byte[] data, MessageType type, int flags, long sessionId,
            long objectId, int offset) {
        data[0] = VERSION;
        data[1] = (byte) type.code;
        data[2] = (byte) flags;
        data[3] = 0;
        writeLe32(data, 4, sessionId);
        writeLe32(data, 8, objectId);
        writeLe16(data, 12, offset);
    }

    public static Frame parseFrame(byte[] data) {
        if (data == null || data.length < HEADER_SIZE || data[0] != VERSION || data[1] == 0
                || (data[2] & ~(START | END)) != 0 || data[3] != 0) {
            return null;
        }
        MessageType type = MessageType.fromCode(data[1] & 0xff);
        if (type == null) {
            return null;
        }
        Frame frame = new Frame();
        frame.type = type;
        frame.flags = data[2] & 0xff;
        frame.sessionId = readLe32(data, 4);
        frame.objectId = readLe32(data, 8);
        frame.offset = readLe16(data, 12);
        frame.payload = Arrays.copyOfRange(data, HEADER_SIZE, data.length);
        return frame;
    }

    public static List<byte[]> createFrames(MessageType type, long sessionId, long objectId,
            byte[] body, int payloadLimit) {
        if (body == null || body.length > MAX_OBJECT_BYTES || payloadLimit < START_METADATA_SIZE
                || payloadLimit > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid object or payload limit");
        }
        ArrayList<byte[]> frames = new ArrayList<>();
        int firstBytes = Math.min(body.length, payloadLimit - START_METADATA_SIZE);
        int firstFlags = START | (firstBytes == body.length ? END : 0);
        byte[] first = new byte[HEADER_SIZE + START_METADATA_SIZE + firstBytes];
        writeFrameHeader(first, type, firstFlags, sessionId, objectId, 0);
        writeLe16(first, HEADER_SIZE, body.length);
        writeLe32(first, HEADER_SIZE + 2, crc32(body));
        System.arraycopy(body, 0, first, HEADER_SIZE + START_METADATA_SIZE, firstBytes);
        frames.add(first);

        int sent = firstBytes;
        while (sent < body.length) {
            int chunk = Math.min(body.length - sent, payloadLimit);
            int flags = sent + chunk == body.length ? END : 0;
            byte[] frame = new byte[HEADER_SIZE + chunk];
            writeFrameHeader(frame, type, flags, sessionId, objectId, sent);
            System.arraycopy(body, sent, frame, HEADER_SIZE, chunk);
            frames.add(frame);
            sent += chunk;
        }
        return frames;
    }
}
