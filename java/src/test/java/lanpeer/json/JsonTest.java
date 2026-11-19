package lanpeer.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTripsNestedValues() throws JsonException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "chat");
        value.put("port", 5001L);
        value.put("ok", true);
        value.put("ratio", 0.5);
        value.put("none", null);
        value.put("list", List.of(1L, "two", false));
        value.put("text", "quote \" backslash \\ newline \n tab \t unicode héllo → 😀");

        assertEquals(value, Json.parse(Json.write(value)));
    }

    @Test
    void writesNonAsciiRawLikePythonEnsureAsciiFalse() {
        assertEquals("{\"name\":\"Zoë\"}", Json.write(Map.of("name", "Zoë")));
    }

    @Test
    void escapesControlCharacters() {
        assertEquals("\"\\u0001\"", Json.write("\u0001"));
    }

    @Test
    void parsesPythonStyleWhitespaceAndEscapes() throws JsonException {
        Map<String, Object> parsed = Json.parseObject("{\"a\": 1, \"b\": \"\\u00e9\\ud83d\\ude00\", \"c\": [1.5e2]}");
        assertEquals(1L, parsed.get("a"));
        assertEquals("é😀", parsed.get("b"));
        assertEquals(List.of(150.0), parsed.get("c"));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : List.of("", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"unterminated", "tru", "01x", "{1:2}")) {
            assertThrows(JsonException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void rejectsExcessiveNesting() {
        String deep = "[".repeat(100) + "]".repeat(100);
        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    @Test
    void parseObjectRejectsNonObjects() {
        assertThrows(JsonException.class, () -> Json.parseObject("[1]"));
    }
}
