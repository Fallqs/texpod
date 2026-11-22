package com.crossmux.textplayer.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.crossmux.textplayer.protocol.WireProtocol;

import org.junit.Test;

public final class ReaderDiagnosticsTest {
    @Test
    public void recordsEncodingWritesAndDisplayForSamePage() {
        ReaderDiagnostics diagnostics = new ReaderDiagnostics();
        diagnostics.begin(1, WireProtocol.MessageType.PAGE_GLYPHS, 600, 3, 90, 100, 120);
        diagnostics.writesComplete(1, 125, 195, true);
        var timings = new WireProtocol.PageTimings(60, 5, 2, 5, 500);
        diagnostics.displayed(1, 710, 40, timings);

        assertNull(diagnostics.pending());
        var page = diagnostics.last();
        assertEquals(20, page.encodeMs());
        assertEquals(70, page.writesMs());
        assertEquals(620, page.totalMs());
        assertEquals(WireProtocol.MessageType.PAGE_GLYPHS, page.encoding());
        assertEquals(40, page.occupiedSlots());
        org.junit.Assert.assertTrue(page.highPrioritySubmitted());
        assertSame(timings, page.reader());
    }

    @Test
    public void lateWriteCallbackCannotUpdateNextPage() {
        ReaderDiagnostics diagnostics = new ReaderDiagnostics();
        diagnostics.begin(1, WireProtocol.MessageType.PAGE_BITMAP, 48016, 213, 100, 100, 120);
        diagnostics.displayed(1, 2000, 0, null);
        diagnostics.begin(2, WireProtocol.MessageType.PAGE_GRID, 300, 2, 2010, 2010, 2015);
        diagnostics.writesComplete(1, 128, 1998, true);
        diagnostics.displayed(1, 2021, 100, null);
        assertEquals(1870, diagnostics.last().writesMs());
        org.junit.Assert.assertTrue(diagnostics.last().highPrioritySubmitted());
        assertEquals(-1, diagnostics.pending().writesMs());
        org.junit.Assert.assertFalse(diagnostics.pending().highPrioritySubmitted());
        assertEquals(2, diagnostics.pending().pushSeq());

        diagnostics.cancelPending();
        assertNull(diagnostics.pending());
        assertEquals(1, diagnostics.last().pushSeq());
        diagnostics.reset();
        assertNull(diagnostics.last());
    }
}
