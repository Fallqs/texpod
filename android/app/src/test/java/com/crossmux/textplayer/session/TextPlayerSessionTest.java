package com.crossmux.textplayer.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class TextPlayerSessionTest {
    private TextPlayerSession session;

    @After
    public void tearDown() {
        if (session != null) {
            session.stop();
        }
    }

    @Test
    public void acceptsAnyFirstSourceAndPreservesOrder() throws Exception {
        session = newSession(8, 1_000, 100);

        assertEquals(TextPlayerSession.IngestResult.ACCEPTED,
                session.submit("reader.app", "first"));
        assertEquals(TextPlayerSession.IngestResult.ACCEPTED,
                session.submit("reader.app", "second"));
        awaitCharacters(11);

        assertEquals(2, session.getReceivedText().size());
        assertEquals("first", session.getReceivedText().get(0));
        assertEquals("second", session.getReceivedText().get(1));
    }

    @Test
    public void stopAndRestartIsolatesOldQueueAndText() throws Exception {
        session = newSession(8, 1_000, 100);
        session.submit("reader.app", "old");
        awaitCharacters(3);

        session.stop();
        assertEquals(TextPlayerSession.IngestResult.INACTIVE,
                session.submit("reader.app", "inactive"));
        assertTrue(session.getReceivedText().isEmpty());

        session.start();
        session.submit("reader.app", "new");
        awaitCharacters(3);
        assertEquals(1, session.getReceivedText().size());
        assertEquals("new", session.getReceivedText().get(0));
    }

    @Test
    public void emptyTextDoesNotConsumeQuota() {
        session = newSession(8, 1_000, 100);

        assertEquals(TextPlayerSession.IngestResult.EMPTY, session.submit("reader.app", ""));
        assertEquals(0, session.getSnapshot().getReceivedChunks());
        assertEquals(TextPlayerSession.State.ACTIVE, session.getSnapshot().getState());
    }

    @Test
    public void textLimitStopsCaptureExplicitly() {
        session = newSession(8, 5, 100);

        assertEquals(TextPlayerSession.IngestResult.TEXT_LIMIT,
                session.submit("reader.app", "123456"));
        assertEquals(TextPlayerSession.State.ERROR, session.getSnapshot().getState());
        assertEquals(TextPlayerSession.Error.TEXT_LIMIT, session.getSnapshot().getError());
        assertTrue(session.getReceivedText().isEmpty());
        assertEquals(TextPlayerSession.IngestResult.SESSION_ERROR,
                session.submit("reader.app", "later"));
    }

    @Test
    public void fullQueueStopsCaptureInsteadOfDroppingText() throws Exception {
        CountDownLatch consumerStarted = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        ArrayBlockingQueue<TextPlayerSession.Submission> blockedQueue =
                new ArrayBlockingQueue<TextPlayerSession.Submission>(1) {
                    @Override
                    public TextPlayerSession.Submission take() throws InterruptedException {
                        TextPlayerSession.Submission taken = super.take();
                        consumerStarted.countDown();
                        releaseConsumer.await();
                        return taken;
                    }
                };
        session = new TextPlayerSession(blockedQueue, 10, 1_000, 100);
        session.start();

        assertEquals(TextPlayerSession.IngestResult.ACCEPTED,
                session.submit("reader.app", "being consumed"));
        assertTrue(consumerStarted.await(1, TimeUnit.SECONDS));
        assertEquals(TextPlayerSession.IngestResult.ACCEPTED,
                session.submit("reader.app", "queued"));
        assertEquals(TextPlayerSession.IngestResult.QUEUE_FULL,
                session.submit("reader.app", "must not disappear"));
        assertEquals(TextPlayerSession.State.ERROR, session.getSnapshot().getState());
        assertEquals(TextPlayerSession.Error.QUEUE_FULL, session.getSnapshot().getError());
        releaseConsumer.countDown();
    }

    private TextPlayerSession newSession(int queueCapacity, int maxTotalCharacters,
            int maxChunkCharacters) {
        TextPlayerSession newSession = new TextPlayerSession(
                new ArrayBlockingQueue<>(queueCapacity), 10, maxTotalCharacters, maxChunkCharacters);
        newSession.start();
        return newSession;
    }

    private void awaitCharacters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (session.getSnapshot().getReceivedCharacters() != expected
                && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, session.getSnapshot().getReceivedCharacters());
    }
}
