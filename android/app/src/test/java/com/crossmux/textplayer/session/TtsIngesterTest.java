package com.crossmux.textplayer.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public final class TtsIngesterTest {
    private TextPlayerSession session;
    private TtsIngester ingester;

    @After
    public void tearDown() {
        if (ingester != null) {
            ingester.clear();
        }
    }

    @Test
    public void acceptsAnyAppWithoutSelectingOne() throws Exception {
        newIngester(1000, 100);

        assertEquals(TextPlayerSession.IngestResult.ACCEPTED, ingester.ingest("one.reader", "one"));
        awaitCharacters(3);
        assertEquals("one.reader", session.getSnapshot().getSourcePackage());
        assertEquals(TextPlayerSession.IngestResult.ACCEPTED, ingester.ingest("one.reader", " two"));
        awaitCharacters(7);
    }

    @Test
    public void switchingAppsStartsCleanSessionWithoutMixingText() throws Exception {
        newIngester(1000, 100);
        ingester.ingest("first.reader", "old text");
        awaitCharacters(8);

        assertEquals(TextPlayerSession.IngestResult.ACCEPTED, ingester.ingest("another.reader", "new text"));
        awaitCharacters(8);

        assertEquals(1, session.getReceivedText().size());
        assertEquals("new text", session.getReceivedText().get(0));
        assertEquals("another.reader", session.getSnapshot().getSourcePackage());
    }

    @Test
    public void detachedAppCannotTakeBackAnActiveFlow() throws Exception {
        newIngester(1000, 100);
        ingester.ingest("first.reader", "old text");
        awaitCharacters(8);
        ingester.ingest("another.reader", "new text");
        awaitCharacters(8);

        assertEquals(TextPlayerSession.IngestResult.SOURCE_MISMATCH,
                ingester.ingest("first.reader", "stale text"));
        assertEquals("another.reader", session.getSnapshot().getSourcePackage());
        assertEquals(8, session.getSnapshot().getReceivedCharacters());
    }

    @Test
    public void stoppedFlowAllowsAnySourceToAttachWithoutClearingManually() throws Exception {
        newIngester(1000, 100);
        ingester.ingest("first.reader", "old text");
        awaitCharacters(8);
        ingester.ingest("another.reader", "new text");
        awaitCharacters(8);

        assertTrue(ingester.pause("another.reader"));
        assertEquals(TextPlayerSession.IngestResult.ACCEPTED, ingester.ingest("first.reader", "return"));
        awaitCharacters(6);
        assertEquals("first.reader", session.getSnapshot().getSourcePackage());
        assertEquals("return", session.getReceivedText().get(0));
    }

    @Test
    public void restartingAfterStopStartsNewFlowEvenFromTheSameApp() throws Exception {
        newIngester(1000, 100);
        ingester.ingest("reader", "old text");
        awaitCharacters(8);
        long oldGeneration = session.getSnapshot().getGeneration();

        assertTrue(ingester.pause("reader"));
        assertEquals(TextPlayerSession.IngestResult.ACCEPTED, ingester.ingest("reader", "new"));
        awaitCharacters(3);
        assertTrue(session.getSnapshot().getGeneration() > oldGeneration);
        assertEquals("new", session.getReceivedText().get(0));
    }

    @Test
    public void stalePauseDoesNotDetachTheNewFlow() throws Exception {
        newIngester(1000, 100);
        ingester.ingest("first.reader", "old text");
        ingester.ingest("another.reader", "new text");

        assertFalse(ingester.pause("first.reader"));
        assertEquals(TextPlayerSession.IngestResult.SOURCE_MISMATCH,
                ingester.ingest("first.reader", "stale"));
    }

    @Test
    public void nextUtteranceCanRecoverFromCaptureError() throws Exception {
        newIngester(4, 4);

        assertEquals(TextPlayerSession.IngestResult.TEXT_LIMIT, ingester.ingest("reader", "too long"));
        assertEquals(TextPlayerSession.State.ERROR, session.getSnapshot().getState());
        assertEquals(TextPlayerSession.IngestResult.ACCEPTED, ingester.ingest("reader", "ok"));
        awaitCharacters(2);
        assertEquals(TextPlayerSession.State.ACTIVE, session.getSnapshot().getState());
    }

    @Test
    public void emptyRequestDoesNotStartOrEraseCurrentReading() throws Exception {
        newIngester(1000, 100);

        assertEquals(TextPlayerSession.IngestResult.EMPTY, ingester.ingest("reader", ""));
        assertEquals(TextPlayerSession.State.IDLE, session.getSnapshot().getState());
        ingester.ingest("reader", "text");
        awaitCharacters(4);
        assertEquals(TextPlayerSession.IngestResult.EMPTY, ingester.ingest("other", null));
        assertEquals(4, session.getSnapshot().getReceivedCharacters());
    }

    @Test
    public void sampleAndClearDoNotRequireBluetooth() throws Exception {
        newIngester(1000, 100);

        ingester.loadSample("Local sample");
        awaitCharacters(12);
        assertEquals("texpod.sample", session.getSnapshot().getSourcePackage());
        ingester.clear();
        assertEquals(0, session.getReceivedText().size());
        assertEquals(TextPlayerSession.State.IDLE, session.getSnapshot().getState());
    }

    private void newIngester(int maxCharacters, int maxChunk) {
        session = new TextPlayerSession(new ArrayBlockingQueue<>(8), 10, maxCharacters, maxChunk);
        ingester = new TtsIngester(session);
    }

    private void awaitCharacters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (session.getSnapshot().getReceivedCharacters() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, session.getSnapshot().getReceivedCharacters());
    }
}
