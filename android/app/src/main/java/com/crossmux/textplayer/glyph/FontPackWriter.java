package com.crossmux.textplayer.glyph;

import com.crossmux.textplayer.protocol.GlyphPageStream;
import com.crossmux.textplayer.protocol.WireProtocol;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;

public final class FontPackWriter {
    public static final int GENERATION_BYTES = 16;
    public static final int MAX_PACK_BYTES = 64 * 1024 * 1024;
    public static final int MAX_INK_BYTES = 1024;
    private static final int HEADER_BYTES = 32;
    private static final int FACE_BYTES = 8;
    private static final int METRICS_BYTES = 8;
    private static final int RECORD_OVERHEAD = METRICS_BYTES + Integer.BYTES;

    public interface Face {
        int glyphCount();
        void measure(int glyphId, Bounds bounds) throws IOException;
        void rasterize(int glyphId, Bounds bounds, byte[] record, int inkOffset) throws IOException;
    }

    public static final class Bounds {
        public int left;
        public int top;
        public int width;
        public int height;

        public void set(int left, int top, int width, int height) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
        }

        private int inkBytes() throws IOException {
            if (width == 0 && height == 0 && left == 0 && top == 0) return 0;
            if (width <= 0 || width > 0xffff || height <= 0 || height > 0xffff
                    || left < Short.MIN_VALUE || left > Short.MAX_VALUE
                    || top < Short.MIN_VALUE || top > Short.MAX_VALUE) {
                throw new IOException("Invalid glyph dimensions");
            }
            int bytes = ((width + 7) / 8) * height;
            if (bytes > MAX_INK_BYTES) throw new IOException("Glyph exceeds the reader's ink buffer");
            return bytes;
        }
    }

    public record Result(int bytes, long crc) {}

    private FontPackWriter() {}

    public static Result write(OutputStream output, byte[] generation, int pixelSize, List<? extends Face> faces,
            BooleanSupplier cancelled) throws IOException {
        if (generation.length != GENERATION_BYTES || Arrays.equals(generation, new byte[GENERATION_BYTES])
                || pixelSize <= 0 || pixelSize > 0xffff || faces.isEmpty() || faces.size() > GlyphPageStream.MAX_FACES) {
            throw new IOException("Invalid font pack identity");
        }
        Bounds bounds = new Bounds();
        int[] recordBytes = new int[faces.size()];
        byte[] header = new byte[HEADER_BYTES + faces.size() * FACE_BYTES];
        header[0] = 'T';
        header[1] = 'P';
        header[2] = 'F';
        header[3] = '1';
        System.arraycopy(generation, 0, header, 4, GENERATION_BYTES);
        WireProtocol.writeLe16(header, 20, pixelSize);
        WireProtocol.writeLe16(header, 22, faces.size());
        long total = header.length;

        for (int index = 0; index < faces.size(); index++) {
            Face face = faces.get(index);
            int glyphCount = face.glyphCount();
            if (glyphCount <= 0 || glyphCount > 65536) throw new IOException("Invalid glyph count");
            int maxInk = 0;
            for (int glyph = 0; glyph < glyphCount; glyph++) {
                checkCancelled(cancelled, glyph);
                face.measure(glyph, bounds);
                maxInk = Math.max(maxInk, bounds.inkBytes());
            }
            int stride = maxInk + RECORD_OVERHEAD;
            recordBytes[index] = stride;
            total += (long) stride * glyphCount;
            if (total > MAX_PACK_BYTES) throw new IOException("Font pack exceeds reader storage limit");
            int directoryOffset = HEADER_BYTES + index * FACE_BYTES;
            WireProtocol.writeLe32(header, directoryOffset, glyphCount);
            WireProtocol.writeLe16(header, directoryOffset + 4, stride);
        }

        WireProtocol.writeLe32(header, 24, total);
        CRC32 crc = new CRC32();
        crc.update(header, 0, 28);
        crc.update(header, HEADER_BYTES, header.length - HEADER_BYTES);
        WireProtocol.writeLe32(header, 28, crc.getValue());
        output.write(header);
        CRC32 fullCrc = new CRC32();
        fullCrc.update(header);
        byte[] record = new byte[RECORD_OVERHEAD + MAX_INK_BYTES];
        for (int index = 0; index < faces.size(); index++) {
            Face face = faces.get(index);
            int stride = recordBytes[index];
            for (int glyph = 0; glyph < face.glyphCount(); glyph++) {
                checkCancelled(cancelled, glyph);
                face.measure(glyph, bounds);
                int ink = bounds.inkBytes();
                if (ink > stride - RECORD_OVERHEAD) throw new IOException("Glyph dimensions changed during export");
                Arrays.fill(record, 0, stride, (byte) 0);
                WireProtocol.writeLe16(record, 0, bounds.width);
                WireProtocol.writeLe16(record, 2, bounds.height);
                WireProtocol.writeLe16(record, 4, bounds.left);
                WireProtocol.writeLe16(record, 6, bounds.top);
                if (ink != 0) face.rasterize(glyph, bounds, record, METRICS_BYTES);
                crc.reset();
                int crcOffset = METRICS_BYTES + ink;
                crc.update(record, 0, crcOffset);
                WireProtocol.writeLe32(record, crcOffset, crc.getValue());
                output.write(record, 0, stride);
                fullCrc.update(record, 0, stride);
            }
        }
        return new Result((int) total, fullCrc.getValue());
    }

    private static void checkCancelled(BooleanSupplier cancelled, int glyphId) throws IOException {
        if ((glyphId & 63) == 0 && cancelled.getAsBoolean()) throw new IOException("Font generation cancelled");
    }
}
