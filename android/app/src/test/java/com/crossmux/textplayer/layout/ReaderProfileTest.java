package com.crossmux.textplayer.layout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReaderProfileTest {
    @Test
    public void defaultProfileUsesHalfCellsWithTheSameCjkPitch() {
        GridLayout layout = ReaderProfile.phonePreview();

        assertEquals(30, layout.columns);
        assertEquals(20, layout.rows);
        assertEquals(14, layout.cellWidth);
        assertEquals(36, layout.cellHeight);
        assertEquals(26, layout.baselineOffsetY);
        assertTrue(layout.isValid());
    }

    @Test
    public void largerTextScalesCellsAndFitsTheViewport() {
        GridLayout layout = ReaderProfile.phonePreview(36);

        assertEquals(20, layout.cellWidth);
        assertEquals(54, layout.cellHeight);
        assertEquals(38, layout.baselineOffsetY);
        assertEquals(21, layout.columns);
        assertEquals(13, layout.rows);
        assertTrue(layout.isValid());
    }

    @Test
    public void fontSizeIsBounded() {
        assertEquals(18, ReaderProfile.clampTextSize(1));
        assertEquals(24, ReaderProfile.clampTextSize(24));
        assertEquals(36, ReaderProfile.clampTextSize(100));
    }
}
