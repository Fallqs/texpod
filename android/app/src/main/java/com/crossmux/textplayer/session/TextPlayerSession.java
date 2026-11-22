package com.crossmux.textplayer.session;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public final class TextPlayerSession {
    public enum State {
        IDLE,
        ACTIVE,
        ERROR
    }

    public enum Error {
        QUEUE_FULL,
        TEXT_LIMIT,
        INTERRUPTED
    }

    public enum IngestResult {
        ACCEPTED,
        EMPTY,
        INACTIVE,
        SOURCE_MISMATCH,
        SESSION_ERROR,
        QUEUE_FULL,
        TEXT_LIMIT,
        INTERRUPTED
    }

    public static final class Snapshot {
        private final State state;
        private final Error error;
        private final String sourcePackage;
        private final int receivedChunks;
        private final int receivedCharacters;
        private final long generation;

        private Snapshot(State state, Error error, String sourcePackage, int receivedChunks,
                int receivedCharacters, long generation) {
            this.state = state;
            this.error = error;
            this.sourcePackage = sourcePackage;
            this.receivedChunks = receivedChunks;
            this.receivedCharacters = receivedCharacters;
            this.generation = generation;
        }

        public State getState() {
            return state;
        }

        public Error getError() {
            return error;
        }

        public String getSourcePackage() {
            return sourcePackage;
        }

        public int getReceivedChunks() {
            return receivedChunks;
        }

        public int getReceivedCharacters() {
            return receivedCharacters;
        }

        public long getGeneration() {
            return generation;
        }
    }

    static final class Submission {
        final long generation;
        final String text;

        Submission(long generation, String text) {
            this.generation = generation;
            this.text = text;
        }
    }

    private static final int QUEUE_CAPACITY = 32;
    private static final int MAX_TOTAL_CHARACTERS = 1_000_000;
    private static final int MAX_CHUNK_CHARACTERS = 16_000;
    private static final long QUEUE_WAIT_MILLIS = 250;
    private static final TextPlayerSession INSTANCE = new TextPlayerSession();

    private final Object lock = new Object();
    private final BlockingQueue<Submission> queue;
    private final long queueWaitMillis;
    private final int maxTotalCharacters;
    private final int maxChunkCharacters;
    private final ArrayList<String> receivedText = new ArrayList<>();

    private State state = State.IDLE;
    private Error error;
    private String sourcePackage;
    private long generation;
    private int claimedCharacters;
    private int receivedCharacters;
    private Thread worker;

    public static TextPlayerSession getInstance() {
        return INSTANCE;
    }

    private TextPlayerSession() {
        this(new ArrayBlockingQueue<>(QUEUE_CAPACITY), QUEUE_WAIT_MILLIS, MAX_TOTAL_CHARACTERS,
                MAX_CHUNK_CHARACTERS);
    }

    TextPlayerSession(BlockingQueue<Submission> queue, long queueWaitMillis, int maxTotalCharacters,
            int maxChunkCharacters) {
        this.queue = queue;
        this.queueWaitMillis = queueWaitMillis;
        this.maxTotalCharacters = maxTotalCharacters;
        this.maxChunkCharacters = maxChunkCharacters;
    }

    public void start() {
        synchronized (lock) {
            generation++;
            stopWorkerLocked();
            queue.clear();
            receivedText.clear();
            sourcePackage = null;
            claimedCharacters = 0;
            receivedCharacters = 0;
            error = null;
            state = State.ACTIVE;
            long workerGeneration = generation;
            worker = new Thread(() -> consume(workerGeneration), "TexpodIngest");
            worker.start();
        }
    }

    public void stop() {
        synchronized (lock) {
            generation++;
            state = State.IDLE;
            error = null;
            sourcePackage = null;
            claimedCharacters = 0;
            receivedCharacters = 0;
            queue.clear();
            receivedText.clear();
            stopWorkerLocked();
        }
    }

    public IngestResult submit(String callerPackage, CharSequence text) {
        final Submission submission;
        synchronized (lock) {
            if (state == State.ERROR) {
                return IngestResult.SESSION_ERROR;
            }
            if (state != State.ACTIVE) {
                return IngestResult.INACTIVE;
            }
            if (callerPackage == null || callerPackage.isEmpty()) {
                return IngestResult.SOURCE_MISMATCH;
            }
            if (sourcePackage != null && !sourcePackage.equals(callerPackage)) {
                return IngestResult.SOURCE_MISMATCH;
            }
            if (text == null || text.length() == 0) {
                return IngestResult.EMPTY;
            }
            int length = text.length();
            if (length > maxChunkCharacters || claimedCharacters > maxTotalCharacters - length) {
                failLocked(Error.TEXT_LIMIT);
                return IngestResult.TEXT_LIMIT;
            }
            claimedCharacters += length;
            sourcePackage = callerPackage;
            submission = new Submission(generation, text.toString());
        }

        final boolean offered;
        try {
            offered = queue.offer(submission, queueWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            synchronized (lock) {
                if (state == State.ACTIVE && generation == submission.generation) {
                    failLocked(Error.INTERRUPTED);
                }
            }
            return IngestResult.INTERRUPTED;
        }

        if (!offered) {
            synchronized (lock) {
                if (state == State.ACTIVE && generation == submission.generation) {
                    failLocked(Error.QUEUE_FULL);
                }
            }
            return IngestResult.QUEUE_FULL;
        }
        return IngestResult.ACCEPTED;
    }

    public Snapshot getSnapshot() {
        synchronized (lock) {
            return new Snapshot(state, error, sourcePackage, receivedText.size(), receivedCharacters, generation);
        }
    }

    public List<String> getReceivedText() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(receivedText));
        }
    }

    private void consume(long workerGeneration) {
        while (true) {
            final Submission submission;
            try {
                submission = queue.take();
            } catch (InterruptedException interrupted) {
                return;
            }
            synchronized (lock) {
                if (workerGeneration != generation || state != State.ACTIVE
                        || submission.generation != generation) {
                    continue;
                }
                receivedText.add(submission.text);
                receivedCharacters += submission.text.length();
            }
        }
    }

    private void failLocked(Error newError) {
        state = State.ERROR;
        error = newError;
        queue.clear();
        stopWorkerLocked();
    }

    private void stopWorkerLocked() {
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }
}
