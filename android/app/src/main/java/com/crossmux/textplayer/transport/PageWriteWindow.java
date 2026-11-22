package com.crossmux.textplayer.transport;

import com.crossmux.textplayer.protocol.WireProtocol;

final class PageWriteWindow {
    private static final int NO_MORE_FRAGMENTS = -1;

    private int submitted;
    private int expectedCredit = NO_MORE_FRAGMENTS;

    boolean canWrite() {
        return submitted < WireProtocol.PAGE_BURST_FRAMES;
    }

    void submitted(int nextOffset) {
        if (!canWrite()) {
            throw new IllegalStateException("Page window exhausted");
        }
        if (++submitted == WireProtocol.PAGE_BURST_FRAMES) {
            expectedCredit = nextOffset;
        }
    }

    boolean acceptCredit(int stagedBytes) {
        if (canWrite() || expectedCredit == NO_MORE_FRAGMENTS || stagedBytes != expectedCredit) {
            return false;
        }
        submitted = 0;
        expectedCredit = NO_MORE_FRAGMENTS;
        return true;
    }
}
