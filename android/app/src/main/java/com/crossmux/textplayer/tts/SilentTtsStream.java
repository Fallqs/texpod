package com.crossmux.textplayer.tts;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SilentTtsStream {
    public interface Sink {
        int start(int sampleRate, int audioFormat, int channelCount);

        int getMaxBufferSize();

        int audioAvailable(byte[] buffer, int offset, int length);

        void rangeStart(int frame, int start, int end);

        int done();

        void error();
    }

    public static final int SUCCESS = 0;
    public static final int ERROR = -1;
    public static final int STOPPED = -2;

    private static final int SAMPLE_RATE = 16_000;
    private static final int PCM_16BIT = 2;
    private static final int MIN_FRAMES = 3_200;
    private static final int MAX_FRAMES = 16_000;
    private static final int FRAMES_PER_CODEPOINT = 80;
    private static final byte[] SILENCE = new byte[640];

    private final AtomicBoolean stopped = new AtomicBoolean();

    public void begin() {
        stopped.set(false);
    }

    public void stop() {
        stopped.set(true);
    }

    public void complete(CharSequence text, int speechRate, Sink sink) {
        if (stopped.get()) {
            return;
        }
        int startStatus = sink.start(SAMPLE_RATE, PCM_16BIT, 1);
        if (startStatus != SUCCESS) {
            fail(sink, startStatus);
            return;
        }
        if (text.length() == 0) {
            if (!stopped.get()) {
                sink.done();
            }
            return;
        }

        List<UtteranceRanges.Range> ranges = UtteranceRanges.split(text);
        int totalFrames = frameCount(text, ranges.size(), speechRate);
        int frame = 0;
        int consumedCodePoints = 0;
        int totalCodePoints = Character.codePointCount(text, 0, text.length());
        for (int index = 0; index < ranges.size(); index++) {
            UtteranceRanges.Range range = ranges.get(index);
            consumedCodePoints += range.getCodePoints();
            int nextFrame = index == ranges.size() - 1 ? totalFrames
                    : (int) ((long) totalFrames * consumedCodePoints / totalCodePoints);
            if (stopped.get()) {
                return;
            }
            sink.rangeStart(frame, range.getStart(), range.getEnd());
            if (!writeFrames(sink, nextFrame - frame)) {
                return;
            }
            frame = nextFrame;
        }
        if (!stopped.get()) {
            sink.done();
        }
    }

    private int frameCount(CharSequence text, int rangeCount, int speechRate) {
        long codePoints = Character.codePointCount(text, 0, text.length());
        long requested = codePoints * FRAMES_PER_CODEPOINT * 100L / Math.max(1, speechRate);
        return (int) Math.min(MAX_FRAMES, Math.max(MIN_FRAMES, Math.max(rangeCount, requested)));
    }

    private boolean writeFrames(Sink sink, int frames) {
        int remaining = frames * 2;
        while (remaining > 0) {
            if (stopped.get()) {
                return false;
            }
            int maxBuffer = sink.getMaxBufferSize() & ~1;
            int length = Math.min(remaining, Math.min(SILENCE.length, maxBuffer));
            if (length < 2) {
                sink.error();
                return false;
            }
            int status = sink.audioAvailable(SILENCE, 0, length);
            if (status != SUCCESS) {
                return fail(sink, status);
            }
            remaining -= length;
        }
        return true;
    }

    private boolean fail(Sink sink, int status) {
        if (status == STOPPED) {
            stopped.set(true);
        }
        if (!stopped.get()) {
            sink.error();
        }
        return false;
    }
}
