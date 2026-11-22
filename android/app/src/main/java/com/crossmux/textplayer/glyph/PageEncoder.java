package com.crossmux.textplayer.glyph;

import com.crossmux.textplayer.layout.GridPager;
import com.crossmux.textplayer.protocol.WireProtocol;

import java.util.ArrayList;
import java.util.HashMap;

public final class PageEncoder {
    public static final int GRID_HEADER_BYTES = 32;
    public static final int DESCRIPTOR_BYTES = 20;

    public static final class EncodedPage {
        private final long requestId;
        private final long pageId;
        private final long pushSeq;
        private final DeviceGlyphCache.Plan<GlyphKey> plan;
        private final byte[] body;

        private EncodedPage(long requestId, long pageId, long pushSeq,
                DeviceGlyphCache.Plan<GlyphKey> plan, byte[] body) {
            this.requestId = requestId;
            this.pageId = pageId;
            this.pushSeq = pushSeq;
            this.plan = plan;
            this.body = body;
        }

        public long getRequestId() {
            return requestId;
        }

        public long getPageId() {
            return pageId;
        }

        public long getPushSeq() {
            return pushSeq;
        }

        public DeviceGlyphCache.Encoding getEncoding() {
            return plan.getEncoding();
        }

        public byte[] getBody() {
            return body;
        }

        public DeviceGlyphCache.Plan<GlyphKey> getPlan() {
            return plan;
        }
    }

    private final GlyphProvider provider;
    private final RasterGlyphLru<GlyphKey, GlyphBitmap> rasterCache;
    private final DeviceGlyphCache<GlyphKey> deviceCache;
    private final String fontId;
    private final float textSizePx;
    private final int style;

    public PageEncoder(GlyphProvider provider, RasterGlyphLru<GlyphKey, GlyphBitmap> rasterCache,
            DeviceGlyphCache<GlyphKey> deviceCache, String fontId, float textSizePx, int style) {
        this.provider = provider;
        this.rasterCache = rasterCache;
        this.deviceCache = deviceCache;
        this.fontId = fontId;
        this.textSizePx = textSizePx;
        this.style = style;
    }

    public EncodedPage encode(GridPager.Page page, long requestId, long pushSeq) {
        ArrayList<DeviceGlyphCache.Demand<GlyphKey>> demands = new ArrayList<>();
        HashMap<GlyphKey, GlyphBitmap> bitmaps = new HashMap<>();
        for (GridPager.Token token : page.getTokens()) {
            if (token.getType() != GridPager.TokenType.GLYPH
                    && token.getType() != GridPager.TokenType.ASCII_RUN) {
                continue;
            }
            GlyphKey key = GlyphKey.forToken(token, fontId, textSizePx, style);
            GlyphBitmap bitmap = rasterCache.get(key);
            if (bitmap == null) {
                bitmap = provider.rasterize(key);
                if (bitmap == null) {
                    return null;
                }
                rasterCache.put(key, bitmap);
            }
            bitmaps.put(key, bitmap);
            demands.add(new DeviceGlyphCache.Demand<>(key, bitmap.getByteLength()));
        }

        DeviceGlyphCache.Plan<GlyphKey> plan = deviceCache.planGrid(demands);
        if (plan.getEncoding() == DeviceGlyphCache.Encoding.BITMAP) {
            return new EncodedPage(requestId, page.getPageId(), pushSeq, plan, null);
        }

        int glyphBytes = 0;
        for (DeviceGlyphCache.Binding<GlyphKey> binding : plan.getBindings()) {
            glyphBytes += bitmaps.get(binding.getKey()).getByteLength();
        }
        int tokenCount = 1;
        for (GridPager.Token token : page.getTokens()) {
            tokenCount += token.isSpanningGlyph() ? 3 : 1;
        }
        byte[] body = new byte[GRID_HEADER_BYTES + DESCRIPTOR_BYTES * plan.getBindings().size()
                + glyphBytes + tokenCount * 2];
        WireProtocol.writeLe32(body, 0, requestId);
        WireProtocol.writeLe32(body, 4, page.getPageId());
        WireProtocol.writeLe32(body, 8, plan.getBaseEpoch());
        WireProtocol.writeLe32(body, 12, plan.getBaseRevision());
        WireProtocol.writeLe32(body, 16, plan.getTargetEpoch());
        WireProtocol.writeLe32(body, 20, plan.getTargetRevision());
        WireProtocol.writeLe16(body, 24, plan.getBindings().size());
        WireProtocol.writeLe16(body, 26, tokenCount);
        WireProtocol.writeLe16(body, 28, plan.isReset() ? 1 : 0);
        WireProtocol.writeLe16(body, 30, 0);

        int offset = GRID_HEADER_BYTES;
        for (DeviceGlyphCache.Binding<GlyphKey> binding : plan.getBindings()) {
            GlyphBitmap bitmap = bitmaps.get(binding.getKey());
            WireProtocol.writeLe16(body, offset, binding.getSlotId());
            WireProtocol.writeLe32(body, offset + 2, binding.getPoolOffset());
            WireProtocol.writeLe32(body, offset + 6, binding.getByteLength());
            WireProtocol.writeLe16(body, offset + 10, bitmap.getWidth());
            WireProtocol.writeLe16(body, offset + 12, bitmap.getHeight());
            WireProtocol.writeLe16(body, offset + 14, bitmap.getRowStride());
            WireProtocol.writeLe16(body, offset + 16, bitmap.getOffsetX());
            WireProtocol.writeLe16(body, offset + 18, bitmap.getOffsetY());
            offset += DESCRIPTOR_BYTES;
        }
        for (DeviceGlyphCache.Binding<GlyphKey> binding : plan.getBindings()) {
            byte[] pixels = bitmaps.get(binding.getKey()).getPixels();
            System.arraycopy(pixels, 0, body, offset, pixels.length);
            offset += pixels.length;
        }
        int glyphIndex = 0;
        for (GridPager.Token token : page.getTokens()) {
            if (token.isSpanningGlyph()) {
                WireProtocol.writeLe16(body, offset, -4);
                WireProtocol.writeLe16(body, offset + 2, plan.getSlotTokens().get(glyphIndex++));
                WireProtocol.writeLe16(body, offset + 4, token.getAdvanceCells());
                offset += 6;
                continue;
            }
            final int wireToken;
            switch (token.getType()) {
                case GLYPH:
                    wireToken = plan.getSlotTokens().get(glyphIndex++);
                    break;
                case SPACE:
                    wireToken = -1;
                    break;
                case NEWLINE:
                    wireToken = -2;
                    break;
                default:
                    throw new IllegalStateException("Unknown grid token");
            }
            WireProtocol.writeLe16(body, offset, wireToken);
            offset += 2;
        }
        WireProtocol.writeLe16(body, offset, -3);
        return new EncodedPage(requestId, page.getPageId(), pushSeq, plan, body);
    }

    public void commitDisplayed(EncodedPage page) {
        deviceCache.commitDisplayed(page.getPlan());
    }
}
