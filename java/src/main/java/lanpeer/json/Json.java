package lanpeer.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader and writer.
 *
 * <p>Values map to Java types as follows: object → {@code Map<String, Object>} (insertion ordered),
 * array → {@code List<Object>}, string → {@link String}, integer → {@link Long}, other number →
 * {@link Double}, {@code true}/{@code false} → {@link Boolean}, {@code null} → {@code null}.
 *
 * <p>Output is compact UTF-8-friendly JSON: non-ASCII characters are written as-is, matching Python's
 * {@code json.dumps(..., ensure_ascii=False)} so both implementations exchange identical payloads.
 */
public final class Json {
    private static final int MAX_DEPTH = 64;

    private Json() {
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String text -> writeString(text, out);
            case Boolean bool -> out.append(bool);
            case Double number -> writeDouble(number, out);
            case Float number -> writeDouble(number.doubleValue(), out);
            case Number number -> out.append(number.longValue());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case Iterable<?> items -> {
                out.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(item, out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Cannot encode " + value.getClass().getName() + " as JSON");
        }
    }

    private static void writeDouble(double number, StringBuilder out) {
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("JSON cannot represent " + number);
        }
        out.append(number);
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ------------------------------------------------------------------ reading

    /** Parses a complete JSON document; throws {@link JsonException} on malformed input. */
    public static Object parse(String text) throws JsonException {
        Parser parser = new Parser(text);
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected trailing data");
        }
        return value;
    }

    /** Parses a JSON document that must be an object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?>)) {
            throw new JsonException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        JsonException error(String message) {
            return new JsonException(message + " at position " + pos);
        }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        char peek() throws JsonException {
            if (atEnd()) {
                throw error("Unexpected end of input");
            }
            return text.charAt(pos);
        }

        void expect(char expected) throws JsonException {
            if (peek() != expected) {
                throw error("Expected '" + expected + "'");
            }
            pos++;
        }

        Object readValue(int depth) throws JsonException {
            if (depth > MAX_DEPTH) {
                throw error("JSON nested too deeply");
            }
            char c = peek();
            return switch (c) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber();
                    }
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        Object readLiteral(String word, Object value) throws JsonException {
            if (!text.startsWith(word, pos)) {
                throw error("Invalid literal");
            }
            pos += word.length();
            return value;
        }

        Map<String, Object> readObject(int depth) throws JsonException {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("Expected object key");
                }
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, readValue(depth + 1));
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("Expected ',' or '}'");
                }
            }
        }

        List<Object> readArray(int depth) throws JsonException {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWhitespace();
                list.add(readValue(depth + 1));
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("Expected ',' or ']'");
                }
            }
        }

        String readString() throws JsonException {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("Control character in string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escape = peek();
                pos++;
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) {
                            throw error("Truncated unicode escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("Invalid unicode escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("Invalid escape '\\" + escape + "'");
                }
            }
        }

        Number readNumber() throws JsonException {
            int start = pos;
            if (text.charAt(pos) == '-') {
                pos++;
            }
            boolean integral = true;
            while (!atEnd()) {
                char c = text.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    pos++;
                } else {
                    break;
                }
            }
            String token = text.substring(start, pos);
            try {
                if (integral) {
                    return Long.parseLong(token);
                }
                double value = Double.parseDouble(token);
                if (!Double.isFinite(value)) {
                    throw error("Number out of range");
                }
                return value;
            } catch (NumberFormatException e) {
                throw error("Invalid number '" + token + "'");
            }
        }
    }
}
