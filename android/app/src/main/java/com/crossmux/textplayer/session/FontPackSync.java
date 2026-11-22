package com.crossmux.textplayer.session;

import com.crossmux.textplayer.glyph.FontPackWriter;
import com.crossmux.textplayer.protocol.WireProtocol;
import com.crossmux.textplayer.protocol.WireProtocol.MessageType;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

final class FontPackSync implements Closeable {
    public enum Phase { NEW, QUERYING, NEED_PACK, BEGINNING, UPLOADING, INSTALLING, READY, CLOSED }

    public record Command(MessageType type, long id, byte[] body) {}

    private static final int READY = 0;
    private static final int MISSING = 1;
    private static final int UPLOADING = 2;
    private static final int CHUNK_HEADER_BYTES = Integer.BYTES;
    private static final int BEGIN_BYTES = FontPackWriter.GENERATION_BYTES + 2 * Integer.BYTES;

    private final byte[] generation;
    private final int chunkLimit;
    private Phase phase = Phase.NEW;
    private long lastId;
    private DataInputStream pack;
    private int totalBytes;
    private int confirmedBytes;
    private int expectedBytes;

    public FontPackSync(byte[] generation, int maxObjectBytes) {
        this(generation, maxObjectBytes, 0);
    }

    FontPackSync(byte[] generation, int maxObjectBytes, long previousId) {
        lastId = previousId;
        if (generation.length != FontPackWriter.GENERATION_BYTES || Arrays.equals(generation, new byte[generation.length])
                || maxObjectBytes < BEGIN_BYTES || maxObjectBytes > WireProtocol.MAX_OBJECT_BYTES) {
            throw new IllegalArgumentException("Invalid font transfer parameters");
        }
        this.generation = generation.clone();
        chunkLimit = Math.min(WireProtocol.MAX_FONT_CHUNK_BYTES, maxObjectBytes - CHUNK_HEADER_BYTES);
    }

    public Phase phase() { return phase; }
    long lastId() { return lastId; }
    public int totalBytes() { return totalBytes; }
    public int confirmedBytes() { return confirmedBytes; }

    public boolean waitingFor(long id) {
        return id == lastId && switch (phase) {
            case QUERYING, BEGINNING, UPLOADING, INSTALLING -> true;
            default -> false;
        };
    }

    public Command start(boolean force) {
        if (phase != Phase.NEW) throw new IllegalStateException("Font sync already started");
        if (force) {
            phase = Phase.NEED_PACK;
            return null;
        }
        return issue(Phase.QUERYING, MessageType.FONT_QUERY, generation.clone());
    }

    // Takes ownership of source on success.
    public Command beginUpload(FontPackWriter.Result result, InputStream source) {
        if (phase != Phase.NEED_PACK) throw new IllegalStateException("Not expecting a font pack");
        if (result.bytes() <= 0 || result.bytes() > FontPackWriter.MAX_PACK_BYTES) {
            throw new IllegalArgumentException("Invalid font pack length");
        }
        pack = new DataInputStream(source);
        totalBytes = result.bytes();
        byte[] body = Arrays.copyOf(generation, BEGIN_BYTES);
        WireProtocol.writeLe32(body, generation.length, totalBytes);
        WireProtocol.writeLe32(body, generation.length + Integer.BYTES, result.crc());
        return issue(Phase.BEGINNING, MessageType.FONT_BEGIN, body);
    }

    public Command onStatus(long sessionId, long id, byte[] body) throws IOException {
        if (sessionId != 0 || !waitingFor(id) || body.length != WireProtocol.FONT_STATUS_BYTES) {
            throw new IOException("Unexpected font status");
        }
        int status = body[0] & 0xff;
        long offset = WireProtocol.readLe32(body, 1);
        if (offset != expectedBytes) throw new IOException("Unexpected committed font offset");
        switch (phase) {
            case QUERYING:
                if (status == READY || status == MISSING) {
                    phase = status == READY ? Phase.READY : Phase.NEED_PACK;
                    return null;
                }
                break;
            case BEGINNING:
            case UPLOADING:
                if (status == UPLOADING) {
                    confirmedBytes = expectedBytes;
                    return nextChunk();
                }
                break;
            case INSTALLING:
                if (status == READY) {
                    pack.close();
                    pack = null;
                    phase = Phase.READY;
                    return null;
                }
                break;
            default:
                throw new IOException("No font operation in progress");
        }
        throw new IOException("Reader rejected font operation");
    }

    private Command nextChunk() throws IOException {
        if (confirmedBytes == totalBytes) return issue(Phase.INSTALLING, MessageType.FONT_END, new byte[0]);
        int length = Math.min(chunkLimit, totalBytes - confirmedBytes);
        byte[] body = new byte[CHUNK_HEADER_BYTES + length];
        WireProtocol.writeLe32(body, 0, confirmedBytes);
        pack.readFully(body, CHUNK_HEADER_BYTES, length);
        expectedBytes = confirmedBytes + length;
        return issue(Phase.UPLOADING, MessageType.FONT_CHUNK, body);
    }

    private Command issue(Phase next, MessageType type, byte[] body) {
        phase = next;
        return new Command(type, ++lastId, body);
    }

    @Override
    public void close() throws IOException {
        phase = Phase.CLOSED;
        if (pack != null) {
            DataInputStream source = pack;
            pack = null;
            source.close();
        }
    }
}
