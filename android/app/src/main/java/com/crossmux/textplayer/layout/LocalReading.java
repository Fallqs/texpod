package com.crossmux.textplayer.layout;

public final class LocalReading {
    public static final int NO_PAGE = -1;
    public static final int BRIDGE_PAGE = -2;

    private GridLayout layout;
    private int maxRawTokens;
    private TextRunPacker latinPacker;
    private GridPager pager;
    private int currentIndex;
    private String previousChunk;
    private GridPager.Page bridgePage;
    private String bridgeText;

    public LocalReading(GridLayout layout, int maxRawTokens) {
        this(layout, maxRawTokens, null);
    }

    public LocalReading(GridLayout layout, int maxRawTokens, TextRunPacker latinPacker) {
        this.layout = layout;
        this.maxRawTokens = maxRawTokens;
        this.latinPacker = latinPacker;
        pager = newPager(0);
    }

    public synchronized void append(CharSequence text) {
        appendRange(text, 0, text.length());
    }

    public synchronized void appendRange(CharSequence text, int start, int end) {
        if (text == null || start < 0 || end < start || end > text.length()) {
            throw new IllegalArgumentException("Invalid text range");
        }
        if (start == end) {
            return;
        }
        String chunk = text.toString();
        if (start == 0 && ParagraphRules.paragraphBefore(previousChunk, chunk)) {
            pager.append("\n");
        }
        pager.append(chunk, start, end);
        if (end == chunk.length()) {
            previousChunk = chunk;
        }
    }

    public synchronized GridPager.Page currentPage() {
        return pageAt(currentIndex);
    }

    public synchronized GridPager.Page pageAt(int index) {
        return index == BRIDGE_PAGE ? bridgePage : pager.pageAt(index);
    }

    public synchronized int currentIndex() {
        return currentIndex;
    }

    public synchronized int pageCount() {
        return pager.pageCount();
    }

    public synchronized int reflow(GridLayout layout, int maxRawTokens, TextRunPacker latinPacker, int readerIndex) {
        GridPager.Page previewAnchor = pager.pageAt(currentIndex);
        GridPager.Page readerAnchor = pager.pageAt(readerIndex);
        this.layout = layout;
        this.maxRawTokens = maxRawTokens;
        this.latinPacker = latinPacker;
        pager = pager.reflow(layout.columns, layout.rows, maxRawTokens, latinPacker, previewAnchor, readerAnchor);
        if (currentIndex != BRIDGE_PAGE) {
            currentIndex = previewAnchor == null ? 0 : pager.indexOfAnchor(previewAnchor);
        }
        if (bridgePage != null) {
            GridPager bridge = newPager(bridgePage.getPageId() - 1);
            bridge.append(bridgeText);
            bridgePage = bridge.pageAt(0);
        }
        return readerIndex == BRIDGE_PAGE ? BRIDGE_PAGE : pager.indexOfAnchor(readerAnchor);
    }

    public synchronized int switchFlowAt(int anchorIndex) {
        GridPager.Page anchor = pageAt(anchorIndex);
        bridgeText = anchor == null ? null : anchorIndex == BRIDGE_PAGE ? bridgeText : pager.pageText(anchor);
        bridgePage = anchor;
        pager = newPager(anchor == null ? 0 : anchor.getPageId());
        previousChunk = null;
        currentIndex = anchor == null ? 0 : BRIDGE_PAGE;
        return currentIndex;
    }

    public synchronized int turnTarget(int index, int direction) {
        if (direction != -1 && direction != 1) {
            return NO_PAGE;
        }
        int target = index == BRIDGE_PAGE ? 0 : index + direction;
        return target >= 0 && target < pager.pageCount() ? target : NO_PAGE;
    }

    public synchronized int jumpTarget(int index, int offset) {
        int count = pager.pageCount();
        if (offset == 0 || offset < -127 || offset > 127 || count == 0 || pageAt(index) == null) return NO_PAGE;
        int base = index == BRIDGE_PAGE ? 0 : index;
        int movement = index == BRIDGE_PAGE && offset > 0 ? offset - 1 : offset;
        int target = Math.max(0, Math.min(count - 1, base + movement));
        return target == index ? NO_PAGE : target;
    }

    public synchronized boolean showPage(int index) {
        if (index < 0 || index >= pager.pageCount() || index == currentIndex) {
            return false;
        }
        markDelivered(currentIndex);
        currentIndex = index;
        return true;
    }

    public synchronized void discardUnusedBridge(int readerIndex) {
        if (currentIndex != BRIDGE_PAGE && readerIndex != BRIDGE_PAGE) {
            bridgePage = null;
            bridgeText = null;
        }
    }

    public synchronized void markDelivered(int index) {
        GridPager.Page page = pager.pageAt(index);
        if (page != null) {
            pager.markDelivered(page.getPageId());
        }
    }

    public synchronized void markCurrentDelivered() {
        markDelivered(currentIndex);
    }

    private GridPager newPager(long pageIdOffset) {
        return new GridPager(layout.columns, layout.rows, maxRawTokens, latinPacker, pageIdOffset);
    }
}
