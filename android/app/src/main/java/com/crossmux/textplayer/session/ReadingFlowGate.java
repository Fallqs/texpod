package com.crossmux.textplayer.session;

public final class ReadingFlowGate {
    public static final int MAX_PAGE_LEAD = 3;

    public static final class Token {
        private final long sourceGeneration;
        private final long targetCharacters;
        private final long controlGeneration;

        private Token(long sourceGeneration, long targetCharacters, long controlGeneration) {
            this.sourceGeneration = sourceGeneration;
            this.targetCharacters = targetCharacters;
            this.controlGeneration = controlGeneration;
        }
    }

    private long sourceGeneration = -1;
    private long acceptedCharacters;
    private long consumedCharacters;
    private long controlGeneration;
    private int renderedPageIndex;
    private int pageCount;
    private boolean paused = true;

    public synchronized Token accept(long generation, int characters) {
        if (generation < 0 || characters <= 0) {
            throw new IllegalArgumentException("Invalid flow acceptance");
        }
        if (sourceGeneration != generation) {
            sourceGeneration = generation;
            acceptedCharacters = 0;
            consumedCharacters = 0;
            renderedPageIndex = 0;
            pageCount = 0;
        }
        acceptedCharacters += characters;
        paused = false;
        controlGeneration++;
        notifyAll();
        return new Token(generation, acceptedCharacters, controlGeneration);
    }

    public synchronized boolean canConsume(long generation) {
        return !paused && sourceGeneration == generation && hasRoomAhead();
    }

    private boolean hasRoomAhead() {
        return pageCount == 0 || pageCount - 1 - renderedPageIndex < MAX_PAGE_LEAD;
    }

    public synchronized void consumed(long generation, int characters) {
        if (sourceGeneration != generation || characters <= 0) {
            return;
        }
        consumedCharacters = Math.min(acceptedCharacters, consumedCharacters + characters);
        notifyAll();
    }

    public synchronized void rendered(long generation, int pageIndex, int pages) {
        if (sourceGeneration != generation || pageIndex < 0 || pages < 0) {
            return;
        }
        renderedPageIndex = pageIndex;
        pageCount = pages;
        notifyAll();
    }

    public synchronized void pause() {
        paused = true;
        controlGeneration++;
        notifyAll();
    }

    public boolean await(Token token) {
        if (token == null) {
            return false;
        }
        synchronized (this) {
            while (token.controlGeneration == controlGeneration
                    && (consumedCharacters < token.targetCharacters || !hasRoomAhead())) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return token.controlGeneration == controlGeneration && token.sourceGeneration == sourceGeneration
                    && consumedCharacters >= token.targetCharacters;
        }
    }
}
