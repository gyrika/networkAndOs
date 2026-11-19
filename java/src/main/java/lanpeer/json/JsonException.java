package lanpeer.json;

/** Thrown when text is not valid JSON. */
public class JsonException extends Exception {
    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }
}
