package com.crossmux.textplayer.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.crossmux.textplayer.glyph.FontPackWriter;
import com.crossmux.textplayer.protocol.WireProtocol;
import com.crossmux.textplayer.protocol.WireProtocol.MessageType;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;

public final class FontPackSyncTest {
    private static final byte[] GENERATION = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

    @Test
    public void installedGenerationNeedsNoUpload() throws IOException {
        try (var sync = new FontPackSync(GENERATION, 65535)) {
            var query = sync.start(false);
            assertEquals(MessageType.FONT_QUERY, query.type());
            assertArrayEquals(GENERATION, query.body());
            assertNull(ack(sync, query, 0, 0));
            assertEquals(FontPackSync.Phase.READY, sync.phase());
            assertFalse(sync.waitingFor(query.id()));
        }
    }

    @Test
    public void missingGenerationUploadsBoundedChunksAndWaitsForInstallation() throws IOException {
        byte[] pack = new byte[70];
        for (int i = 0; i < pack.length; i++) pack[i] = (byte) i;
        long crc = WireProtocol.crc32(pack);
        var source = new TrackedSource(pack);
        try (var sync = new FontPackSync(GENERATION, 29)) {
            var query = sync.start(false);
            assertNull(ack(sync, query, 1, 0));
            assertEquals(FontPackSync.Phase.NEED_PACK, sync.phase());
            var begin = sync.beginUpload(new FontPackWriter.Result(pack.length, crc), source);
            assertEquals(MessageType.FONT_BEGIN, begin.type());
            assertArrayEquals(GENERATION, Arrays.copyOf(begin.body(), GENERATION.length));
            assertEquals(pack.length, WireProtocol.readLe32(begin.body(), 16));
            assertEquals(crc, WireProtocol.readLe32(begin.body(), 20));
            var chunk = ack(sync, begin, 2, 0);
            for (int offset = 0; offset < pack.length;) {
                assertEquals(MessageType.FONT_CHUNK, chunk.type());
                assertEquals(offset, sync.confirmedBytes());
                assertEquals(offset, WireProtocol.readLe32(chunk.body(), 0));
                int end = Math.min(pack.length, offset + 25);
                assertArrayEquals(Arrays.copyOfRange(pack, offset, end),
                        Arrays.copyOfRange(chunk.body(), 4, chunk.body().length));
                chunk = ack(sync, chunk, 2, end);
                offset = end;
            }
            assertEquals(MessageType.FONT_END, chunk.type());
            assertEquals(0, chunk.body().length);
            assertEquals(FontPackSync.Phase.INSTALLING, sync.phase());
            assertFalse(source.closed);
            assertNull(ack(sync, chunk, 0, pack.length));
            assertTrue(source.closed);
            assertEquals(FontPackSync.Phase.READY, sync.phase());
        }
    }

    @Test
    public void forceSkipsQueryAndRejectsWrongSessionIdStatusAndOffset() throws IOException {
        var source = new TrackedSource(new byte[60]);
        try (var sync = new FontPackSync(GENERATION, 65535)) {
            assertNull(sync.start(true));
            assertEquals(FontPackSync.Phase.NEED_PACK, sync.phase());
            var begin = sync.beginUpload(new FontPackWriter.Result(60, 0), source);
            assertEquals(1, begin.id());
            assertThrows(IOException.class, () -> sync.onStatus(1, begin.id(), status(2, 0)));
            assertThrows(IOException.class, () -> sync.onStatus(0, begin.id() + 1, status(2, 0)));
            assertThrows(IOException.class, () -> ack(sync, begin, 3, 0));
            var chunk = ack(sync, begin, 2, 0);
            assertThrows(IOException.class, () -> ack(sync, begin, 2, 0));
            assertThrows(IOException.class, () -> ack(sync, chunk, 2, 59));
            assertEquals(0, sync.confirmedBytes());
        }
        assertTrue(source.closed);
    }

    @Test
    public void cancellationClosesSourceAndDisarmsOutstandingCommand() throws IOException {
        var sync = new FontPackSync(GENERATION, 65535);
        var source = new TrackedSource(new byte[64]);
        sync.start(true);
        var begin = sync.beginUpload(new FontPackWriter.Result(64, 0), source);
        assertTrue(sync.waitingFor(begin.id()));
        sync.close();
        assertTrue(source.closed);
        assertFalse(sync.waitingFor(begin.id()));
        assertThrows(IOException.class, () -> ack(sync, begin, 2, 0));
    }

    private static FontPackSync.Command ack(FontPackSync sync, FontPackSync.Command command, int result, int offset)
            throws IOException {
        return sync.onStatus(0, command.id(), status(result, offset));
    }

    private static byte[] status(int result, int offset) {
        byte[] body = new byte[WireProtocol.FONT_STATUS_BYTES];
        body[0] = (byte) result;
        WireProtocol.writeLe32(body, 1, offset);
        return body;
    }

    private static final class TrackedSource extends ByteArrayInputStream {
        boolean closed;

        TrackedSource(byte[] data) { super(data); }

        @Override public void close() { closed = true; }
    }
}
