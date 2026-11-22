package com.crossmux.textplayer.tts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class SilentTtsStreamTest {
    @Test
    public void emitsOriginalRangesAndCompletesAfterAudio() {
        SilentTtsStream stream = new SilentTtsStream();
        FakeSink sink = new FakeSink(stream);
        stream.begin();

        stream.complete("第一句。第二句！", 100, sink);

        assertEquals("start", sink.events.get(0));
        assertEquals("range:0:0:4", sink.events.get(1));
        assertTrue(sink.events.contains("range:1600:4:8"));
        assertEquals("done", sink.events.get(sink.events.size() - 1));
        assertEquals(3200, sink.frames);
        assertEquals(0, sink.errors);
        for (int chunk : sink.chunks) {
            assertTrue(chunk <= 640);
        }
    }

    @Test
    public void respectsSpeechRateAndSmallCallbackBuffers() {
        SilentTtsStream stream = new SilentTtsStream();
        FakeSink sink = new FakeSink(stream);
        sink.maxBuffer = 100;
        stream.begin();

        stream.complete("a".repeat(100), 100, sink);
        assertEquals(8000, sink.frames);
        for (int chunk : sink.chunks) {
            assertTrue(chunk <= 100);
        }

        FakeSink faster = new FakeSink(stream);
        stream.begin();
        stream.complete("a".repeat(100), 200, faster);
        assertEquals(4000, faster.frames);
    }

    @Test
    public void oddCallbackBufferStillEmitsWholePcmFrames() {
        SilentTtsStream stream = new SilentTtsStream();
        FakeSink sink = new FakeSink(stream);
        sink.maxBuffer = 7;
        stream.begin();

        stream.complete("text", 100, sink);

        assertEquals(3200, sink.frames);
        for (int chunk : sink.chunks) {
            assertTrue(chunk <= 6);
            assertEquals(0, chunk % 2);
        }
        assertEquals(1, sink.done);
    }

    @Test
    public void stoppedStartIsNotReportedAsAnError() {
        SilentTtsStream stream = new SilentTtsStream();
        FakeSink sink = new FakeSink(stream);
        sink.startResult = SilentTtsStream.STOPPED;
        stream.begin();

        stream.complete("text", 100, sink);

        assertEquals(0, sink.errors);
        assertEquals(0, sink.done);
        assertEquals(0, sink.frames);
    }

    @Test
    public void stopDuringAudioDoesNotCallDoneOrError() {
        SilentTtsStream stream = new SilentTtsStream();
        FakeSink sink = new FakeSink(stream);
        sink.writeResult = SilentTtsStream.ERROR;
        sink.stopOnWrite = true;
        stream.begin();

        stream.complete("a".repeat(100), 100, sink);

        assertEquals(0, sink.errors);
        assertEquals(0, sink.done);
        assertEquals(1, sink.chunks.size());
    }

    @Test
    public void failedAudioWriteReportsErrorInsteadOfDone() {
        SilentTtsStream stream = new SilentTtsStream();
        FakeSink sink = new FakeSink(stream);
        sink.writeResult = SilentTtsStream.ERROR;
        stream.begin();

        stream.complete("text", 100, sink);

        assertEquals(1, sink.errors);
        assertEquals(0, sink.done);
    }

    @Test
    public void failedStartReportsErrorWithoutWriting() {
        SilentTtsStream stream = new SilentTtsStream();
        FakeSink sink = new FakeSink(stream);
        sink.startResult = SilentTtsStream.ERROR;
        stream.begin();

        stream.complete("text", 100, sink);

        assertEquals(1, sink.errors);
        assertEquals(0, sink.done);
        assertTrue(sink.chunks.isEmpty());
    }

    @Test
    public void emptyRequestCompletesWithoutAudio() {
        SilentTtsStream stream = new SilentTtsStream();
        FakeSink sink = new FakeSink(stream);
        stream.begin();

        stream.complete("", 100, sink);

        assertEquals(1, sink.done);
        assertEquals(0, sink.errors);
        assertEquals(0, sink.frames);
        assertTrue(sink.events.stream().noneMatch(event -> event.startsWith("range:")));
    }

    private static final class FakeSink implements SilentTtsStream.Sink {
        private final SilentTtsStream stream;
        private final List<String> events = new ArrayList<>();
        private final List<Integer> chunks = new ArrayList<>();
        private int startResult = SilentTtsStream.SUCCESS;
        private int writeResult = SilentTtsStream.SUCCESS;
        private int maxBuffer = 640;
        private boolean stopOnWrite;
        private int frames;
        private int done;
        private int errors;

        private FakeSink(SilentTtsStream stream) {
            this.stream = stream;
        }

        @Override
        public int start(int sampleRate, int audioFormat, int channelCount) {
            events.add("start");
            assertEquals(16000, sampleRate);
            assertEquals(2, audioFormat);
            assertEquals(1, channelCount);
            return startResult;
        }

        @Override
        public int getMaxBufferSize() {
            return maxBuffer;
        }

        @Override
        public int audioAvailable(byte[] buffer, int offset, int length) {
            chunks.add(length);
            frames += length / 2;
            if (stopOnWrite) {
                stream.stop();
            }
            return writeResult;
        }

        @Override
        public void rangeStart(int frame, int start, int end) {
            events.add("range:" + frame + ":" + start + ":" + end);
        }

        @Override
        public int done() {
            done++;
            events.add("done");
            return SilentTtsStream.SUCCESS;
        }

        @Override
        public void error() {
            errors++;
            events.add("error");
        }
    }
}
