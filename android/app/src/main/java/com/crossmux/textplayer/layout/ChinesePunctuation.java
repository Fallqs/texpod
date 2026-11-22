package com.crossmux.textplayer.layout;

public final class ChinesePunctuation {
    public static final int OPEN_DOUBLE_QUOTE = '“';
    private static final String OPEN = "“‘「『（《〈【〔［";
    private static final String CLOSE = "”’」』）》〉】〕］";
    private static final String MARK = "，。！？；：、";

    private ChinesePunctuation() {
    }

    static boolean startsPair(int codePoint) {
        return MARK.indexOf(codePoint) >= 0 || CLOSE.indexOf(codePoint) >= 0 || codePoint == ':';
    }

    static boolean canPair(int first, int second) {
        if (first == ':' || first == '：') {
            return OPEN.indexOf(second) >= 0;
        }
        return startsPair(first) && (OPEN.indexOf(second) >= 0 || CLOSE.indexOf(second) >= 0);
    }

    public static boolean isPair(String text) {
        return text.length() == 2 && canPair(text.charAt(0), text.charAt(1));
    }

    public static boolean hasRightAlignedQuote(String text) {
        if (text.length() == 1) return text.charAt(0) == OPEN_DOUBLE_QUOTE;
        if (!isPair(text)) return false;
        char quote = text.charAt(1);
        return quote == OPEN_DOUBLE_QUOTE || CLOSE.indexOf(quote) >= 0;
    }

    public static int rightAlignedInkLeft(int emWidth, int inkWidth) {
        return Math.max(0, emWidth - inkWidth);
    }

    public static int pairGap(int emWidth) {
        return Math.max(2, Math.round(emWidth / 8f));
    }
}
