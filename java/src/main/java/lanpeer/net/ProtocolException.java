package lanpeer.net;

import java.io.IOException;

/** A peer sent something that does not follow the wire protocol. */
public class ProtocolException extends IOException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
