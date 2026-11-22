package com.crossmux.textplayer.glyph;

import com.crossmux.textplayer.protocol.WireProtocol;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class FontPackWriterTest {
    private static final byte[] GENERATION = {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    @Test
    public void writesDirectSlotsIncludingEmptyGlyphWithIndependentChecksums() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FontPackWriter.Result result = FontPackWriter.write(output, GENERATION, 24,
                List.of(face(2, 9), face(1, 8)), () -> false);
        byte[] pack = output.toByteArray();
        assertEquals(48 + 2 * 16 + 14, pack.length);
        assertEquals(pack.length, result.bytes());
        assertEquals(crc(pack, 0, pack.length), result.crc());
        assertArrayEquals(new byte[]{'T', 'P', 'F', '1'}, Arrays.copyOf(pack, 4));
        assertArrayEquals(GENERATION, Arrays.copyOfRange(pack, 4, 20));
        assertEquals(24, WireProtocol.readLe16(pack, 20));
        assertEquals(2, WireProtocol.readLe16(pack, 22));
        assertEquals(pack.length, WireProtocol.readLe32(pack, 24));
        CRC32 directoryCrc = new CRC32();
        directoryCrc.update(pack, 0, 28);
        directoryCrc.update(pack, 32, 16);
        assertEquals(directoryCrc.getValue(), WireProtocol.readLe32(pack, 28));
        assertEquals(2, WireProtocol.readLe32(pack, 32));
        assertEquals(16, WireProtocol.readLe16(pack, 36));
        assertEquals(14, WireProtocol.readLe16(pack, 44));
        assertEquals(0, WireProtocol.readLe32(pack, 48));
        assertEquals(crc(pack, 48, 8), WireProtocol.readLe32(pack, 56));
        assertEquals(9, WireProtocol.readLe16(pack, 64));
        assertEquals(-2, (short) WireProtocol.readLe16(pack, 68));
        assertEquals(0x80, Byte.toUnsignedInt(pack[72]));
        assertEquals(crc(pack, 64, 12), WireProtocol.readLe32(pack, 76));
        assertEquals(8, WireProtocol.readLe16(pack, 80));
        assertEquals(crc(pack, 80, 10), WireProtocol.readLe32(pack, 90));
    }

    @Test
    public void rejectsUnrepresentableGlyphsAndCancelledBuildsBeforeWritingHeader() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> FontPackWriter.write(output, GENERATION, 24,
                List.of(face(1, 65535)), () -> false));
        assertThrows(IOException.class, () -> FontPackWriter.write(output, GENERATION, 24,
                List.of(face(1, 10)), () -> true));
        assertEquals(0, output.size());
    }

    @Test
    public void readsCollectionFaceAndRejectsOutOfRangeTable() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(90).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(0, 0x74746366).putInt(4, 0x00010000).putInt(8, 2);
        buffer.putInt(12, 20).putInt(16, 50);
        buffer.putShort(24, (short) 1).putInt(32, 0x6d617870).putInt(40, 78).putInt(44, 6);
        buffer.putShort(54, (short) 1).putInt(62, 0x6d617870).putInt(70, 84).putInt(74, 6);
        buffer.putShort(82, (short) 532).putShort(88, (short) 63000);
        assertEquals(532, SfntGlyphCount.read(buffer, 0));
        assertEquals(63000, SfntGlyphCount.read(buffer, 1));
        assertThrows(IOException.class, () -> SfntGlyphCount.read(buffer, 2));
        buffer.putInt(74, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> SfntGlyphCount.read(buffer, 1));
    }

    private static long crc(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return crc.getValue();
    }

    private static FontPackWriter.Face face(int glyphs, int width) {
        return new FontPackWriter.Face() {
            @Override public int glyphCount() { return glyphs; }

            @Override public void measure(int id, FontPackWriter.Bounds bounds) {
                if (id == 0 && glyphs > 1) {
                    bounds.set(0, 0, 0, 0);
                } else {
                    bounds.set(-2, -7, width, 2);
                }
            }

            @Override public void rasterize(int id, FontPackWriter.Bounds bounds, byte[] record, int offset) {
                record[offset] = (byte) 0x80;
            }
        };
    }
}
