package com.crossmux.textplayer.tts;

import java.util.ArrayList;
import java.util.List;

public final class UtteranceRanges {
    public static final class Range {
        private final int start;
        private final int end;
        private final int codePoints;

        private Range(int start, int end, int codePoints) {
            this.start = start;
            this.end = end;
            this.codePoints = codePoints;
        }

        public int getStart() {
            return start;
        }

        public int getEnd() {
            return end;
        }

        public int getCodePoints() {
            return codePoints;
        }
    }

    private static final String CLOSING = "”’\"')）】》〉」』〕〗";

    private UtteranceRanges() {
    }

    public static List<Range> split(CharSequence text) {
        ArrayList<Range> ranges = new ArrayList<>();
        int start = 0;
        int offset = 0;
        while (offset < text.length()) {
            int codePoint = Character.codePointAt(text, offset);
            int next = offset + Character.charCount(codePoint);
            if (!isBoundary(text, offset, codePoint)) {
                offset = next;
                continue;
            }
            offset = next;
            while (offset < text.length()) {
                int trailing = Character.codePointAt(text, offset);
                if (!Character.isWhitespace(trailing) && CLOSING.indexOf(trailing) < 0) {
                    break;
                }
                offset += Character.charCount(trailing);
            }
            ranges.add(new Range(start, offset, Character.codePointCount(text, start, offset)));
            start = offset;
        }
        if (start < text.length()) {
            ranges.add(new Range(start, text.length(), Character.codePointCount(text, start, text.length())));
        }
        return ranges;
    }

    private static boolean isBoundary(CharSequence text, int offset, int codePoint) {
        if (codePoint == '\r' || codePoint == '\n' || codePoint == '。' || codePoint == '！'
                || codePoint == '？' || codePoint == '!' || codePoint == '?' || codePoint == '…') {
            return true;
        }
        if (codePoint != '.') {
            return false;
        }
        int nextOffset = offset + 1;
        return nextOffset >= text.length() || Character.isWhitespace(text.charAt(nextOffset));
    }
}
