package com.crossmux.textplayer.glyph;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import com.crossmux.textplayer.layout.ChinesePunctuation;
import com.crossmux.textplayer.layout.TextRunPacker;

public final class AndroidGlyphRasterizer implements GlyphProvider, TextRunPacker.Measurer {
    private static final int MAX_GLYPH_BYTES = 1024;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int maxRunWidth;

    public AndroidGlyphRasterizer(Typeface typeface, float textSizePx) {
        paint.setTypeface(typeface);
        paint.setTextSize(textSizePx);
        paint.setColor(Color.BLACK);
        paint.setStyle(Paint.Style.FILL);
        int height = Math.max(1, paint.getFontMetricsInt().bottom - paint.getFontMetricsInt().top);
        maxRunWidth = Math.max(1, (MAX_GLYPH_BYTES / height) * 8 - 8);
    }

    public int maxRunWidth() {
        return maxRunWidth;
    }

    @Override
    public int measure(String text, int start, int end) {
        return (int) Math.ceil(paint.measureText(text, start, end));
    }

    @Override
    public GlyphBitmap rasterize(GlyphKey key) {
        String text = key.getText();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (!paint.hasGlyph(new String(Character.toChars(codePoint)))) {
                return null;
            }
            offset += Character.charCount(codePoint);
        }
        Rect bounds = new Rect();
        Rect second = null;
        int gap = 0;
        int inkWidth;
        if (ChinesePunctuation.isPair(text)) {
            second = new Rect();
            paint.getTextBounds(text, 0, 1, bounds);
            paint.getTextBounds(text, 1, 2, second);
            gap = ChinesePunctuation.pairGap(Math.round(paint.getTextSize()));
            inkWidth = bounds.width() + gap + second.width();
        } else {
            paint.getTextBounds(text, 0, text.length(), bounds);
            inkWidth = bounds.width();
        }
        Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
        int width = Math.max(1, second == null ? inkWidth : Math.min(inkWidth, (int) paint.getTextSize()));
        int height = Math.max(1, metrics.bottom - metrics.top);
        int stride = (width + 7) / 8;
        if (stride * height > MAX_GLYPH_BYTES) {
            return null;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.TRANSPARENT);
            Canvas canvas = new Canvas(bitmap);
            if (second == null) {
                canvas.drawText(text, -bounds.left, -metrics.top, paint);
            } else {
                canvas.scale((float) width / inkWidth, 1f);
                canvas.drawText(text, 0, 1, -bounds.left, -metrics.top, paint);
                canvas.drawText(text, 1, 2, bounds.width() + gap - second.left, -metrics.top, paint);
            }
            int[] pixels = new int[width * height];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            byte[] packed = new byte[stride * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (Color.alpha(pixels[y * width + x]) >= 128) {
                        packed[y * stride + x / 8] |= (byte) (0x80 >> (x % 8));
                    }
                }
            }
            int offsetX = second == null ? bounds.left : 0;
            if (ChinesePunctuation.hasRightAlignedQuote(text)) {
                offsetX = ChinesePunctuation.rightAlignedInkLeft(Math.round(paint.getTextSize()), width);
            }
            return new GlyphBitmap(width, height, stride, offsetX, metrics.top, packed);
        } finally {
            bitmap.recycle();
        }
    }
}
