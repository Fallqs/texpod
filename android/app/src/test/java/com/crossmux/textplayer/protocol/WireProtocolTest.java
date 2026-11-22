package com.crossmux.textplayer.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

public final class WireProtocolTest {
    @Test
    public void mtu23RoundTripPreservesBodyAndFrameLimit() {
        byte[] body = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9};
        assertEquals(6, WireProtocol.gattPayloadLimit(23));
        assertEquals(168, WireProtocol.gattPayloadLimit(185));
        assertEquals(226, WireProtocol.gattPayloadLimit(517));
        List<byte[]> frames = WireProtocol.createFrames(
                WireProtocol.MessageType.PAGE_GRID, 7, 11, body, WireProtocol.gattPayloadLimit(23));
        WireProtocol.ObjectAssembler assembler = new WireProtocol.ObjectAssembler();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WireProtocol.AssemblyResult result = null;
        for (byte[] encoded : frames) {
            assertTrue(encoded.length <= 20);
            WireProtocol.Frame frame = WireProtocol.parseFrame(encoded);
            result = assembler.receive(frame, (data, offset, length) -> {
                output.write(data, offset, length);
                return true;
            });
        }
        assertEquals(3, frames.size());
        assertEquals(WireProtocol.AssemblyStatus.COMPLETE, result.status);
        assertEquals(9, result.bodyLength);
        assertTrue(java.util.Arrays.equals(body, output.toByteArray()));
    }

    @Test
    public void largeObjectUsesBoundedOffsets() {
        byte[] body = new byte[1024];
        for (int index = 0; index < body.length; index++) {
            body[index] = (byte) index;
        }
        List<byte[]> frames = WireProtocol.createFrames(
                WireProtocol.MessageType.PAGE_BITMAP, 1, 2, body, 20);
        WireProtocol.ObjectAssembler assembler = new WireProtocol.ObjectAssembler();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WireProtocol.AssemblyResult result = null;
        for (byte[] encoded : frames) {
            result = assembler.receive(WireProtocol.parseFrame(encoded), (data, offset, length) -> {
                output.write(data, offset, length);
                return true;
            });
        }
        assertEquals(WireProtocol.AssemblyStatus.COMPLETE, result.status);
        assertTrue(java.util.Arrays.equals(body, output.toByteArray()));
    }

    @Test
    public void rejectsDuplicateOffset() {
        byte[] body = new byte[20];
        List<byte[]> frames = WireProtocol.createFrames(
                WireProtocol.MessageType.PAGE_GRID, 1, 2, body, 20);
        WireProtocol.ObjectAssembler assembler = new WireProtocol.ObjectAssembler();
        WireProtocol.Frame first = WireProtocol.parseFrame(frames.get(0));
        assertEquals(WireProtocol.AssemblyStatus.NEED_MORE,
                assembler.receive(first, (data, offset, length) -> true).status);
        assertEquals(WireProtocol.AssemblyStatus.ERROR,
                assembler.receive(first, (data, offset, length) -> true).status);
    }

    @Test
    public void crcMatchesStandardVector() {
        assertEquals(0xcbf43926L, WireProtocol.crc32("123456789".getBytes()));
    }

    @Test
    public void profileFragmentCountMatchesWireFrames() {
        for (int mtu : new int[] {23, 185, 243, 517}) {
            int payload = WireProtocol.gattPayloadLimit(mtu);
            for (int size : new int[] {0, 1, payload - 6, payload - 5, 48_016, 65_535}) {
                var frames = WireProtocol.createFrames(WireProtocol.MessageType.PAGE_BITMAP, 1, 2,
                        new byte[size], payload);
                assertEquals(frames.size(), WireProtocol.frameCount(size, payload));
            }
        }
        assertEquals(226, WireProtocol.gattPayloadLimit(243));
    }

    @Test
    public void decodesProfiledDisplayedAndDoesNotInventLegacyTimings() {
        byte[] displayed = new byte[] {
                1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0, 5, 0, 0, 0,
                (byte) 0xe8, 3, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0, 0, 1, 0, 0, (byte) 0x80
        };
        assertEquals(new WireProtocol.PageTimings(1000, 2, 0, 9, 0x80000001L),
                WireProtocol.PageTimings.fromDisplayed(displayed));
        org.junit.Assert.assertNull(WireProtocol.PageTimings.fromDisplayed(new byte[20]));
        org.junit.Assert.assertNull(WireProtocol.PageTimings.fromDisplayed(new byte[39]));
    }

    @Test
    public void negotiatesTimingFlagsAndAckSizes() {
        int legacy = WireProtocol.CAP_PAGE_TIMINGS;
        int detailed = WireProtocol.CAP_PIPELINE_TIMINGS;
        int intervals = WireProtocol.CAP_CONNECTION_INTERVAL;
        int sd = WireProtocol.CAP_SD_GLYPHS;
        int turn = WireProtocol.CAP_GLYPH_TURN_TIMINGS;
        assertEquals(0, WireProtocol.timingFlags(3, false));
        assertEquals(0, WireProtocol.timingFlags(3 | detailed | intervals, false));
        assertEquals(1, WireProtocol.timingFlags(3 | legacy, false));
        assertEquals(1, WireProtocol.timingFlags(3 | legacy | intervals, false));
        assertEquals(3, WireProtocol.timingFlags(3 | legacy | detailed, false));
        int link = 3 | legacy | detailed | intervals;
        assertEquals(7, WireProtocol.timingFlags(link, false));
        assertEquals(7, WireProtocol.timingFlags(link | sd, true));
        assertEquals(7, WireProtocol.timingFlags(link | sd | turn, false));
        assertEquals(3, WireProtocol.timingFlags((link & ~intervals) | sd | turn, true));
        assertEquals(39, WireProtocol.timingFlags(link | sd | turn, true));
        assertEquals(68, WireProtocol.displayedBytes(39));
        assertEquals(20, WireProtocol.displayedBytes(0));
        assertEquals(40, WireProtocol.displayedBytes(1));
        assertEquals(52, WireProtocol.displayedBytes(3));
        assertEquals(56, WireProtocol.displayedBytes(7));
    }

    @Test
    public void verifiesSdDisplayedIdentityAndZeroCacheRevision() {
        byte[] body = new byte[WireProtocol.LINK_DISPLAYED_BYTES];
        WireProtocol.writeLe32(body, 0, 42);
        WireProtocol.writeLe32(body, 4, 17);
        WireProtocol.writeLe32(body, 16, 136);
        assertTrue(WireProtocol.matchesDisplayed(body, 7, 42, 17, 0, 0));
        org.junit.Assert.assertFalse(WireProtocol.matchesDisplayed(body, 3, 42, 17, 0, 0));
        org.junit.Assert.assertFalse(WireProtocol.matchesDisplayed(body, 7, 43, 17, 0, 0));
        org.junit.Assert.assertFalse(WireProtocol.matchesDisplayed(body, 7, 42, 18, 0, 0));
        WireProtocol.writeLe32(body, 12, 1);
        org.junit.Assert.assertFalse(WireProtocol.matchesDisplayed(body, 7, 42, 17, 0, 0));
        assertTrue(WireProtocol.matchesDisplayed(body, 7, 42, 17, 0, 1));
    }

    @Test
    public void decodesReaderCreditFrame() {
        byte[] credit = {0, 11, 3, 0, 7, 0, 0, 0, 11, 0, 0, 0, 0, 0,
                2, 0, (byte) 0xf3, 0x0e, (byte) 0x90, (byte) 0x97, 0x46, 0x05};
        var assembler = new WireProtocol.ObjectAssembler();
        var body = new ByteArrayOutputStream();
        var result = assembler.receive(WireProtocol.parseFrame(credit), (data, offset, length) -> {
            body.write(data, offset, length);
            return true;
        });
        assertEquals(WireProtocol.AssemblyStatus.COMPLETE, result.status);
        assertEquals(WireProtocol.MessageType.PAGE_CREDIT, result.type);
        assertEquals(7, result.sessionId);
        assertEquals(11, result.objectId);
        assertEquals(WireProtocol.PAGE_CREDIT_BYTES, result.bodyLength);
        assertEquals(1350, WireProtocol.readLe16(body.toByteArray(), 0));
    }

    @Test
    public void decodesDetailedReaderTimingVector() {
        byte[] displayed = new byte[] {
                1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0, 5, 0, 0, 0,
                (byte) 0xe8, 3, 0, 0, 2, 0, 0, 0, 1, 0, 0, 0, 9, 0, 0, 0, (byte) 0xf4, 1, 0, 0,
                (byte) 0xe0, 3, 0, 0, 0x10, 0x27, 0, 0, 5, 0, 0, 0
        };
        var timings = WireProtocol.PageTimings.fromDisplayed(displayed);
        assertEquals(new WireProtocol.PageTimings(1000, 2, 1, 9, 500,
                new WireProtocol.ReceiveTimings(992, 10_000, 5), null, null), timings);
        assertEquals(512, timings.stagedToRefreshMs());
        org.junit.Assert.assertNull(WireProtocol.PageTimings.fromDisplayed(new byte[51]));
    }

    @Test
    public void decodesGlyphTurnTimingAndDistinguishesUnrequestedAnchor() {
        byte[] body = new byte[WireProtocol.GLYPH_TURN_DISPLAYED_BYTES];
        byte[] suffix = {0x20, 3, 0, 0, 0x18, 0x79, 0, 0, (byte) 0x83, 0, 0x25, 0};
        System.arraycopy(suffix, 0, body, WireProtocol.LINK_DISPLAYED_BYTES, suffix.length);
        WireProtocol.writeLe16(body, 52, 24);
        WireProtocol.writeLe16(body, 54, 12);
        var timing = WireProtocol.PageTimings.fromDisplayed(body);
        assertEquals(new WireProtocol.ConnectionIntervals(24, 12), timing.intervals());
        assertEquals(new WireProtocol.GlyphTurnTimings(800, 31_000, 131, 37), timing.glyphTurn());
        assertTrue(timing.glyphTurn().hasTurn());
        WireProtocol.writeLe32(body, WireProtocol.LINK_DISPLAYED_BYTES, 0xffff_ffffL);
        org.junit.Assert.assertFalse(WireProtocol.PageTimings.fromDisplayed(body).glyphTurn().hasTurn());
        org.junit.Assert.assertNull(WireProtocol.PageTimings.fromDisplayed(new byte[67]));
        org.junit.Assert.assertNull(WireProtocol.PageTimings.fromDisplayed(new byte[56]).glyphTurn());
    }

    @Test
    public void decodesFirstAndLastNegotiatedIntervalsInBleUnits() {
        byte[] body = new byte[WireProtocol.LINK_DISPLAYED_BYTES];
        body[40] = 100;
        body[52] = 48;
        body[54] = 9;
        var timing = WireProtocol.PageTimings.fromDisplayed(body);
        assertEquals(100, timing.rx().arrivalSpanMs());
        assertEquals(60.0, timing.intervals().firstMs(), 0.0);
        assertEquals(11.25, timing.intervals().lastMs(), 0.0);
        org.junit.Assert.assertNull(WireProtocol.PageTimings.fromDisplayed(java.util.Arrays.copyOf(body, 55)));
    }

    @Test
    public void knownFrameVectorMatchesFirmwareDecoder() {
        byte[] expected = new byte[] {0, 4, 3, 0, 7, 0, 0, 0, 11, 0, 0, 0, 0, 0,
                9, 0, (byte) 0x9e, (byte) 0xab, (byte) 0xef, 0x40, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        List<byte[]> frames = WireProtocol.createFrames(
                WireProtocol.MessageType.PAGE_GRID, 7, 11, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, 20);
        assertEquals(1, frames.size());
        assertTrue(java.util.Arrays.equals(expected, frames.get(0)));
    }
}
