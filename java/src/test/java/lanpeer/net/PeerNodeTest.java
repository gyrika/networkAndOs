package lanpeer.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeerNodeTest {
    private static final long WAIT_SECONDS = 10;

    private final BlockingQueue<PeerEvent> aliceEvents = new LinkedBlockingQueue<>();
    private final BlockingQueue<PeerEvent> bobEvents = new LinkedBlockingQueue<>();
    private PeerNode alice;
    private PeerNode bob;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startNodes() throws IOException {
        alice = new PeerNode("Alice", 0, 0, false, aliceEvents::add);
        bob = new PeerNode("Bob", 0, 0, false, bobEvents::add);
        alice.start();
        bob.start();
    }

    @AfterEach
    void stopNodes() {
        alice.stop();
        bob.stop();
    }

    @Test
    void connectAddsPeersOnBothSides() throws Exception {
        connect();
        assertEquals("Bob", alice.listPeers().getFirst().name());
        assertTrue(alice.listPeers().getFirst().manual());
        await(bobEvents, PeerEvent.PeersChanged.class);
        assertEquals("Alice", bob.listPeers().getFirst().name());
    }

    @Test
    void chatIsDeliveredAndAcknowledged() throws Exception {
        connect();
        alice.sendChat(bob.peerId(), "Hello, Bob! héllo 😀");

        PeerEvent.ChatReceived received = await(bobEvents, PeerEvent.ChatReceived.class);
        assertEquals("Alice", received.name());
        assertEquals(alice.peerId(), received.peerId());
        assertEquals("Hello, Bob! héllo 😀", received.text());

        PeerEvent.ChatSent sent = await(aliceEvents, PeerEvent.ChatSent.class);
        assertEquals("Bob", sent.name());
    }

    @Test
    void emptyAndOversizedChatsAreRefusedLocally() throws Exception {
        connect();
        alice.sendChat(bob.peerId(), "");
        assertTrue(await(aliceEvents, PeerEvent.Error.class).message().contains("1 to"));
        alice.sendChat(bob.peerId(), "x".repeat(PeerNode.MAX_MESSAGE + 1));
        assertTrue(await(aliceEvents, PeerEvent.Error.class).message().contains("1 to"));
    }

    @Test
    void acceptedFileArrivesIntact() throws Exception {
        byte[] content = new byte[PeerNode.CHUNK_SIZE * 5 + 123];
        new Random(42).nextBytes(content);
        Path source = Files.write(tempDir.resolve("report.bin"), content);
        Path destination = tempDir.resolve("inbox").resolve("copy.bin");
        Files.createDirectories(destination.getParent());

        connect();
        alice.sendFile(bob.peerId(), source);
        FileOffer offer = await(bobEvents, PeerEvent.FileOffered.class).offer();
        assertEquals("report.bin", offer.name());
        assertEquals(content.length, offer.size());
        assertEquals("Alice", offer.sender());
        assertTrue(offer.accept(destination));

        PeerEvent.FileReceived received = await(bobEvents, PeerEvent.FileReceived.class);
        assertEquals(destination, received.path());
        await(aliceEvents, PeerEvent.FileSent.class);
        assertArrayEquals(content, Files.readAllBytes(destination));
        try (var leftovers = Files.list(destination.getParent())) {
            assertEquals(1, leftovers.count(), "temporary .part file should be gone");
        }
    }

    @Test
    void emptyFileTransfers() throws Exception {
        Path source = Files.write(tempDir.resolve("empty.txt"), new byte[0]);
        Path destination = tempDir.resolve("empty-copy.txt");

        connect();
        alice.sendFile(bob.peerId(), source);
        await(bobEvents, PeerEvent.FileOffered.class).offer().accept(destination);
        await(bobEvents, PeerEvent.FileReceived.class);
        assertEquals(0, Files.size(destination));
    }

    @Test
    void rejectedFileIsReportedToSender() throws Exception {
        Path source = Files.writeString(tempDir.resolve("secret.txt"), "nope");

        connect();
        alice.sendFile(bob.peerId(), source);
        FileOffer offer = await(bobEvents, PeerEvent.FileOffered.class).offer();
        assertTrue(offer.reject());
        assertFalse(offer.accept(tempDir.resolve("late.txt")), "a decided offer cannot be answered again");

        PeerEvent.FileRejected rejected = await(aliceEvents, PeerEvent.FileRejected.class);
        assertEquals("secret.txt", rejected.name());
        assertEquals("Bob", rejected.peer());
    }

    @Test
    void malformedFrameIsReportedNotFatal() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", bob.port());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
            out.writeInt(Connection.MAX_FRAME + 1);
            out.flush();
            assertTrue(await(bobEvents, PeerEvent.Error.class).message().contains("too large"));
        }
        // The node still works afterwards.
        connect();
    }

    @Test
    void manualAddressIsStickyAndAddressChangesAreSilent() {
        BlockingQueue<PeerEvent> events = new LinkedBlockingQueue<>();
        PeerNode node = new PeerNode("Carol", 0, 0, false, events::add);

        node.remember("bob-id", "Bob", "192.168.1.5", 5003, false); // discovered
        assertEquals(1, events.size(), "a new peer is announced");
        events.clear();

        node.remember("bob-id", "Bob", "127.0.0.1", 5003, true); // /connect 127.0.0.1
        assertEquals("127.0.0.1", node.listPeers().getFirst().host());
        assertTrue(node.listPeers().getFirst().manual());

        node.remember("bob-id", "Bob", "192.168.1.5", 5003, false); // next broadcast
        assertEquals("127.0.0.1", node.listPeers().getFirst().host(), "discovery must not override /connect");
        assertTrue(events.isEmpty(), "address-only updates must not print 'Peer list changed'");

        node.remember("bob-id", "Bobby", "127.0.0.1", 5003, false); // renamed
        assertEquals(1, events.size(), "a rename is announced");

        node.remember("dave-id", "Dave", "192.168.1.6", 5005, false);
        node.remember("dave-id", "Dave", "192.168.1.9", 5005, false); // discovered peer moved (DHCP)
        assertEquals("192.168.1.9", node.listPeers().stream()
                .filter(peer -> peer.peerId().equals("dave-id")).findFirst().orElseThrow().host());
    }

    @Test
    void safeFilenameStripsDirectoriesAndRejectsDangerousNames() throws ProtocolException {
        assertEquals("passwd", PeerNode.safeFilename("../../etc/passwd"));
        assertEquals("y.txt", PeerNode.safeFilename("C:\\x\\y.txt"));
        assertEquals("notes v2.md", PeerNode.safeFilename("notes v2.md"));
        for (String bad : new String[] {"", "..", "dir/", "a.txt:stream", "CON", "nul.txt", "what?.txt", "trailing.", "x".repeat(256)}) {
            assertThrows(ProtocolException.class, () -> PeerNode.safeFilename(bad), bad);
        }
    }

    private void connect() throws InterruptedException {
        alice.connect("127.0.0.1", bob.port());
        await(aliceEvents, PeerEvent.PeersChanged.class);
    }

    /** Waits for the next event of {@code type}, failing on errors that were not asked for. */
    private static <T extends PeerEvent> T await(BlockingQueue<PeerEvent> queue, Class<T> type)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (true) {
            long remaining = deadline - System.nanoTime();
            PeerEvent event = queue.poll(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
            assertNotNull(event, "timed out waiting for " + type.getSimpleName());
            if (type.isInstance(event)) {
                return type.cast(event);
            }
            if (event instanceof PeerEvent.Error error) {
                throw new AssertionError("unexpected error event: " + error.message());
            }
        }
    }
}
