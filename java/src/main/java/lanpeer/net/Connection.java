package lanpeer.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * A TCP connection speaking the framed protocol: each message is a 4-byte big-endian length
 * followed by that many bytes of UTF-8 JSON. Raw bytes (file contents and the trailing SHA-256 hex
 * digest) may be interleaved with frames on the same stream.
 */
public final class Connection implements AutoCloseable {
    /** Largest JSON frame either side will send or accept. */
    public static final int MAX_FRAME = 64 * 1024;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    public Connection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public static Connection open(String host, int port, Duration connectTimeout) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
            return new Connection(socket);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    public void setReadTimeout(Duration timeout) throws IOException {
        socket.setSoTimeout((int) timeout.toMillis());
    }

    /** Address of the remote end, without port. */
    public String remoteHost() {
        return socket.getInetAddress().getHostAddress();
    }

    public void send(Message message) throws IOException {
        byte[] payload = message.toJson().getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_FRAME) {
            throw new ProtocolException("Protocol message is too large");
        }
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    public Message receive() throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME) {
            throw new ProtocolException("Protocol message is too large");
        }
        return Message.parse(decodeUtf8(readExact(length)));
    }

    /** Reads exactly {@code count} bytes or fails with {@link EOFException}. */
    public byte[] readExact(int count) throws IOException {
        byte[] data = new byte[count];
        try {
            in.readFully(data);
        } catch (EOFException e) {
            throw new EOFException("Connection closed before all data arrived");
        }
        return data;
    }

    /** Reads up to {@code buffer.length} raw bytes; returns -1 at end of stream. */
    public int readSome(byte[] buffer, int length) throws IOException {
        return in.read(buffer, 0, length);
    }

    /** Writes raw bytes without flushing; call {@link #flush()} when a logical unit is complete. */
    public void writeRaw(byte[] data, int length) throws IOException {
        out.write(data, 0, length);
    }

    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    static String decodeUtf8(byte[] bytes) throws ProtocolException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("Message is not valid UTF-8", e);
        }
    }
}
