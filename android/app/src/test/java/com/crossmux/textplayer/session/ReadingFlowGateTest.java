package com.crossmux.textplayer.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public final class ReadingFlowGateTest {
    @Test
    public void allowsOnlyCurrentPagePlusThreePagesAhead() {
        ReadingFlowGate gate = new ReadingFlowGate();
        gate.accept(1, 100);

        assertTrue(gate.canConsume(1));
        gate.rendered(1, 0, 4);
        assertFalse(gate.canConsume(1));
        gate.rendered(1, 1, 4);
        assertTrue(gate.canConsume(1));
        gate.rendered(1, 1, 5);
        assertFalse(gate.canConsume(1));
        gate.rendered(1, 2, 5);
        assertTrue(gate.canConsume(1));
    }

    @Test
    public void waitsUntilTheWholeAcceptedUtteranceIsConsumed() throws Exception {
        ReadingFlowGate gate = new ReadingFlowGate();
        ReadingFlowGate.Token token = gate.accept(1, 10);
        AsyncResult result = await(gate, token);

        assertFalse(result.completedWithin(50));
        gate.consumed(1, 4);
        assertFalse(result.completedWithin(50));
        gate.consumed(1, 6);

        assertTrue(result.completedWithin(1000));
        assertTrue(result.value);
    }

    @Test
    public void movingRenderingPageReleasesConsumptionButNotCompletion() throws Exception {
        ReadingFlowGate gate = new ReadingFlowGate();
        ReadingFlowGate.Token token = gate.accept(1, 10);
        gate.rendered(1, 0, 4);
        AsyncResult result = await(gate, token);

        assertFalse(gate.canConsume(1));
        gate.rendered(1, 3, 4);
        assertTrue(gate.canConsume(1));
        assertFalse(result.completedWithin(50));
        gate.consumed(1, 10);
        assertTrue(result.completedWithin(1000));
    }

    @Test
    public void pausingCancelsWaitAndStopsBufferedConsumption() throws Exception {
        ReadingFlowGate gate = new ReadingFlowGate();
        ReadingFlowGate.Token token = gate.accept(1, 10);
        AsyncResult result = await(gate, token);

        gate.pause();

        assertTrue(result.completedWithin(1000));
        assertFalse(result.value);
        assertFalse(gate.canConsume(1));
    }

    @Test
    public void sourceSwitchDetachesOldTokenAndResetsConsumption() throws Exception {
        ReadingFlowGate gate = new ReadingFlowGate();
        ReadingFlowGate.Token oldToken = gate.accept(1, 10);
        AsyncResult oldResult = await(gate, oldToken);

        ReadingFlowGate.Token newToken = gate.accept(2, 5);
        AsyncResult newResult = await(gate, newToken);

        assertTrue(oldResult.completedWithin(1000));
        assertFalse(oldResult.value);
        gate.consumed(1, 10);
        assertFalse(newResult.completedWithin(50));
        gate.consumed(2, 5);
        assertTrue(newResult.completedWithin(1000));
        assertTrue(newResult.value);
    }

    @Test
    public void fillingTheThirdPageDoesNotRequestAnotherUtterance() throws Exception {
        ReadingFlowGate gate = new ReadingFlowGate();
        ReadingFlowGate.Token token = gate.accept(1, 10);
        AsyncResult result = await(gate, token);

        try {
            gate.rendered(1, 0, 4);
            gate.consumed(1, 10);
            assertFalse(gate.canConsume(1));
            assertFalse(result.completedWithin(50));

            gate.rendered(1, 1, 4);
            assertTrue(result.completedWithin(1000));
            assertTrue(result.value);
        } finally {
            gate.pause();
        }
    }

    @Test
    public void reversingAJumpStopsRefillWithoutRewindingConsumedText() throws Exception {
        ReadingFlowGate gate = new ReadingFlowGate();
        ReadingFlowGate.Token token = gate.accept(1, 10);
        gate.rendered(1, 0, 4);
        AsyncResult result = await(gate, token);

        try {
            gate.rendered(1, 3, 4);
            assertTrue(gate.canConsume(1));
            gate.rendered(1, 3, 6);
            gate.consumed(1, 6);

            gate.rendered(1, 1, 6);
            assertFalse(gate.canConsume(1));
            assertFalse(result.completedWithin(50));

            gate.rendered(1, 4, 6);
            assertTrue(gate.canConsume(1));
            gate.consumed(1, 4);
            assertTrue(result.completedWithin(1000));
            assertTrue(result.value);
        } finally {
            gate.pause();
        }
    }

    @Test
    public void pausingCancelsCompletionEvenWhenUtteranceIsFullyLaidOut() throws Exception {
        ReadingFlowGate gate = new ReadingFlowGate();
        ReadingFlowGate.Token token = gate.accept(1, 5);
        gate.rendered(1, 3, 9);
        gate.consumed(1, 5);
        AsyncResult result = await(gate, token);

        try {
            assertFalse(result.completedWithin(50));
        } finally {
            gate.pause();
        }
        assertTrue(result.completedWithin(1000));
        assertFalse(result.value);
    }

    private AsyncResult await(ReadingFlowGate gate, ReadingFlowGate.Token token) {
        AsyncResult result = new AsyncResult();
        result.thread = new Thread(() -> {
            result.value = gate.await(token);
            synchronized (result) {
                result.done = true;
                result.notifyAll();
            }
        }, "FlowGateTest");
        result.thread.start();
        return result;
    }

    private static final class AsyncResult {
        private boolean done;
        private boolean value;
        private Thread thread;

        private boolean completedWithin(long milliseconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
            synchronized (this) {
                while (!done && System.nanoTime() < deadline) {
                    TimeUnit.NANOSECONDS.timedWait(this, deadline - System.nanoTime());
                }
                if (done) {
                    thread.join(1000);
                }
                return done;
            }
        }
    }
}
