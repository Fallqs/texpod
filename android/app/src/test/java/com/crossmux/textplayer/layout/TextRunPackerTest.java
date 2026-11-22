package com.crossmux.textplayer.layout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TextRunPackerTest {
    private final TextRunPacker packer = new TextRunPacker((text, start, end) -> (end - start) * 8, 24);

    @Test
    public void splitsOnlyAWordThatCannotFitTheWholeRow() {
        TextRunPacker.Run first = packer.nextRun("Texpod", 1, 1);
        assertEquals("Tex", first.getText());
        assertEquals(1, first.getAdvanceCells());
        assertFalse(first.isWrapBefore());

        TextRunPacker.Run second = packer.nextRun("pod", 1, 1);
        assertEquals("pod", second.getText());
    }

    @Test
    public void wrapsAWholeWordInsteadOfSplittingItAcrossRows() {
        TextRunPacker.Run run = packer.nextRun("Android", 1, 4);

        assertEquals("Android", run.getText());
        assertEquals(3, run.getAdvanceCells());
        assertTrue(run.isWrapBefore());
    }

    @Test
    public void measuresOnlyNearTheCurrentRowInALongRun() {
        TextRunPacker bounded = new TextRunPacker((text, start, end) -> {
            assertTrue(end - start <= 32);
            return (end - start) * 8;
        }, 24);
        String text = "a".repeat(10000);

        assertEquals("aaa", bounded.nextRun(text, 5000, 1, 4).getText());
    }

    @Test
    public void preservesWordsAndSpacesAcrossTtsChunks() {
        GridPager pager = new GridPager(16, 4, 64, packer);
        pager.append("Texp");
        pager.append("od ");
        pager.append("reads");

        assertEquals("Texpod reads", content(pager.pageAt(0)));
        assertEquals(1, pager.pageAt(0).getTokens().size());
        assertEquals(GridPager.TokenType.ASCII_RUN, pager.pageAt(0).getTokens().get(0).getType());
    }

    @Test
    public void leavesCjkCharactersOnTheOriginalGrid() {
        GridPager pager = new GridPager(16, 4, 64, packer);
        pager.append("A中B");

        assertEquals("A中B", content(pager.pageAt(0)));
        assertEquals(3, pager.pageAt(0).getTokens().size());
        assertEquals('中', pager.pageAt(0).getTokens().get(1).getCodePoint());
        assertEquals(GridPager.TokenType.ASCII_RUN, pager.pageAt(0).getTokens().get(0).getType());
    }

    @Test
    public void asciiUsesHalfCellsAndWideGlyphsWrapWithoutSplitting() {
        TextRunPacker halfCells = new TextRunPacker((text, start, end) -> (end - start) * 8, 16, 160, 2);
        GridPager pager = new GridPager(4, 2, 48, halfCells);
        pager.append("中ab文");
        GridPager.Page page = pager.pageAt(0);
        assertEquals("中ab\n文", content(page));
        assertEquals(2, page.getTokens().get(0).getAdvanceCells());
        assertEquals(1, page.getTokens().get(1).getAdvanceCells());
        assertEquals(2, page.getTokens().get(3).getAdvanceCells());
    }

    @Test
    public void keepsEnglishPunctuationInsideMeasuredRuns() {
        GridPager pager = new GridPager(16, 4, 64, packer);
        pager.append("Hello, world!");
        pager.append(" ");

        GridPager.Page page = pager.pageAt(0);
        assertEquals("Hello, world! ", content(page));
        assertEquals(1, page.getTokens().size());
        assertEquals(GridPager.TokenType.ASCII_RUN, page.getTokens().get(0).getType());
    }

    @Test
    public void pendingWordCannotOverflowAPreviewPage() {
        TextRunPacker twoLetters = new TextRunPacker((text, start, end) -> (end - start) * 8, 16);
        GridPager pager = new GridPager(4, 1, 16, twoLetters);
        pager.append("ab");
        pager.append("cdefghij");

        assertEquals(2, pager.pageCount());
        assertEquals("abcdefgh", content(pager.pageAt(0)));
        assertEquals("ij", content(pager.pageAt(1)));
        assertNull(pager.pageAt(2));
        pager.markDelivered(pager.pageAt(0).getPageId());
        assertEquals("ij", content(pager.pageAt(1)));
    }

    @Test
    public void sealingPageKeepsItsAsciiRunsStable() {
        GridPager pager = new GridPager(8, 2, 32, packer);
        pager.append("Texpod");
        long pageId = pager.pageAt(0).getPageId();
        String before = content(pager.pageAt(0));
        pager.markDelivered(pageId);
        pager.append(" reads");

        assertEquals(before, content(pager.pageAt(0)));
        assertEquals(" reads", content(pager.pageAt(1)));
        assertEquals("Texpod reads", content(pager.pageAt(0)) + content(pager.pageAt(1)));
    }

    @Test
    public void asciiMetadataConsumesRawTokenBudgetSeparatelyFromCells() {
        GridPager pager = new GridPager(8, 2, 5, packer);
        pager.append("ab中cd");

        assertEquals(2, pager.pageCount());
        assertEquals("ab中", content(pager.pageAt(0)));
        assertEquals("cd", content(pager.pageAt(1)));
    }

    @Test
    public void wordWrappingInsertsTheSameNewlineForPreviewAndTransport() {
        TextRunPacker wideLetters = new TextRunPacker((text, start, end) -> (end - start) * 12, 24);
        GridPager pager = new GridPager(4, 2, 32, wideLetters);
        pager.append("中Android");

        GridPager.Page page = pager.pageAt(0);
        assertEquals("中\nAndroid", content(page));
        assertEquals(3, page.getTokens().size());
        assertEquals(GridPager.TokenType.NEWLINE, page.getTokens().get(1).getType());
        assertEquals(4, page.getTokens().get(2).getAdvanceCells());
    }

    private String content(GridPager.Page page) {
        StringBuilder text = new StringBuilder();
        for (GridPager.Token token : page.getTokens()) {
            if (token.getType() == GridPager.TokenType.GLYPH
                    || token.getType() == GridPager.TokenType.ASCII_RUN) {
                text.append(token.getText());
            } else if (token.getType() == GridPager.TokenType.SPACE) {
                text.append(' ');
            } else {
                text.append('\n');
            }
        }
        return text.toString();
    }
}
