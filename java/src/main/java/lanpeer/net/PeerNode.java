package lanpeer.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * One participant in the LAN chat: listens for TCP connections, announces itself with UDP
 * broadcasts, and sends chat messages and files to other peers.
 *
 * <p>Wire protocol (compatible with the Python implementation):
 * <ul>
 *   <li>Discovery: a UDP broadcast of {@code {"type":"announce", peer_id, name, port}} every few seconds.</li>
 *   <li>TCP: one connection per operation; frames are a 4-byte big-endian length plus UTF-8 JSON.
 *       {@code hello} → {@code hello}; {@code chat} → {@code ack}; {@code file_offer} →
 *       {@code file_reply}, then raw file bytes and a 64-character SHA-256 hex digest → {@code file_done}.</li>
 * </ul>
 *
 * <p>All public operations are non-blocking; results are reported through the {@link PeerEvent} listener.
 */
public final class PeerNode implements AutoCloseable {
    public static final int DEFAULT_PORT = 5001;
    public static final int DEFAULT_DISCOVERY_PORT = 5000;
    public static final int MAX_MESSAGE = 10_000;
    public static final int MAX_NAME = 40;

    static final int CHUNK_SIZE = 64 * 1024;
    static final Duration DISCOVERY_INTERVAL = Duration.ofSeconds(3);
    static final Duration PEER_TIMEOUT = Duration.ofSeconds(15);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration IO_TIMEOUT = Duration.ofSeconds(30);
    /** How long the receiver waits for the user to accept or reject a file. */
    static final Duration OFFER_TIMEOUT = Duration.ofSeconds(120);
    /** How long the sender waits for that answer; a little longer than {@link #OFFER_TIMEOUT}. */
    static final Duration OFFER_REPLY_TIMEOUT = Duration.ofSeconds(130);

    private static final long PROGRESS_INTERVAL_NANOS = Duration.ofMillis(100).toNanos();
    private static final int DIGEST_LENGTH = 64;
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[\\p{Cntrl}<>:\"|?*]");
    private static final Set<String> RESERVED_WINDOWS_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final String name;
    private final String peerId = UUID.randomUUID().toString().replace("-", "");
    private final int discoveryPort;
    private final boolean discoveryEnabled;
    private final Consumer<PeerEvent> listener;

    private final Object lock = new Object();
    private final Map<String, Peer> peers = new HashMap<>(); // guarded by lock

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private volatile int port;
    private volatile ServerSocket server;
    private volatile DatagramSocket discoverySocket;

    public PeerNode(String name, int port, int discoveryPort, boolean discoveryEnabled, Consumer<PeerEvent> listener) {
        String trimmed = Text.truncate(Objects.requireNonNullElse(name, "").strip(), MAX_NAME);
        this.name = trimmed.isEmpty() ? defaultName() : trimmed;
        this.port = port;
        this.discoveryPort = discoveryPort;
        this.discoveryEnabled = discoveryEnabled;
        this.listener = Objects.requireNonNull(listener);
    }

    /** A sensible display name: the {@code USER} variable, else this computer's host name. */
    public static String defaultName() {
        for (String variable : List.of("USER", "USERNAME", "COMPUTERNAME", "HOSTNAME")) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) {
                return Text.truncate(value.strip(), MAX_NAME);
            }
        }
        try {
            return Text.truncate(InetAddress.getLocalHost().getHostName(), MAX_NAME);
        } catch (IOException e) {
            return "peer";
        }
    }

    public String name() {
        return name;
    }

    public String peerId() {
        return peerId;
    }

    /** The TCP port actually bound (useful when constructed with port 0). */
    public int port() {
        return port;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Binds the TCP listener and starts background threads. Throws if the port is unavailable. */
    public void start() throws IOException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Node already started");
        }
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 20);
        } catch (IOException e) {
            socket.close();
            throw e;
        }
        server = socket;
        port = socket.getLocalPort();
        Thread.ofVirtual().name("lanpeer-accept").start(this::acceptLoop);
        if (discoveryEnabled) {
            Thread.ofVirtual().name("lanpeer-discovery").start(this::discoveryLoop);
        }
    }

    /** Stops listening and aborts transfers in progress. Safe to call more than once. */
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(server);
        closeQuietly(discoverySocket);
        workers.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    // ------------------------------------------------------------------ peer table

    public List<Peer> listPeers() {
        List<Peer> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(peers.values());
        }
        snapshot.sort(Comparator.comparing((Peer peer) -> peer.name().toLowerCase(Locale.ROOT))
                .thenComparing(Peer::host));
        return snapshot;
    }

    public Optional<Peer> findPeer(String id) {
        synchronized (lock) {
            return Optional.ofNullable(peers.get(id));
        }
    }

    /**
     * Records contact with a peer. A peer can be reachable at several addresses (for example
     * 127.0.0.1 via {@code /connect} and its LAN address via discovery), so the address is sticky:
     * a manual contact sets it, and discovery only moves it for peers that were never added
     * manually. Address-only updates are silent so the peer list does not flap between addresses.
     */
    void remember(String id, String peerName, String host, long peerPort, boolean manual) {
        if (id.isEmpty() || id.equals(peerId) || peerPort < 1 || peerPort > 65_535) {
            return;
        }
        String shortName = Text.truncate(peerName, MAX_NAME);
        long now = System.nanoTime();
        boolean changed;
        synchronized (lock) {
            Peer old = peers.get(id);
            changed = old == null || !old.name().equals(shortName) || old.port() != peerPort;
            String address = old == null || manual || !old.manual() ? host : old.host();
            boolean keepManual = manual || (old != null && old.manual());
            peers.put(id, new Peer(id, shortName, address, (int) peerPort, now, keepManual));
        }
        if (changed) {
            emit(new PeerEvent.PeersChanged());
        }
    }

    private void rememberIdentity(Message message, String host, boolean manual) throws ProtocolException {
        try {
            remember(message.text("peer_id"), message.text("name"), host, message.integer("port"), manual);
        } catch (ProtocolException e) {
            throw new ProtocolException("Invalid peer identity", e);
        }
    }

    private void expirePeers(long now) {
        long timeout = PEER_TIMEOUT.toNanos();
        boolean removed;
        synchronized (lock) {
            removed = peers.values().removeIf(peer -> !peer.manual() && now - peer.lastSeen() > timeout);
        }
        if (removed) {
            emit(new PeerEvent.PeersChanged());
        }
    }

    private Peer target(String id) throws ProtocolException {
        return findPeer(id).orElseThrow(() -> new ProtocolException("Select an available peer first"));
    }

    // ------------------------------------------------------------------ public operations

    /** Contacts {@code host:port} directly and adds it as a permanent peer. */
    public void connect(String host, int peerPort) {
        runAsync(() -> {
            try (Connection conn = Connection.open(host, peerPort, CONNECT_TIMEOUT)) {
                conn.setReadTimeout(CONNECT_TIMEOUT);
                conn.send(identity("hello"));
                Message reply = conn.receive();
                if (!"hello".equals(reply.type())) {
                    throw new ProtocolException("That address did not answer as a peer");
                }
                remember(reply.text("peer_id"), reply.text("name"), conn.remoteHost(), reply.integer("port"), true);
            } catch (IOException e) {
                emitError("Cannot add " + host + ":" + peerPort + ": " + describe(e));
            }
        });
    }

    public void sendChat(String targetId, String text) {
        int length = Text.length(text);
        if (length < 1 || length > MAX_MESSAGE) {
            emitError("Messages must be 1 to " + MAX_MESSAGE + " characters");
            return;
        }
        runAsync(() -> {
            try {
                Peer peer = target(targetId);
                try (Connection conn = Connection.open(peer.host(), peer.port(), CONNECT_TIMEOUT)) {
                    conn.setReadTimeout(IO_TIMEOUT);
                    conn.send(identity("chat").with("text", text));
                    if (!"ack".equals(conn.receive().type())) {
                        throw new ProtocolException("Peer did not acknowledge message");
                    }
                }
                emit(new PeerEvent.ChatSent(targetId, peer.name(), text));
            } catch (IOException e) {
                emitError("Message failed: " + describe(e));
            }
        });
    }

    public void sendFile(String targetId, Path source) {
        runAsync(() -> {
            try {
                doSendFile(target(targetId), source);
            } catch (IOException e) {
                emitError("File transfer failed: " + describe(e));
            }
        });
    }

    // ------------------------------------------------------------------ sending files

    private void doSendFile(Peer peer, Path source) throws IOException {
        long size = Files.size(source);
        String filename = source.getFileName().toString();
        String transferId = UUID.randomUUID().toString().replace("-", "");
        try (Connection conn = Connection.open(peer.host(), peer.port(), CONNECT_TIMEOUT)) {
            conn.setReadTimeout(OFFER_REPLY_TIMEOUT); // the receiver may take time to decide
            conn.send(identity("file_offer")
                    .with("filename", filename)
                    .with("size", size)
                    .with("transfer_id", transferId));
            Message reply = conn.receive();
            if (!"file_reply".equals(reply.type())) {
                throw new ProtocolException("Unexpected response to file offer");
            }
            if (!reply.flag("accepted")) {
                emit(new PeerEvent.FileRejected(filename, peer.name()));
                return;
            }

            conn.setReadTimeout(IO_TIMEOUT);
            MessageDigest digest = sha256();
            ProgressReporter progress = new ProgressReporter(transferId, filename, size, PeerEvent.Direction.SENDING);
            byte[] buffer = new byte[CHUNK_SIZE];
            long sent = 0;
            try (InputStream file = Files.newInputStream(source)) {
                while (sent < size) {
                    int count = file.read(buffer, 0, (int) Math.min(CHUNK_SIZE, size - sent));
                    if (count < 0) {
                        throw new EOFException("Source file changed during transfer");
                    }
                    conn.writeRaw(buffer, count);
                    digest.update(buffer, 0, count);
                    sent += count;
                    progress.update(sent);
                }
            }
            if (size == 0) {
                progress.update(0);
            }
            byte[] hex = HexFormat.of().formatHex(digest.digest()).getBytes(StandardCharsets.US_ASCII);
            conn.writeRaw(hex, hex.length);
            conn.flush();

            Message result = conn.receive();
            if (!"file_done".equals(result.type()) || !result.flag("ok")) {
                throw new ProtocolException(result.optionalText("reason", "Peer could not save the file"));
            }
            emit(new PeerEvent.FileSent(filename, peer.name(), size));
        }
    }

    // ------------------------------------------------------------------ incoming connections

    private void acceptLoop() {
        while (!stopping.get()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                break; // closed by stop()
            }
            try {
                workers.execute(() -> handleConnection(socket));
            } catch (RejectedExecutionException e) {
                closeQuietly(socket);
            }
        }
    }

    private void handleConnection(Socket socket) {
        String host = socket.getInetAddress().getHostAddress();
        try (Connection conn = new Connection(socket)) {
            conn.setReadTimeout(IO_TIMEOUT);
            Message header = conn.receive();
            rememberIdentity(header, host, "hello".equals(header.type()));
            switch (header.type()) {
                case "hello" -> conn.send(identity("hello"));
                case "chat" -> {
                    String text = header.has("text") ? header.string("text") : "";
                    int length = Text.length(text);
                    if (length < 1 || length > MAX_MESSAGE) {
                        throw new ProtocolException("Invalid chat message");
                    }
                    emit(new PeerEvent.ChatReceived(header.text("peer_id"), header.text("name"), text));
                    conn.send(Message.of("ack"));
                }
                case "file_offer" -> receiveFile(conn, header);
                default -> throw new ProtocolException("Unknown protocol message");
            }
        } catch (IOException e) {
            if (!stopping.get()) {
                emitError("Incoming connection from " + host + ": " + describe(e));
            }
        }
    }

    private void receiveFile(Connection conn, Message offer) throws IOException {
        long size;
        String filename;
        try {
            size = offer.integer("size");
            if (size < 0) {
                throw new ProtocolException("Negative file size");
            }
            filename = safeFilename(offer.string("filename"));
        } catch (ProtocolException e) {
            conn.send(Message.of("file_reply").with("accepted", false));
            throw new ProtocolException("Invalid file offer", e);
        }

        FileOffer fileOffer = new FileOffer(filename, size, offer.text("name"));
        emit(new PeerEvent.FileOffered(fileOffer));
        Optional<Path> choice;
        try {
            choice = fileOffer.awaitDecision(OFFER_TIMEOUT, () -> emit(new PeerEvent.FileOfferExpired(fileOffer)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            choice = Optional.empty();
        }
        if (choice.isEmpty()) {
            conn.send(Message.of("file_reply").with("accepted", false));
            return;
        }

        Path destination = choice.get();
        Path temporary = destination.resolveSibling(
                destination.getFileName() + ".part-" + UUID.randomUUID().toString().substring(0, 8));
        OutputStream file;
        try {
            file = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            conn.send(Message.of("file_reply").with("accepted", false));
            throw new IOException("Could not save " + filename + ": " + describe(e), e);
        }

        try {
            MessageDigest digest = sha256();
            ProgressReporter progress = new ProgressReporter(
                    offer.optionalText("transfer_id", ""), filename, size, PeerEvent.Direction.RECEIVING);
            try (file) {
                conn.send(Message.of("file_reply").with("accepted", true));
                byte[] buffer = new byte[CHUNK_SIZE];
                long received = 0;
                while (received < size) {
                    int count = conn.readSome(buffer, (int) Math.min(CHUNK_SIZE, size - received));
                    if (count < 0) {
                        throw new EOFException("Connection closed during file transfer");
                    }
                    file.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    received += count;
                    progress.update(received);
                }
                if (size == 0) {
                    progress.update(0);
                }
            }
            String expected = new String(conn.readExact(DIGEST_LENGTH), StandardCharsets.US_ASCII);
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(expected)) {
                throw new ProtocolException("SHA-256 checksum mismatch");
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            conn.send(Message.of("file_done").with("ok", true));
            emit(new PeerEvent.FileReceived(filename, fileOffer.sender(), destination, size));
        } catch (IOException e) {
            Files.deleteIfExists(temporary);
            try {
                conn.send(Message.of("file_done").with("ok", false).with("reason", describe(e)));
            } catch (IOException ignored) {
                // The connection is already broken; the original error is what matters.
            }
            throw e;
        }
    }

    /**
     * Reduces a sender-supplied name to a bare filename that is safe on every platform: no
     * directories, no characters Windows forbids (including ':' which would create an alternate
     * data stream), and no reserved device names.
     */
    static String safeFilename(String rawName) throws ProtocolException {
        String normalized = rawName.replace('\\', '/');
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (filename.isEmpty() || filename.equals(".") || filename.equals("..") || Text.length(filename) > 255) {
            throw new ProtocolException("Invalid filename");
        }
        if (UNSAFE_FILENAME_CHARS.matcher(filename).find() || filename.endsWith(".") || filename.endsWith(" ")) {
            throw new ProtocolException("Filename contains characters that are not allowed");
        }
        String stem = filename.contains(".") ? filename.substring(0, filename.indexOf('.')) : filename;
        if (RESERVED_WINDOWS_NAMES.contains(stem.strip().toUpperCase(Locale.ROOT))) {
            throw new ProtocolException("Filename is reserved by Windows");
        }
        return filename;
    }

    // ------------------------------------------------------------------ discovery

    private void discoveryLoop() {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), discoveryPort));
            socket.setSoTimeout(500);
            discoverySocket = socket;
            if (stopping.get()) {
                return; // stop() ran before the socket was published
            }

            InetSocketAddress broadcast = new InetSocketAddress(InetAddress.getByName("255.255.255.255"), discoveryPort);
            byte[] buffer = new byte[4096];
            long nextAnnounce = System.nanoTime();
            while (!stopping.get()) {
                long now = System.nanoTime();
                if (now - nextAnnounce >= 0) {
                    byte[] payload = identity("announce").toJson().getBytes(StandardCharsets.UTF_8);
                    try {
                        socket.send(new DatagramPacket(payload, payload.length, broadcast));
                    } catch (IOException e) {
                        if (stopping.get()) {
                            break;
                        }
                        emitError("Peer discovery: " + describe(e));
                    }
                    nextAnnounce = now + DISCOVERY_INTERVAL.toNanos();
                    expirePeers(now);
                }

                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try {
                    String json = Connection.decodeUtf8(
                            java.util.Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()));
                    Message announcement = Message.parse(json);
                    if ("announce".equals(announcement.type())) {
                        rememberIdentity(announcement, packet.getAddress().getHostAddress(), false);
                    }
                } catch (ProtocolException ignored) {
                    // Not one of ours; other software may use the same port.
                }
            }
        } catch (IOException e) {
            if (!stopping.get()) {
                emitError("Peer discovery unavailable: " + describe(e) + ". Use /connect IP.");
            }
        } finally {
            discoverySocket = null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Message identity(String type) {
        return Message.of(type).with("peer_id", peerId).with("name", name).with("port", port);
    }

    private void runAsync(Runnable task) {
        try {
            workers.execute(task);
        } catch (RejectedExecutionException e) {
            // Node is shutting down; drop the request.
        }
    }

    private void emit(PeerEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException e) {
            // A faulty listener must not kill network threads.
            System.err.println("Event listener failed: " + e);
        }
    }

    private void emitError(String message) {
        emit(new PeerEvent.Error(message));
    }

    static String describe(Throwable error) {
        if (error instanceof EOFException && error.getMessage() == null) {
            return "Connection closed before all data arrived";
        }
        String message = error.getMessage();
        if (error instanceof ProtocolException && error.getCause() instanceof ProtocolException cause) {
            return message + " (" + cause.getMessage() + ")";
        }
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Best effort during shutdown.
        }
    }

    /** Emits progress at most every 100 ms, plus once at completion. */
    private final class ProgressReporter {
        private final String transferId;
        private final String filename;
        private final long total;
        private final PeerEvent.Direction direction;
        private long lastUpdate = System.nanoTime() - PROGRESS_INTERVAL_NANOS;

        ProgressReporter(String transferId, String filename, long total, PeerEvent.Direction direction) {
            this.transferId = transferId;
            this.filename = filename;
            this.total = total;
            this.direction = direction;
        }

        void update(long done) {
            long now = System.nanoTime();
            if (now - lastUpdate > PROGRESS_INTERVAL_NANOS || done == total) {
                emit(new PeerEvent.FileProgress(transferId, filename, done, total, direction));
                lastUpdate = now;
            }
        }
    }
}
