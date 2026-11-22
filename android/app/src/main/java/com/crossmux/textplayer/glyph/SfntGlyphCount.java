package com.crossmux.textplayer.glyph;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class SfntGlyphCount {
    private static final int TTCF = 0x74746366;
    private static final int MAXP = 0x6d617870;

    private SfntGlyphCount() {}

    static int read(ByteBuffer source, int faceIndex) throws IOException {
        ByteBuffer font = source.slice().order(ByteOrder.BIG_ENDIAN);
        requireRange(font, 0, 12);
        int face = 0;
        if (font.getInt(0) == TTCF) {
            long count = unsignedInt(font, 8);
            if (faceIndex < 0 || faceIndex >= count) throw new IOException("Invalid font collection face");
            long entry = 12L + 4L * faceIndex;
            requireRange(font, entry, 4);
            long offset = unsignedInt(font, (int) entry);
            requireRange(font, offset, 12);
            face = (int) offset;
        } else if (faceIndex != 0) {
            throw new IOException("Invalid standalone font face");
        }
        int tables = Short.toUnsignedInt(font.getShort(face + 4));
        requireRange(font, (long) face + 12, 16L * tables);
        for (int table = 0; table < tables; table++) {
            int entry = face + 12 + 16 * table;
            if (font.getInt(entry) != MAXP) continue;
            long offset = unsignedInt(font, entry + 8);
            long length = unsignedInt(font, entry + 12);
            requireRange(font, offset, length);
            if (length < 6) throw new IOException("Truncated font glyph table");
            int glyphs = Short.toUnsignedInt(font.getShort((int) offset + 4));
            if (glyphs > 0) return glyphs;
            break;
        }
        throw new IOException("Font has no glyph count");
    }

    private static long unsignedInt(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static void requireRange(ByteBuffer buffer, long offset, long bytes) throws IOException {
        if (offset > buffer.limit() || bytes > buffer.limit() - offset) throw new IOException("Truncated font");
    }
}
