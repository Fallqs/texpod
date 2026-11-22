package com.crossmux.textplayer.glyph;

import com.crossmux.textplayer.layout.GridPager;

import java.util.Objects;

public final class GlyphKey {
    private final int codePoint;
    private final String text;
    private final String fontId;
    private final float textSizePx;
    private final int style;

    public GlyphKey(int codePoint, String fontId, float textSizePx, int style) {
        this(codePoint, null, fontId, textSizePx, style);
    }

    public GlyphKey(String text, String fontId, float textSizePx, int style) {
        this(-1, text, fontId, textSizePx, style);
    }

    private GlyphKey(int codePoint, String text, String fontId, float textSizePx, int style) {
        this.codePoint = codePoint;
        this.text = text;
        this.fontId = Objects.requireNonNull(fontId);
        this.textSizePx = textSizePx;
        this.style = style;
    }

    static GlyphKey forToken(GridPager.Token token, String fontId, float textSizePx, int style) {
        return token.getCodePoint() < 0 ? new GlyphKey(token.getText(), fontId, textSizePx, style)
                : new GlyphKey(token.getCodePoint(), fontId, textSizePx, style);
    }

    public int getCodePoint() {
        return codePoint;
    }

    public String getText() {
        return text == null ? new String(Character.toChars(codePoint)) : text;
    }

    public String getFontId() {
        return fontId;
    }

    public float getTextSizePx() {
        return textSizePx;
    }

    public int getStyle() {
        return style;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof GlyphKey)) {
            return false;
        }
        GlyphKey key = (GlyphKey) other;
        return codePoint == key.codePoint && Float.compare(textSizePx, key.textSizePx) == 0
                && style == key.style && fontId.equals(key.fontId) && Objects.equals(text, key.text);
    }

    @Override
    public int hashCode() {
        return Objects.hash(codePoint, text, fontId, textSizePx, style);
    }
}
