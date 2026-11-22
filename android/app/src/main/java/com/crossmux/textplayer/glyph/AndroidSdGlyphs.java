package com.crossmux.textplayer.glyph;

import android.annotation.TargetApi;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontVariationAxis;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.os.Build;

import com.crossmux.textplayer.layout.ChinesePunctuation;
import com.crossmux.textplayer.layout.GridPager;
import com.crossmux.textplayer.layout.ReaderProfile;
import com.crossmux.textplayer.protocol.GlyphPageStream;

import java.io.DataInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Bidi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

@TargetApi(31)
public final class AndroidSdGlyphs {
    private static final int MAX_DIMENSION = 256;
    private static final String RASTER_VERSION = "texpod-tpf1-android-raster-1";

    public static final class MissingFaceException extends IOException {
        MissingFaceException() { super("Page needs a face outside the installed font pack"); }
    }

    private final int pixelSize;
    private final List<Font> faces;
    private final Paint paint;
    private final byte[] bakedGeneration;

    private AndroidSdGlyphs(int pixelSize, List<Font> faces, Typeface typeface, byte[] bakedGeneration) {
        this.pixelSize = pixelSize;
        this.faces = List.copyOf(faces);
        this.bakedGeneration = bakedGeneration;
        paint = newPaint(pixelSize);
        paint.setTypeface(typeface);
    }

    public boolean isBaked() { return bakedGeneration != null; }

    public Typeface typeface() { return paint.getTypeface(); }

    public static AndroidSdGlyphs forBakedPage(AssetManager assets, int pixelSize, GridPager.Page page)
            throws IOException {
        int index = pixelSize - ReaderProfile.MIN_TEXT_SIZE_PX;
        if (index < 0 || pixelSize > ReaderProfile.MAX_TEXT_SIZE_PX) return null;
        byte[] id = new byte[FontPackWriter.GENERATION_BYTES];
        try (DataInputStream input = new DataInputStream(assets.open("texpod/generations.bin"))) {
            int offset = index * id.length;
            if (input.skipBytes(offset) != offset) throw new IOException("Truncated baked font index");
            input.readFully(id);
        } catch (FileNotFoundException absent) {
            return null;
        }
        Font face = new Font.Builder(assets, "texpod/reader.otf").build();
        Typeface typeface = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(face).build()).build();
        AndroidSdGlyphs profile = new AndroidSdGlyphs(pixelSize, List.of(face), typeface, id);
        if (page != null) {
            for (GridPager.Token token : page.getTokens()) {
                if (!isText(token)) continue;
                PositionedGlyphs glyphs = shape(token.getText(), profile.paint);
                for (int i = 0; i < glyphs.glyphCount(); i++) {
                    if (glyphs.getGlyphId(i) == 0 || !face.equals(glyphs.getFont(i))) return null;
                }
            }
        }
        return profile;
    }

    public static AndroidSdGlyphs forPage(int pixelSize, AndroidSdGlyphs previous, GridPager.Page page)
            throws IOException {
        Paint paint = newPaint(pixelSize);
        List<Font> fonts = new ArrayList<>();
        if (previous != null && !previous.isBaked()) fonts.addAll(previous.faces);
        collectFonts(shape("A", paint), fonts);
        // Stable initial Latin/Han profile, independent of whether the first page mixes scripts.
        collectFonts(shape("汉", paint), fonts);
        if (page != null) {
            for (GridPager.Token token : page.getTokens()) {
                if (isText(token)) collectFonts(shape(token.getText(), paint), fonts);
            }
        }
        return new AndroidSdGlyphs(pixelSize, fonts, paint.getTypeface(), null);
    }

    public byte[] generation() {
        if (isBaked()) return bakedGeneration.clone();
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        digest.update(RASTER_VERSION.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) pixelSize);
        digest.update(Build.FINGERPRINT.getBytes(StandardCharsets.UTF_8));
        for (Font font : faces) {
            ByteBuffer bytes = font.getBuffer().duplicate();
            digest.update(ByteBuffer.allocate(8).putInt(bytes.remaining()).putInt(font.getTtcIndex()).array());
            digest.update(bytes);
            FontVariationAxis[] axes = font.getAxes();
            String settings = axes == null ? "" : FontVariationAxis.toFontVariationSettings(axes);
            digest.update(settings.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return Arrays.copyOf(digest.digest(), FontPackWriter.GENERATION_BYTES);
    }

    public FontPackWriter.Result exportPack(OutputStream output, byte[] generation, BooleanSupplier cancelled)
            throws IOException {
        try (Raster raster = new Raster(pixelSize)) {
            List<FontPackWriter.Face> sources = new ArrayList<>(faces.size());
            for (Font face : faces) sources.add(new FaceSource(face, raster));
            return FontPackWriter.write(output, generation, pixelSize, sources, cancelled);
        }
    }

    public byte[] encode(GridPager.Page page, long requestId, int commandLimit, int byteLimit) throws IOException {
        GlyphPageStream stream = new GlyphPageStream(commandLimit, byteLimit);
        for (GridPager.Token token : page.getTokens()) {
            if (token.getType() == GridPager.TokenType.SPACE) {
                stream.advance(1);
                continue;
            }
            if (token.getType() == GridPager.TokenType.NEWLINE) {
                stream.newline();
                continue;
            }
            String text = token.getText();
            PositionedGlyphs glyphs = shape(text, paint);
            if (ChinesePunctuation.hasRightAlignedQuote(text) && glyphs.glyphCount() == text.length()) {
                positionRightAlignedQuote(stream, glyphs);
                stream.advance(token.getAdvanceCells());
                continue;
            }
            if (glyphs.glyphCount() == 1 && Math.round(glyphs.getGlyphX(0)) == 0
                    && Math.round(glyphs.getGlyphY(0)) == 0) {
                stream.glyph(faceNumber(glyphs, 0), glyphs.getGlyphId(0));
                if (token.getAdvanceCells() > 1) stream.advance(token.getAdvanceCells() - 1);
                continue;
            }
            for (int i = 0; i < glyphs.glyphCount(); i++) {
                stream.positioned(faceNumber(glyphs, i), glyphs.getGlyphId(i), Math.round(glyphs.getGlyphX(i)),
                        Math.round(glyphs.getGlyphY(i)));
            }
            stream.advance(token.getAdvanceCells());
        }
        return stream.encode(requestId, page.getPageId());
    }

    private void positionRightAlignedQuote(GlyphPageStream stream, PositionedGlyphs glyphs) throws MissingFaceException {
        RectF bounds = new RectF();
        int quote = glyphs.glyphCount() - 1;
        glyphs.getFont(quote).getGlyphBounds(glyphs.getGlyphId(quote), paint, bounds);
        int quoteLeft = (int) Math.floor(bounds.left);
        int quoteWidth = (int) Math.ceil(bounds.right) - quoteLeft;
        int inkX = ChinesePunctuation.rightAlignedInkLeft(pixelSize, quoteWidth);
        if (quote == 1) {
            bounds.setEmpty();
            glyphs.getFont(0).getGlyphBounds(glyphs.getGlyphId(0), paint, bounds);
            int left = (int) Math.floor(bounds.left);
            int width = (int) Math.ceil(bounds.right) - left;
            int markX = Math.max(0, inkX - ChinesePunctuation.pairGap(pixelSize) - width);
            stream.positioned(faceNumber(glyphs, 0), glyphs.getGlyphId(0), markX - left,
                    Math.round(glyphs.getGlyphY(0)));
        }
        stream.positioned(faceNumber(glyphs, quote), glyphs.getGlyphId(quote), inkX - quoteLeft,
                Math.round(glyphs.getGlyphY(quote)));
    }

    private int faceNumber(PositionedGlyphs glyphs, int glyph) throws MissingFaceException {
        int index = faces.indexOf(glyphs.getFont(glyph));
        if (index < 0 || (isBaked() && glyphs.getGlyphId(glyph) == 0)) throw new MissingFaceException();
        return index;
    }

    private static void collectFonts(PositionedGlyphs glyphs, List<Font> faces) throws IOException {
        for (int i = 0; i < glyphs.glyphCount(); i++) {
            Font font = glyphs.getFont(i);
            if (faces.contains(font)) continue;
            if (faces.size() == GlyphPageStream.MAX_FACES) throw new IOException("Too many reader font faces");
            faces.add(font);
        }
    }

    private static boolean isText(GridPager.Token token) {
        return token.getType() == GridPager.TokenType.GLYPH || token.getType() == GridPager.TokenType.ASCII_RUN;
    }

    private static PositionedGlyphs shape(String text, Paint paint) {
        boolean rtl = !text.isEmpty() && new Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).isRightToLeft();
        return TextRunShaper.shapeTextRun(text, 0, text.length(), 0, text.length(), 0, 0, rtl, paint);
    }

    private static Paint newPaint(int pixelSize) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(pixelSize);
        paint.setColor(Color.BLACK);
        return paint;
    }

    private static final class FaceSource implements FontPackWriter.Face {
        private final Font font;
        private final Raster raster;
        private final int count;

        FaceSource(Font font, Raster raster) throws IOException {
            this.font = font;
            this.raster = raster;
            count = SfntGlyphCount.read(font.getBuffer(), font.getTtcIndex());
        }

        @Override public int glyphCount() { return count; }

        @Override public void measure(int glyphId, FontPackWriter.Bounds bounds) throws IOException {
            raster.bounds.setEmpty();
            font.getGlyphBounds(glyphId, raster.paint, raster.bounds);
            RectF ink = raster.bounds;
            if (ink.isEmpty()) {
                bounds.set(0, 0, 0, 0);
                return;
            }
            int left = (int) Math.floor(ink.left) - 1;
            int top = (int) Math.floor(ink.top) - 1;
            long width = (long) Math.ceil(ink.right) + 1 - left;
            long height = (long) Math.ceil(ink.bottom) + 1 - top;
            if (width <= 0 || width > MAX_DIMENSION || height <= 0 || height > MAX_DIMENSION) {
                throw new IOException("Unrenderable glyph bounds");
            }
            bounds.set(left, top, (int) width, (int) height);
        }

        @Override public void rasterize(int glyphId, FontPackWriter.Bounds bounds, byte[] record, int offset) {
            raster.draw(font, glyphId, bounds, record, offset);
        }
    }

    private static final class Raster implements AutoCloseable {
        private final Paint paint;
        private final RectF bounds = new RectF();
        private final int[] glyph = new int[1];
        private final float[] position = new float[2];
        private Bitmap bitmap;
        private Canvas canvas;
        private int[] pixels;

        Raster(int pixelSize) { paint = newPaint(pixelSize); }

        void draw(Font font, int glyphId, FontPackWriter.Bounds area, byte[] record, int offset) {
            ensureCapacity(area.width, area.height);
            glyph[0] = glyphId;
            position[0] = -area.left;
            position[1] = -area.top;
            int saved = canvas.save();
            canvas.clipRect(0, 0, area.width, area.height);
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.drawGlyphs(glyph, 0, position, 0, 1, font, paint);
            canvas.restoreToCount(saved);
            bitmap.getPixels(pixels, 0, area.width, 0, 0, area.width, area.height);
            int stride = (area.width + 7) / 8;
            for (int y = 0; y < area.height; y++) {
                for (int x = 0; x < area.width; x++) {
                    if ((pixels[y * area.width + x] >>> 24) >= 128) {
                        record[offset + y * stride + x / 8] |= (byte) (0x80 >>> (x % 8));
                    }
                }
            }
        }

        private void ensureCapacity(int width, int height) {
            if (bitmap != null && bitmap.getWidth() >= width && bitmap.getHeight() >= height) return;
            int newWidth = Math.max(width, bitmap == null ? 64 : bitmap.getWidth());
            int newHeight = Math.max(height, bitmap == null ? 64 : bitmap.getHeight());
            close();
            bitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
            pixels = new int[newWidth * newHeight];
        }

        @Override public void close() {
            if (bitmap != null) bitmap.recycle();
        }
    }
}
