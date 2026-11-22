package com.crossmux.textplayer.layout;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ParagraphRulesTest {
    @Test
    public void chapterTitleStaysWholeAndStartsItsOwnParagraph() {
        assertTrue(ParagraphRules.paragraphBefore("上一章正文", "第144章 真相"));
        assertTrue(ParagraphRules.paragraphBefore("第144章 真相", "正文开始"));
        assertTrue(ParagraphRules.isChapterHeading("Chapter 144 Truth"));
        assertTrue(ParagraphRules.isChapterHeading("第144章 真相"));
    }

    @Test
    public void sentenceEndStartsParagraphIncludingClosingQuotes() {
        assertTrue(ParagraphRules.paragraphBefore("第一段。", "第二段"));
        assertTrue(ParagraphRules.paragraphBefore("结束了。”", "下一段"));
        assertTrue(ParagraphRules.paragraphBefore("A sentence. ", "Next"));
    }

    @Test
    public void midSentenceChunksAreNotForcedIntoParagraphs() {
        assertFalse(ParagraphRules.paragraphBefore("一句未", "完成的话"));
        assertFalse(ParagraphRules.paragraphBefore("Hello ", "world"));
        assertFalse(ParagraphRules.paragraphBefore("abc", "def"));
    }

    @Test
    public void explicitNewlinesAreNotDuplicated() {
        assertFalse(ParagraphRules.paragraphBefore("正文\n", "下一段"));
        assertFalse(ParagraphRules.paragraphBefore("正文", "\n下一段"));
        assertFalse(ParagraphRules.paragraphBefore("正文\r", "\n下一段"));
    }
}
