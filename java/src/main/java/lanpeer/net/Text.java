package lanpeer.net;

/** String helpers that count Unicode code points, matching Python's {@code len()} and slicing. */
public final class Text {
    private Text() {
    }

    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** The first {@code max} code points of {@code text}. */
    public static String truncate(String text, int max) {
        if (length(text) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }
}
