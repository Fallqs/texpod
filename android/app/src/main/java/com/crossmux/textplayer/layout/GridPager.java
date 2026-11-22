package com.crossmux.textplayer.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class GridPager {
    public enum TokenType {
        GLYPH,
        ASCII_RUN,
        SPACE,
        NEWLINE
    }

    public static final class Token {
        private final TokenType type;
        private final int codePoint;
        private final String text;
        private final int advanceCells;

        private Token(TokenType type, int codePoint, String text, int advanceCells) {
            this.type = type;
            this.codePoint = codePoint;
            this.text = text;
            this.advanceCells = advanceCells;
        }

        private static Token asciiRun(TextRunPacker.Run run) {
            return new Token(TokenType.ASCII_RUN, -1, run.getText(), run.getAdvanceCells());
        }

        private static Token control(TokenType type, int codePoint) {
            return new Token(type, codePoint, null, type == TokenType.SPACE ? 1 : 0);
        }

        public TokenType getType() {
            return type;
        }

        public int getCodePoint() {
            return codePoint;
        }

        public int getAdvanceCells() {
            return advanceCells;
        }

        public boolean isSpanningGlyph() {
            return type == TokenType.ASCII_RUN || (type == TokenType.GLYPH && advanceCells > 1);
        }

        public String getText() {
            return text == null ? new String(Character.toChars(codePoint)) : text;
        }
    }

    public static final class Page {
        private final long pageId;
        private final List<Token> tokens;
        private final int startOffset;
        private final int endOffset;

        private Page(long pageId, List<Token> tokens, int startOffset, int endOffset) {
            this.pageId = pageId;
            this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens));
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }

        public long getPageId() {
            return pageId;
        }

        public List<Token> getTokens() {
            return tokens;
        }
    }

    private final int columns;
    private final int rows;
    private final long capacity;
    private final int rawCapacity;
    private final int maxRawTokens;
    private final TextRunPacker latinPacker;
    private final StringBuilder source = new StringBuilder();
    private final StringBuilder latinRun = new StringBuilder();
    private final ArrayList<Page> completedPages = new ArrayList<>();
    private ArrayList<Token> currentTokens = new ArrayList<>();
    private long cursor;
    private int rawTokenCount;
    private final long pageIdOffset;
    private int startOffset;
    private int laidOutOffset;
    private boolean currentDelivered;
    private boolean previousWasCarriageReturn;
    private int pendingPunctuation;
    private boolean pendingPunctuationSpace;

    public GridPager(int columns, int rows, int maxRawTokens) {
        this(columns, rows, maxRawTokens, null);
    }

    public GridPager(int columns, int rows, int maxRawTokens, TextRunPacker latinPacker) {
        this(columns, rows, maxRawTokens, latinPacker, 0);
    }

    GridPager(int columns, int rows, int maxRawTokens, TextRunPacker latinPacker, long pageIdOffset) {
        if (columns <= 0 || rows <= 0 || maxRawTokens < 2 || (latinPacker != null && maxRawTokens < 4)) {
            throw new IllegalArgumentException("Invalid grid limits");
        }
        this.columns = columns;
        this.rows = rows;
        this.capacity = columns * (long) rows;
        this.rawCapacity = maxRawTokens - 1;
        this.maxRawTokens = maxRawTokens;
        this.latinPacker = latinPacker;
        this.pageIdOffset = pageIdOffset;
    }

    public synchronized void append(CharSequence text) {
        append(text, 0, text.length());
    }

    synchronized void append(CharSequence text, int start, int end) {
        if (currentDelivered) {
            finishCurrentPage();
        }
        for (int offset = start; offset < end;) {
            int codePoint = Character.codePointAt(text, offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n' && previousWasCarriageReturn) {
                previousWasCarriageReturn = false;
                continue;
            }
            previousWasCarriageReturn = codePoint == '\r';
            int normalized = previousWasCarriageReturn ? '\n' : codePoint;
            source.appendCodePoint(normalized);
            appendCodePoint(normalized);
        }
    }

    private void appendCodePoint(int codePoint) {
        if (pendingPunctuation == '`' && codePoint == '`') {
            flushLatinRun();
            appendGlyph(ChinesePunctuation.OPEN_DOUBLE_QUOTE, null, 2);
            pendingPunctuation = 0;
            return;
        }
        if (pendingPunctuation != 0) {
            if (codePoint == ' ' && !pendingPunctuationSpace
                    && (pendingPunctuation == ':' || pendingPunctuation == '：')) {
                pendingPunctuationSpace = true;
                return;
            }
            if (ChinesePunctuation.canPair(pendingPunctuation, codePoint)) {
                flushLatinRun();
                String pair = new String(new int[] {pendingPunctuation, codePoint}, 0, 2);
                appendGlyph(-1, pair, pair.length() + (pendingPunctuationSpace ? 1 : 0));
                pendingPunctuation = 0;
                pendingPunctuationSpace = false;
                return;
            }
            flushPunctuation();
        }
        if (codePoint == '`' || ChinesePunctuation.startsPair(codePoint)) {
            pendingPunctuation = codePoint;
        } else {
            appendSingle(codePoint);
        }
    }

    private void appendSingle(int codePoint) {
        if (latinPacker != null && isAsciiTextCharacter(codePoint)) {
            latinRun.appendCodePoint(codePoint);
            return;
        }
        flushLatinRun();
        switch (codePoint) {
            case '\n':
                appendControl(TokenType.NEWLINE, '\n');
                break;
            case '\t':
            case ' ':
                appendControl(TokenType.SPACE, codePoint);
                break;
            default:
                appendGlyph(codePoint, null, Character.charCount(codePoint));
                break;
        }
    }

    private void flushPunctuation() {
        if (pendingPunctuation != 0) {
            appendSingle(pendingPunctuation);
            pendingPunctuation = 0;
            if (pendingPunctuationSpace) {
                appendSingle(' ');
                pendingPunctuationSpace = false;
            }
        }
    }

    public synchronized Page pageAt(int index) {
        if (index < 0) {
            return null;
        }
        if (index < completedPages.size()) {
            return completedPages.get(index);
        }
        List<Page> tailPages = pendingTailPages();
        int tailIndex = index - completedPages.size();
        return tailIndex < tailPages.size() ? tailPages.get(tailIndex) : null;
    }

    public synchronized int pageCount() {
        if (pendingPunctuation == 0 && latinRun.length() == 0) {
            return completedPages.size() + (currentTokens.isEmpty() ? 0 : 1);
        }
        return completedPages.size() + pendingTailPages().size();
    }

    public synchronized void markDelivered(long pageId) {
        if (pageId <= pageIdOffset + completedPages.size()) {
            return;
        }
        flushPunctuation();
        flushLatinRun();
        if (pageId == pageIdOffset + completedPages.size() + 1 && !currentTokens.isEmpty()) {
            currentDelivered = true;
        }
    }

    String pageText(Page page) {
        return source.substring(page.startOffset, page.endOffset);
    }

    GridPager reflow(int columns, int rows, int maxRawTokens, TextRunPacker latinPacker, Page... anchors) {
        GridPager rebuilt = new GridPager(columns, rows, maxRawTokens, latinPacker, pageIdOffset);
        int[] boundaries = new int[anchors.length];
        for (int i = 0; i < anchors.length; i++) {
            boundaries[i] = anchors[i] == null ? 0 : anchors[i].startOffset;
        }
        Arrays.sort(boundaries);
        int start = 0;
        for (int boundary : boundaries) {
            if (boundary > start) {
                rebuilt.append(source, start, boundary);
                rebuilt.flushPunctuation();
                rebuilt.flushLatinRun();
                rebuilt.finishCurrentPage();
                start = boundary;
            }
        }
        rebuilt.append(source, start, source.length());
        rebuilt.previousWasCarriageReturn = previousWasCarriageReturn;
        return rebuilt;
    }

    int indexOfAnchor(Page anchor) {
        if (anchor == null) {
            return -1;
        }
        int start = 0;
        int end = completedPages.size();
        while (start < end) {
            int middle = (start + end) >>> 1;
            if (completedPages.get(middle).startOffset < anchor.startOffset) {
                start = middle + 1;
            } else {
                end = middle;
            }
        }
        Page candidate = pageAt(start);
        return candidate != null && candidate.startOffset == anchor.startOffset ? start : -1;
    }

    private List<Page> pendingTailPages() {
        GridPager tail = new GridPager(columns, rows, maxRawTokens, latinPacker, pageIdOffset + completedPages.size());
        tail.currentTokens = new ArrayList<>(currentTokens);
        tail.cursor = cursor;
        tail.rawTokenCount = rawTokenCount;
        tail.startOffset = startOffset;
        tail.laidOutOffset = laidOutOffset;
        tail.latinRun.append(latinRun);
        tail.pendingPunctuation = pendingPunctuation;
        tail.pendingPunctuationSpace = pendingPunctuationSpace;
        tail.flushPunctuation();
        tail.flushLatinRun();
        tail.finishCurrentPage();
        return tail.completedPages;
    }

    private boolean isAsciiTextCharacter(int codePoint) {
        return codePoint >= 0x20 && codePoint <= 0x7e;
    }

    private void flushLatinRun() {
        if (latinRun.length() == 0) {
            return;
        }
        String text = latinRun.toString();
        for (int offset = 0; offset < text.length();) {
            int remainingCells = columns - (int) (cursor % columns);
            TextRunPacker.Run run = latinPacker.nextRun(text, offset, remainingCells, columns);
            if (run.isWrapBefore()) {
                appendControl(TokenType.NEWLINE, 0);
            }
            appendAsciiRun(run);
            offset += run.getText().length();
        }
        latinRun.setLength(0);
    }

    private void appendControl(TokenType type, int codePoint) {
        ensureRoom(1, 0);
        currentTokens.add(Token.control(type, codePoint));
        rawTokenCount++;
        laidOutOffset += codePoint == 0 ? 0 : 1;
        if (type == TokenType.NEWLINE) {
            cursor = (cursor / columns + 1) * columns;
        } else {
            cursor++;
        }
        finishIfFull();
    }

    private void appendGlyph(int codePoint, String text, int sourceLength) {
        int cells = latinPacker == null ? 1 : Math.min(columns, latinPacker.wideGlyphCells());
        if (cursor % columns + cells > columns) {
            appendControl(TokenType.NEWLINE, 0);
        }
        int cost = cells == 1 ? 1 : 3;
        ensureRoom(cost, cells);
        currentTokens.add(new Token(TokenType.GLYPH, codePoint, text, cells));
        rawTokenCount += cost;
        laidOutOffset += sourceLength;
        cursor += cells;
        finishIfFull();
    }

    private void appendAsciiRun(TextRunPacker.Run run) {
        ensureRoom(3, run.getAdvanceCells());
        if (cursor % columns + run.getAdvanceCells() > columns) {
            throw new IllegalArgumentException("ASCII run crosses a grid row");
        }
        currentTokens.add(Token.asciiRun(run));
        rawTokenCount += 3;
        laidOutOffset += run.getText().length();
        cursor += run.getAdvanceCells();
        finishIfFull();
    }

    private void ensureRoom(int tokenCost, long cellCost) {
        if (rawTokenCount + tokenCost > rawCapacity || cursor + cellCost > capacity) {
            finishCurrentPage();
        }
        if (rawTokenCount + tokenCost > rawCapacity || cursor + cellCost > capacity) {
            throw new IllegalArgumentException("Grid token exceeds page capacity");
        }
    }

    private void finishIfFull() {
        if (cursor >= capacity || rawTokenCount >= rawCapacity) {
            finishCurrentPage();
        }
    }

    private void finishCurrentPage() {
        if (currentTokens.isEmpty()) {
            return;
        }
        completedPages.add(new Page(pageIdOffset + completedPages.size() + 1, currentTokens, startOffset, laidOutOffset));
        currentTokens = new ArrayList<>();
        startOffset = laidOutOffset;
        cursor = 0;
        rawTokenCount = 0;
        currentDelivered = false;
    }
}
