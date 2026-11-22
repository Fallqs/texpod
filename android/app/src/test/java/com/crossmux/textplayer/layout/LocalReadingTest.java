package com.crossmux.textplayer.layout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LocalReadingTest {
    @Test
    public void visibleTailContinuesGrowingUntilReaderMovesOn() {
        LocalReading reading = reading(4, 3);
        reading.append("first");

        GridPager.Page before = reading.currentPage();
        reading.append(" second");

        assertEquals(before.getPageId(), reading.currentPage().getPageId());
        assertEquals(12, reading.currentPage().getTokens().size());
        assertEquals(1, reading.pageCount());
    }

    @Test
    public void movingAwaySealsShortPageForConsistentBackNavigation() {
        LocalReading reading = reading(4, 1);
        reading.append("abc");
        reading.markCurrentDelivered();
        reading.append("def");

        assertEquals(2, reading.pageCount());
        assertTrue(reading.showPage(1));
        reading.append("g");
        assertTrue(reading.showPage(0));
        assertEquals(3, reading.currentPage().getTokens().size());
        assertFalse(reading.showPage(-1));
        assertFalse(reading.showPage(3));
    }

    @Test
    public void completedPageIsIdenticalToDirectPagerHistory() {
        LocalReading reading = reading(4, 3);
        GridPager pager = new GridPager(4, 3, 32);
        String text = "A paragraph\nwith multiple pages of text.";
        reading.append(text);
        pager.append(text);

        for (int index = 0; index < reading.pageCount(); index++) {
            GridPager.Page expected = pager.pageAt(index);
            GridPager.Page actual = reading.pageAt(index);
            assertEquals(expected.getPageId(), actual.getPageId());
            assertEquals(expected.getTokens().size(), actual.getTokens().size());
            for (int token = 0; token < expected.getTokens().size(); token++) {
                assertEquals(expected.getTokens().get(token).getType(), actual.getTokens().get(token).getType());
                assertEquals(expected.getTokens().get(token).getCodePoint(),
                        actual.getTokens().get(token).getCodePoint());
            }
        }
    }

    @Test
    public void sealsReaderPageEvenWhenPhoneIsBrowsingAnotherPage() {
        LocalReading reading = new LocalReading(
                new GridLayout(0, 0, 4, 1, 10, 20, 0, 10, 100, 200), 32,
                new TextRunPacker((text, start, end) -> (end - start) * 8, 16));
        reading.append("abcdefghij");

        assertEquals(0, reading.currentIndex());
        reading.markDelivered(1);
        reading.append("kl");

        StringBuilder content = new StringBuilder();
        for (GridPager.Token token : reading.pageAt(1).getTokens()) {
            content.append(token.getText());
        }
        assertEquals("ij", content.toString());
    }

    @Test
    public void rapidTurnsJumpFromDisplayedPageAndClampAtBothEnds() {
        LocalReading reading = reading(4, 1);
        reading.append("abcdefghijklmnop");
        assertEquals(3, reading.jumpTarget(0, 3));
        assertEquals(0, reading.jumpTarget(2, -3));
        assertEquals(3, reading.jumpTarget(1, 127));
        assertEquals(LocalReading.NO_PAGE, reading.jumpTarget(3, 1));
        assertEquals(LocalReading.NO_PAGE, reading.jumpTarget(0, 0));
        assertEquals(LocalReading.NO_PAGE, reading.turnTarget(3, 1));
    }

    @Test
    public void repeatTurnsFromBridgeDoNotReviveOldPages() {
        LocalReading reading = reading(4, 1);
        reading.append("old page");
        assertEquals(LocalReading.BRIDGE_PAGE, reading.switchFlowAt(0));
        assertEquals(LocalReading.NO_PAGE, reading.jumpTarget(LocalReading.BRIDGE_PAGE, 3));
        reading.append("abcdefghijklmnop");
        assertEquals(0, reading.jumpTarget(LocalReading.BRIDGE_PAGE, 1));
        assertEquals(0, reading.jumpTarget(LocalReading.BRIDGE_PAGE, -3));
        assertEquals(2, reading.jumpTarget(LocalReading.BRIDGE_PAGE, 3));
    }

    @Test
    public void emptyReadingHasNoPhantomPage() {
        LocalReading reading = reading(4, 3);

        assertEquals(0, reading.pageCount());
        assertNull(reading.currentPage());
        assertFalse(reading.showPage(0));
    }

    @Test
    public void chapterTitleIsNotSplitFromItsTitleText() {
        LocalReading reading = reading(15, 20, 128);
        reading.append("前文。");
        reading.append("第144章 真相");
        reading.append("正文开始。");

        assertEquals("前文。\n第144章 真相\n正文开始。", content(reading.currentPage()));
    }

    @Test
    public void sentenceChunksFormParagraphsButMidSentenceChunksStayJoined() {
        LocalReading reading = reading(15, 20, 128);
        reading.append("第一段。");
        reading.append("第二段。");
        reading.append("一句未");
        reading.append("完成的话。");

        assertEquals("第一段。\n第二段。\n一句未完成的话。", content(reading.currentPage()));
    }

    @Test
    public void partialRangesKeepOriginalChunkParagraphBoundaries() {
        LocalReading reading = reading(15, 20, 128);
        reading.append("前文。");
        String heading = "第144章 真相";
        reading.appendRange(heading, 0, 4);
        reading.appendRange(heading, 4, heading.length());
        String body = "正文开始。";
        reading.appendRange(body, 0, 2);
        reading.appendRange(body, 2, body.length());

        assertEquals("前文。\n第144章 真相\n正文开始。", content(reading.currentPage()));
    }

    @Test
    public void eitherTurnFromBridgeStartsNewFlowAndOldPagesAreUnreachable() {
        for (int direction : new int[] {-1, 1}) {
            LocalReading reading = reading(3, 1);
            reading.append("甲乙丙丁戊己旧尾巴");
            reading.showPage(1);
            long anchorId = reading.currentPage().getPageId();

            assertEquals(LocalReading.BRIDGE_PAGE, reading.switchFlowAt(1));
            assertEquals(0, reading.pageCount());
            assertEquals("丁戊己", content(reading.currentPage()));
            assertEquals(LocalReading.NO_PAGE, reading.turnTarget(reading.currentIndex(), direction));
            reading.append("新正文继续");

            assertEquals(anchorId, reading.currentPage().getPageId());
            assertEquals(0, reading.turnTarget(reading.currentIndex(), direction));
            assertTrue(reading.showPage(0));
            reading.discardUnusedBridge(LocalReading.NO_PAGE);
            assertNull(reading.pageAt(LocalReading.BRIDGE_PAGE));
            assertEquals("新正文", content(reading.currentPage()));
            assertEquals("继续", content(reading.pageAt(1)));
            assertEquals(LocalReading.NO_PAGE, reading.turnTarget(0, -1));
        }
    }

    @Test
    public void bridgeRemainsAvailableUntilReaderLeavesItsAcknowledgedPage() {
        LocalReading reading = reading(3, 1);
        reading.append("甲乙丙丁戊己");
        int device = reading.switchFlowAt(1);
        reading.append("新正文");
        assertTrue(reading.showPage(0));
        reading.discardUnusedBridge(device);
        assertEquals("丁戊己", content(reading.pageAt(device)));
        device = reading.turnTarget(device, -1);
        reading.discardUnusedBridge(device);
        assertNull(reading.pageAt(LocalReading.BRIDGE_PAGE));
    }

    @Test
    public void reflowKeepsTextAnchorAndRebuildsBothAdjacentPages() {
        LocalReading reading = reading(3, 1);
        reading.append("甲乙丙丁戊己庚辛壬癸子丑");
        reading.showPage(2);
        assertEquals("庚辛壬", content(reading.currentPage()));

        int device = reading.reflow(grid(6, 1), 32, null, 0);
        assertEquals(0, device);
        assertEquals(1, reading.currentIndex());
        assertEquals("庚辛壬癸子丑", content(reading.currentPage()));
        assertTrue(reading.showPage(reading.turnTarget(reading.currentIndex(), -1)));
        assertEquals("甲乙丙丁戊己", content(reading.currentPage()));
        assertTrue(reading.showPage(reading.turnTarget(reading.currentIndex(), 1)));

        reading.reflow(grid(2, 1), 32, null, LocalReading.NO_PAGE);
        assertEquals("庚辛", content(reading.currentPage()));
        assertTrue(reading.showPage(reading.turnTarget(reading.currentIndex(), 1)));
        assertEquals("壬癸", content(reading.currentPage()));
    }

    @Test
    public void reflowPreservesIndependentAnchorsAndSupplementaryCharacters() {
        LocalReading reading = reading(3, 1);
        reading.append("甲乙丙丁戊己😀新文还有");
        reading.showPage(2);

        int device = reading.reflow(grid(2, 1), 32, null, 1);

        assertEquals("丁戊", content(reading.pageAt(device)));
        assertEquals("😀新", content(reading.currentPage()));
    }

    @Test
    public void reflowDropsOldAsciiWrapsButKeepsExplicitNewlines() {
        TextRunPacker packer = new TextRunPacker((text, start, end) -> (end - start) * 6, 10);
        LocalReading reading = new LocalReading(new GridLayout(0, 0, 3, 4, 10, 20, 0, 10, 100, 200), 32, packer);
        reading.append("汉text\n字");

        reading.reflow(grid(6, 4), 32, packer, LocalReading.NO_PAGE);

        assertEquals("汉text\n字", content(reading.currentPage()));
    }

    @Test
    public void reflowWhileBridgingKeepsBothFlowsSeparate() {
        LocalReading reading = reading(4, 1);
        reading.append("旧页面尾还有");
        int reader = reading.switchFlowAt(0);
        reading.append("新页面文字");

        assertEquals(LocalReading.BRIDGE_PAGE, reading.reflow(grid(2, 1), 32, null, reader));
        assertEquals("旧页", content(reading.currentPage()));
        assertEquals(0, reading.turnTarget(reading.currentIndex(), -1));
        assertEquals("新页", content(reading.pageAt(0)));

        reading.reflow(grid(8, 1), 32, null, reader);
        assertEquals("旧页面尾", content(reading.currentPage()));
        assertEquals("新页面文字", content(reading.pageAt(0)));
    }

    private GridLayout grid(int columns, int rows) {
        return new GridLayout(0, 0, columns, rows, 10, 20, 0, 10, 100, 200);
    }

    private String content(GridPager.Page page) {
        StringBuilder text = new StringBuilder();
        for (GridPager.Token token : page.getTokens()) {
            if (token.getType() == GridPager.TokenType.GLYPH || token.getType() == GridPager.TokenType.ASCII_RUN) {
                text.append(token.getText());
            } else if (token.getType() == GridPager.TokenType.SPACE) {
                text.append(' ');
            } else {
                text.append('\n');
            }
        }
        return text.toString();
    }

    private LocalReading reading(int columns, int rows) {
        return reading(columns, rows, 32);
    }

    private LocalReading reading(int columns, int rows, int maxRawTokens) {
        return new LocalReading(new GridLayout(0, 0, columns, rows, 10, 20, 0, 10, 100, 200),
                maxRawTokens);
    }
}
