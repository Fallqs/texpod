package com.crossmux.textplayer.layout;

import java.util.regex.Pattern;

public final class ParagraphRules {
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "^\\s*(?:第[0-9零一二三四五六七八九十百千万两〇]+章|Chapter\\s+[0-9]+)(?:\\s+[^\\r\\n]{0,80})?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final String CLOSING = "”’\"')）】》〉」』〕〗";

    private ParagraphRules() {
    }

    public static boolean paragraphBefore(CharSequence previous, CharSequence current) {
        if (previous == null || previous.length() == 0 || current == null || current.length() == 0
                || startsLineBreak(current) || endsLineBreak(previous)) {
            return false;
        }
        return isChapterHeading(previous) || isChapterHeading(current) || endsParagraph(previous);
    }

    public static boolean isChapterHeading(CharSequence text) {
        return text != null && CHAPTER_HEADING.matcher(text).matches();
    }

    private static boolean startsLineBreak(CharSequence text) {
        char first = text.charAt(0);
        return first == '\r' || first == '\n';
    }

    private static boolean endsLineBreak(CharSequence text) {
        char last = text.charAt(text.length() - 1);
        return last == '\r' || last == '\n';
    }

    private static boolean endsParagraph(CharSequence text) {
        for (int index = text.length() - 1; index >= 0; index--) {
            char character = text.charAt(index);
            if (Character.isWhitespace(character) || CLOSING.indexOf(character) >= 0) {
                continue;
            }
            return character == '。' || character == '！' || character == '？' || character == '!'
                    || character == '?' || character == '.' || character == '…';
        }
        return false;
    }
}
