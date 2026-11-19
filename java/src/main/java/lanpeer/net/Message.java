package lanpeer.net;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import lanpeer.json.Json;
import lanpeer.json.JsonException;

/**
 * One protocol message: a JSON object with a {@code type} field, plus typed accessors that turn
 * missing or ill-typed fields into {@link ProtocolException}s.
 */
public final class Message {
    private final Map<String, Object> fields;

    private Message(Map<String, Object> fields) {
        this.fields = fields;
    }

    public static Message of(String type) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", type);
        return new Message(fields);
    }

    public static Message parse(String json) throws ProtocolException {
        try {
            return new Message(Json.parseObject(json));
        } catch (JsonException e) {
            throw new ProtocolException("Invalid protocol message: " + e.getMessage(), e);
        }
    }

    public Message with(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    public String toJson() {
        return Json.write(fields);
    }

    public Map<String, Object> fields() {
        return Collections.unmodifiableMap(fields);
    }

    /** The {@code type} field, or an empty string when it is absent or not a string. */
    public String type() {
        return fields.get("type") instanceof String type ? type : "";
    }

    public boolean has(String key) {
        return fields.containsKey(key);
    }

    /** Required field rendered as text (any JSON scalar is accepted, like Python's {@code str()}). */
    public String text(String key) throws ProtocolException {
        Object value = require(key);
        return value instanceof String text ? text : Json.write(value);
    }

    /** Required field that must be a JSON string. */
    public String string(String key) throws ProtocolException {
        if (require(key) instanceof String text) {
            return text;
        }
        throw new ProtocolException("Field '" + key + "' must be a string");
    }

    /** Required field that must be a JSON integer. */
    public long integer(String key) throws ProtocolException {
        if (require(key) instanceof Long number) {
            return number;
        }
        throw new ProtocolException("Field '" + key + "' must be an integer");
    }

    /** Optional boolean, {@code false} when missing; follows Python truthiness for non-booleans. */
    public boolean flag(String key) {
        return switch (fields.get(key)) {
            case null -> false;
            case Boolean bool -> bool;
            case Number number -> number.doubleValue() != 0;
            case String text -> !text.isEmpty();
            default -> true;
        };
    }

    /** Optional string, or {@code fallback} when missing. */
    public String optionalText(String key, String fallback) {
        Object value = fields.get(key);
        return value == null ? fallback : (value instanceof String text ? text : Json.write(value));
    }

    private Object require(String key) throws ProtocolException {
        Object value = fields.get(key);
        if (value == null) {
            throw new ProtocolException("Missing field '" + key + "'");
        }
        return value;
    }

    @Override
    public String toString() {
        return toJson();
    }
}
