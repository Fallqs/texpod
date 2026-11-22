package com.crossmux.textplayer.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.crossmux.textplayer.protocol.WireProtocol;

import org.junit.Test;

public final class TrafficMonitorTest {
    @Test
    public void recordsTxAndRxAtCallbackTimeWithoutRetainingValues() {
        var monitor = new TrafficMonitor();
        monitor.reset(100);
        byte[] frame = new byte[240];
        WireProtocol.writeFrameHeader(frame, WireProtocol.MessageType.PAGE_BITMAP, 0, 2, 0xffffffffL, 220);
        monitor.written(frame, 110, 172, 175, 0);
        var notification = WireProtocol.createFrames(WireProtocol.MessageType.DISPLAYED,
                2, 0xffffffffL, new byte[52], 226).get(0);
        monitor.received(notification, 720, 723);
        monitor.written(frame, 724, 725, 727, 133);
        frame[1] = 0;

        var history = monitor.latest(3);
        assertEquals(3, history.total());
        assertEquals(0, history.omitted());
        assertEquals(new TrafficMonitor.Packet(1, true, 72, 240, 5, 0xffffffffL, 220, 62, 3, 0),
                history.packets().get(0));
        assertEquals(new TrafficMonitor.Packet(2, false, 620, 72, 7, 0xffffffffL, 0, -1, 3, 0),
                history.packets().get(1));
        assertEquals(133, history.packets().get(2).gattStatus());
    }

    @Test
    public void retainsOnlyNewestRowsAndResetsForNewConnection() {
        var monitor = new TrafficMonitor();
        monitor.reset(1);
        for (int i = 0; i < TrafficMonitor.CAPACITY + 4; i++) {
            monitor.received(new byte[0], 2 + i, 3 + i);
        }
        var snapshot = monitor.latest(TrafficMonitor.CAPACITY);
        assertEquals(TrafficMonitor.CAPACITY, snapshot.packets().size());
        assertEquals(4, snapshot.omitted());
        assertEquals(5, snapshot.packets().get(0).sequence());
        assertEquals(snapshot.total(), monitor.latest(1).packets().get(0).sequence());
        long revision = monitor.revision();
        monitor.reset(10_000);
        assertTrue(monitor.revision() > revision);
        assertEquals(0, monitor.latest(3).total());
        monitor.unfinishedWrite(new byte[0], 10_001, TrafficMonitor.NOT_SUBMITTED);
        monitor.unfinishedWrite(new byte[0], 10_002, TrafficMonitor.NO_CALLBACK);
        var afterReset = monitor.latest(2).packets();
        assertEquals(1, afterReset.get(0).sequence());
        assertEquals(-1, afterReset.get(0).offset());
        assertTrue(afterReset.get(0).notSubmitted());
        assertTrue(afterReset.get(1).noCallback());
    }
}
