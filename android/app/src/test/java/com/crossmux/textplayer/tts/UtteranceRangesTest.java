package com.crossmux.textplayer.tts;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class UtteranceRangesTest {
    @Test
    public void splitsSentencesWithoutChangingText() {
        String text = "第一句。第二句！\nThird.";
        List<UtteranceRanges.Range> ranges = UtteranceRanges.split(text);

        assertEquals(3, ranges.size());
        assertRange(text, ranges.get(0), 0, 4);
        assertRange(text, ranges.get(1), 4, 9);
        assertRange(text, ranges.get(2), 9, text.length());
    }

    @Test
    public void keepsClosingQuotesAndCrLfInThePreviousRange() {
        String text = "“Hi.”\r\n然后";
        List<UtteranceRanges.Range> ranges = UtteranceRanges.split(text);

        assertEquals(2, ranges.size());
        assertRange(text, ranges.get(0), 0, 7);
        assertRange(text, ranges.get(1), 7, text.length());
    }

    @Test
    public void preservesSupplementaryUnicodeIndices() {
        String text = "😀。后";
        List<UtteranceRanges.Range> ranges = UtteranceRanges.split(text);

        assertEquals(2, ranges.size());
        assertEquals(0, ranges.get(0).getStart());
        assertEquals(3, ranges.get(0).getEnd());
        assertEquals(2, ranges.get(0).getCodePoints());
        assertEquals(3, ranges.get(1).getStart());
        assertEquals(4, ranges.get(1).getEnd());
    }

    private void assertRange(String text, UtteranceRanges.Range range, int start, int end) {
        assertEquals(start, range.getStart());
        assertEquals(end, range.getEnd());
        assertEquals(text.substring(start, end), text.substring(range.getStart(), range.getEnd()));
    }
}
