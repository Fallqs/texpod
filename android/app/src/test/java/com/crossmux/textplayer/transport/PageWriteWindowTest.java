package com.crossmux.textplayer.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crossmux.textplayer.protocol.WireProtocol;

import org.junit.Test;

public final class PageWriteWindowTest {
    @Test
    public void grantsOnlyExactNonfinalCreditAndRejectsEarlyOrDuplicateCredit() {
        var window = new PageWriteWindow();
        for (int i = 0; i < WireProtocol.PAGE_BURST_FRAMES; i++) {
            assertTrue(window.canWrite());
            assertFalse(window.acceptCredit(1_350));
            window.submitted((i + 1) * 226 - 6);
        }
        assertFalse(window.canWrite());
        assertFalse(window.acceptCredit(1_349));
        assertFalse(window.canWrite());
        assertTrue(window.acceptCredit(1_350));
        assertFalse(window.acceptCredit(1_350));
        assertTrue(window.canWrite());
    }

    @Test
    public void streamsBulkObjectsThroughBoundedBatchesAtLargeAndSmallMtus() {
        for (int mtu : new int[] {23, 243}) {
            int limit = WireProtocol.gattPayloadLimit(mtu);
            var cases = new WireProtocol.MessageType[] {WireProtocol.MessageType.PAGE_BITMAP,
                    WireProtocol.MessageType.PAGE_GLYPHS, WireProtocol.MessageType.FONT_CHUNK};
            var sizes = new int[] {52_288, 7 * limit - 6, WireProtocol.MAX_FONT_CHUNK_BYTES + Integer.BYTES};
            for (int j = 0; j < cases.length; j++) {
                var type = cases[j];
                var frames = WireProtocol.createFrames(type, type.isPage() ? 7 : 0, 11,
                        new byte[sizes[j]], limit);
                var window = new PageWriteWindow();
                int credits = 0;
                for (int i = 0; i < frames.size(); i++) {
                    assertTrue(window.canWrite());
                    boolean end = i + 1 == frames.size();
                    int nextOffset = end ? -1 : WireProtocol.parseFrame(frames.get(i + 1)).offset;
                    window.submitted(nextOffset);
                    if (!end && (i + 1) % WireProtocol.PAGE_BURST_FRAMES == 0) {
                        assertFalse(window.canWrite());
                        assertTrue(window.acceptCredit(nextOffset));
                        credits++;
                    } else {
                        assertFalse(window.acceptCredit(nextOffset));
                    }
                }
                assertEquals((frames.size() - 1) / WireProtocol.PAGE_BURST_FRAMES, credits);
            }
        }
    }
}
