package com.crossmux.textplayer.preview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import com.crossmux.textplayer.glyph.PageBitmap;

public final class ReaderPreviewView extends View {
    private static final int PAPER = Color.rgb(250, 249, 245);
    private static final int INK = Color.rgb(29, 28, 26);

    private final Paint bitmapPaint = new Paint(Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect destination = new Rect();
    private PageBitmap page;
    private Bitmap surface;
    private int[] colors;

    public ReaderPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bitmapPaint.setFilterBitmap(true);
    }

    public void setPage(PageBitmap newPage) {
        if (page == newPage) {
            return;
        }
        page = newPage;
        if (page == null) {
            releaseSurface();
        } else {
            updateSurface();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(PAPER);
        if (surface == null || page == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float scale = Math.min((float) getWidth() / page.getWidth(), (float) getHeight() / page.getHeight());
        int width = Math.round(page.getWidth() * scale);
        int height = Math.round(page.getHeight() * scale);
        int left = (getWidth() - width) / 2;
        int top = (getHeight() - height) / 2;
        destination.set(left, top, left + width, top + height);
        canvas.drawBitmap(surface, null, destination, bitmapPaint);
    }

    private void updateSurface() {
        if (surface == null || surface.getWidth() != page.getWidth()
                || surface.getHeight() != page.getHeight()) {
            releaseSurface();
            // One reusable phone-only surface, capped at 1,000,000 pixels by capability validation.
            surface = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
            colors = new int[page.getWidth() * page.getHeight()];
        }
        for (int y = 0; y < page.getHeight(); y++) {
            int row = y * page.getWidth();
            for (int x = 0; x < page.getWidth(); x++) {
                colors[row + x] = page.isInk(x, y) ? INK : PAPER;
            }
        }
        surface.setPixels(colors, 0, page.getWidth(), 0, 0, page.getWidth(), page.getHeight());
    }

    private void releaseSurface() {
        if (surface != null) {
            surface.recycle();
            surface = null;
        }
        colors = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseSurface();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (page != null) {
            updateSurface();
            invalidate();
        }
    }
}
