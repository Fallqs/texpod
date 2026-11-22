package com.crossmux.textplayer.layout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GridPagerTest {
    @Test
    public void keepsSupplementaryCodePointInOneCell() {
        GridPager pager = new GridPager(4, 2, 16);
        pager.append("A\uD83D\uDE00B");

        GridPager.Page page = pager.pageAt(0);
        assertEquals(3, page.getTokens().size());
        assertEquals(0x1f600, page.getTokens().get(1).getCodePoint());
    }

    @Test
    public void deliveredShortPageDoesNotChangeWithLaterText() {
        GridPager pager = new GridPager(4, 2, 16);
        pager.append("first");
        GridPager.Page delivered = pager.pageAt(0);
        pager.markDelivered(delivered.getPageId());
        pager.append("second");

        assertEquals(2, pager.pageCount());
        assertEquals(5, delivered.getTokens().size());
        assertEquals(6, pager.pageAt(1).getTokens().size());
        assertEquals(5, pager.pageAt(0).getTokens().size());
    }

    @Test
    public void normalizesCrLfAcrossChunks() {
        GridPager pager = new GridPager(4, 3, 16);
        pager.append("a\r");
        pager.append("\nb");

        GridPager.Page page = pager.pageAt(0);
        assertEquals(3, page.getTokens().size());
        assertEquals(GridPager.TokenType.NEWLINE, page.getTokens().get(1).getType());
    }

    @Test
    public void completesFullPagesWithoutReordering() {
        GridPager pager = new GridPager(2, 2, 5);
        pager.append("abcdefg");

        assertEquals(2, pager.pageCount());
        assertEquals(4, pager.pageAt(0).getTokens().size());
        assertEquals(3, pager.pageAt(1).getTokens().size());
        assertEquals('e', pager.pageAt(1).getTokens().get(0).getCodePoint());
        assertNull(pager.pageAt(2));
    }

    @Test
    public void compactsChinesePunctuationAcrossAppendsEvenAtPageEdge() {
        GridPager pager = new GridPager(2, 1, 16,
                new TextRunPacker((text, start, end) -> (end - start) * 5, 10));
        pager.append("说:");
        assertEquals(1, pager.pageCount());
        pager.append(" ");
        pager.append("“");
        pager.append("是。”");

        assertEquals(2, pager.pageCount());
        GridPager.Token colonAndQuote = pager.pageAt(0).getTokens().get(1);
        assertEquals(GridPager.TokenType.GLYPH, colonAndQuote.getType());
        assertEquals(1, colonAndQuote.getAdvanceCells());
        assertEquals(":“", colonAndQuote.getText());
        assertEquals("。”", pager.pageAt(1).getTokens().get(1).getText());
    }

    @Test
    public void doesNotCompactAcrossLineBreaksOrOrdinaryEnglishSpaces() {
        GridPager pager = new GridPager(10, 2, 16,
                new TextRunPacker((text, start, end) -> (end - start) * 5, 10));
        pager.append("Note: text：\n“");

        assertEquals("Note: text", pager.pageAt(0).getTokens().get(0).getText());
        assertEquals('：', pager.pageAt(0).getTokens().get(1).getCodePoint());
        assertEquals(GridPager.TokenType.NEWLINE, pager.pageAt(0).getTokens().get(2).getType());
        assertEquals('“', pager.pageAt(0).getTokens().get(3).getCodePoint());
    }

    @Test
    public void pairedBackticksAreOneOpeningQuoteWithoutChangingSourceOffsets() {
        GridPager pager = new GridPager(2, 1, 16,
                new TextRunPacker((text, start, end) -> (end - start) * 5, 10, 100, 2));
        pager.append("`");
        assertEquals("`", pager.pageAt(0).getTokens().get(0).getText());
        pager.append("`文");
        GridPager.Token quote = pager.pageAt(0).getTokens().get(0);
        assertEquals('“', quote.getCodePoint());
        assertEquals(2, quote.getAdvanceCells());
        assertEquals("``", pager.pageText(pager.pageAt(0)));
        assertEquals("文", pager.pageText(pager.pageAt(1)));
    }

    @Test
    public void openingDoubleQuoteStillAlignsToRightEdge() {
        assertTrue(ChinesePunctuation.hasRightAlignedQuote("“"));
        assertTrue(ChinesePunctuation.hasRightAlignedQuote("：“"));
        assertFalse(ChinesePunctuation.hasRightAlignedQuote("Latin“"));
        assertEquals(21, ChinesePunctuation.rightAlignedInkLeft(30, 9));
        assertEquals(0, ChinesePunctuation.rightAlignedInkLeft(30, 31));
    }

    @Test
    public void alignsMergedClosingQuotesWithoutChangingStandaloneQuotes() {
        for (String pair : new String[] {"！”", "？”", "。”", "，”", "；’", "！」", "。』", "’”"}) {
            GridPager pager = new GridPager(2, 1, 16,
                    new TextRunPacker((text, start, end) -> (end - start) * 5, 10, 100, 2));
            pager.append(pair + "文");
            GridPager.Token token = pager.pageAt(0).getTokens().get(0);
            assertEquals(pair, token.getText());
            assertEquals(2, token.getAdvanceCells());
            assertEquals("文", pager.pageAt(1).getTokens().get(0).getText());
            assertTrue(pair, ChinesePunctuation.hasRightAlignedQuote(token.getText()));
        }
        assertFalse(ChinesePunctuation.hasRightAlignedQuote("”"));
        assertFalse(ChinesePunctuation.hasRightAlignedQuote("Latin”"));
    }

    @Test
    public void newlineMovesToNextRow() {
        GridPager pager = new GridPager(2, 3, 16);
        pager.append("ab\nc");

        GridPager.Page page = pager.pageAt(0);
        assertEquals(4, page.getTokens().size());
        assertEquals(GridPager.TokenType.NEWLINE, page.getTokens().get(2).getType());
        assertTrue(page.getTokens().get(3).getType() == GridPager.TokenType.GLYPH);
    }
}
