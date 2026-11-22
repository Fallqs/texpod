package com.crossmux.textplayer.protocol;

import java.io.ByteArrayOutputStream;

public final class GlyphPageStream {
    public static final int HEADER_BYTES = 10;
    public static final int MAX_FACES = 16;
    private static final int POSITIONED = 0x10;
    private static final int ADVANCE = 0x20;
    private static final int NEWLINE = 0x21;

    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final int commandLimit;
    private final int byteLimit;
    private int commands;

    public GlyphPageStream(int commandLimit, int byteLimit) {
        if (commandLimit < 0 || commandLimit > 0xffff || byteLimit < HEADER_BYTES
                || byteLimit > WireProtocol.MAX_OBJECT_BYTES) {
            throw new IllegalArgumentException("Invalid glyph page limits");
        }
        this.commandLimit = commandLimit;
        this.byteLimit = byteLimit;
    }

    public void glyph(int face, int glyphId) {
        checkGlyph(face, glyphId);
        command(face, 3);
        shortOperand(glyphId);
    }

    public void positioned(int face, int glyphId, int x, int y) {
        checkGlyph(face, glyphId);
        if (x < Short.MIN_VALUE || x > Short.MAX_VALUE || y < Short.MIN_VALUE || y > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid glyph displacement");
        }
        command(POSITIONED | face, 7);
        shortOperand(glyphId);
        shortOperand(x);
        shortOperand(y);
    }

    public void advance(int cells) {
        if (cells < 1 || cells > 0xffff) {
            throw new IllegalArgumentException("Invalid cell advance");
        }
        command(ADVANCE, 3);
        shortOperand(cells);
    }

    public void newline() {
        command(NEWLINE, 1);
    }

    public byte[] encode(long requestId, long pageId) {
        if (requestId < 0 || requestId > 0xffffffffL || pageId <= 0 || pageId > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid page identity");
        }
        byte[] result = new byte[HEADER_BYTES + body.size()];
        WireProtocol.writeLe32(result, 0, requestId);
        WireProtocol.writeLe32(result, 4, pageId);
        WireProtocol.writeLe16(result, 8, commands);
        System.arraycopy(body.toByteArray(), 0, result, HEADER_BYTES, body.size());
        return result;
    }

    private void command(int opcode, int bytes) {
        if (commands == commandLimit || body.size() + bytes > byteLimit - HEADER_BYTES) {
            throw new IllegalArgumentException("Glyph page exceeds negotiated limits");
        }
        commands++;
        body.write(opcode);
    }

    private static void checkGlyph(int face, int glyphId) {
        if (face < 0 || face >= MAX_FACES || glyphId < 0 || glyphId > 0xffff) {
            throw new IllegalArgumentException("Invalid glyph reference");
        }
    }

    private void shortOperand(int value) {
        body.write(value);
        body.write(value >>> 8);
    }
}
