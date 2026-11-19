package lanpeer.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import lanpeer.net.FileOffer;
import lanpeer.net.Peer;
import lanpeer.net.PeerEvent;
import lanpeer.net.PeerNode;

/**
 * Interactive terminal front end. Keyboard input and network events are funnelled into one queue
 * and handled on a single thread, so the UI state below needs no locking.
 */
public final class TerminalApp {

    /** Work items for the UI thread. */
    private sealed interface UiEvent {
        record Input(String line) implements UiEvent {
        }

        record Network(PeerEvent event) implements UiEvent {
        }
    }

    private record ProgressKey(String transferId, PeerEvent.Direction direction) {
    }

    private final BlockingQueue<UiEvent> events = new LinkedBlockingQueue<>();
    private final PeerNode node;
    private final BufferedReader input;
    private final PrintStream out;
    private final Path downloadDirectory;

    // UI state: touched only by the thread inside run().
    private final Map<Integer, FileOffer> pendingFiles = new LinkedHashMap<>();
    private final Map<ProgressKey, Integer> lastProgress = new HashMap<>();
    private String selectedId;
    private int nextOffer = 1;
    private volatile boolean running = true;

    public TerminalApp(Options options, InputStream in, PrintStream out) {
        this(options, in, out, Path.of("").toAbsolutePath().resolve("received_files"));
    }

    TerminalApp(Options options, InputStream in, PrintStream out, Path downloadDirectory) {
        this.input = new BufferedReader(new InputStreamReader(in, inputCharset()));
        this.out = out;
        this.downloadDirectory = downloadDirectory;
        this.node = new PeerNode(options.name(), options.port(), options.discoveryPort(), options.discovery(),
                event -> events.add(new UiEvent.Network(event)));
    }

    /** Runs until {@code /quit} or end of input. Throws if the TCP port cannot be bound. */
    public void run() throws IOException {
        node.start();
        Thread shutdownHook = new Thread(() -> {
            if (running) {
                out.println();
                out.println("Exiting...");
            }
            node.stop();
        }, "lanpeer-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        out.println("LAN Peer Chat | You: " + node.name() + " | TCP port: " + node.port());
        out.println("Connect both computers to the same LAN. Type /help for commands.");
        Thread reader = new Thread(this::readInput, "lanpeer-input");
        reader.setDaemon(true);
        reader.start();

        try {
            while (running) {
                switch (events.take()) {
                    case UiEvent.Input(String line) -> handleInput(line.strip());
                    case UiEvent.Network(PeerEvent event) -> handleNetworkEvent(event);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running = false;
            pendingFiles.values().forEach(FileOffer::reject);
            node.stop();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // Already shutting down.
            }
        }
    }

    private void readInput() {
        while (running) {
            out.print("> ");
            out.flush();
            String line;
            try {
                line = input.readLine();
            } catch (IOException e) {
                line = null;
            }
            if (line == null) {
                line = "/quit";
            }
            events.add(new UiEvent.Input(line));
            String command = line.strip().toLowerCase(Locale.ROOT);
            if (command.equals("/quit") || command.equals("/exit")) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ commands

    void handleInput(String line) {
        if (line.isEmpty()) {
            return;
        }
        if (!line.startsWith("/")) {
            selectedPeer().ifPresentOrElse(
                    peer -> node.sendChat(peer.peerId(), line),
                    () -> out.println("Select a peer first with /select NUMBER (or wait for one to appear)."));
            return;
        }

        int space = line.indexOf(' ');
        String command = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        String argument = space < 0 ? "" : line.substring(space + 1).strip();
        switch (command) {
            case "/help" -> printHelp();
            case "/peers" -> showPeers();
            case "/connect" -> connect(argument);
            case "/select" -> select(argument);
            case "/file" -> offerFile(argument);
            case "/accept" -> answerFile(true, argument);
            case "/reject" -> answerFile(false, argument);
            case "/quit", "/exit" -> {
                running = false;
                out.println("Goodbye.");
            }
            default -> out.println("Unknown command. Type /help.");
        }
    }

    private void printHelp() {
        out.println("/peers                 List peers (* is selected)");
        out.println("/connect IP[:port]     Connect manually (default port " + PeerNode.DEFAULT_PORT + ")");
        out.println("/select NUMBER         Select the peer to chat with");
        out.println("/file PATH             Offer a file to the selected peer");
        out.println("/accept [N] [PATH]     Accept an offered file (saves in received_files by default)");
        out.println("/reject [N]            Reject an offered file");
        out.println("/quit                  Exit");
        out.println("Type any other line to send it as a chat message.");
    }

    private void showPeers() {
        List<Peer> peers = node.listPeers();
        if (peers.isEmpty()) {
            out.println("No peers found. Try /connect IP[:port].");
            return;
        }
        for (int i = 0; i < peers.size(); i++) {
            Peer peer = peers.get(i);
            String marker = peer.peerId().equals(selectedId) ? "*" : " ";
            out.printf("%s %d. %s (%s:%d)%n", marker, i + 1, peer.name(), peer.host(), peer.port());
        }
    }

    private void connect(String argument) {
        if (argument.isEmpty()) {
            out.println("Usage: /connect IP[:port]");
            return;
        }
        int colon = argument.indexOf(':');
        String host = colon < 0 ? argument : argument.substring(0, colon);
        int port;
        try {
            port = colon < 0 ? PeerNode.DEFAULT_PORT : Integer.parseInt(argument.substring(colon + 1).strip());
        } catch (NumberFormatException e) {
            port = -1;
        }
        if (host.isBlank() || port < 1 || port > 65_535) {
            out.println("Enter an IPv4 address or hostname, optionally followed by :port.");
            return;
        }
        node.connect(host.strip(), port);
        out.println("Connecting to " + host.strip() + ":" + port + "...");
    }

    private void select(String argument) {
        List<Peer> peers = node.listPeers();
        int number;
        try {
            number = Integer.parseInt(argument);
        } catch (NumberFormatException e) {
            number = 0;
        }
        if (number < 1 || number > peers.size()) {
            out.println("Usage: /select NUMBER (see /peers)");
            return;
        }
        Peer peer = peers.get(number - 1);
        selectedId = peer.peerId();
        out.println("Chatting with " + peer.name() + ".");
    }

    private void offerFile(String argument) {
        Optional<Peer> peer = selectedPeer();
        if (peer.isEmpty()) {
            out.println("Select a peer first with /select NUMBER.");
            return;
        }
        Path source;
        try {
            source = PathUtils.parseUserPath(argument);
        } catch (InvalidPathException e) {
            source = null;
        }
        if (source == null || !Files.isRegularFile(source)) {
            out.println("Usage: /file PATH (path must name an existing file)");
            return;
        }
        node.sendFile(peer.get().peerId(), source);
        out.println("Offering " + source.getFileName() + " to " + peer.get().name() + "...");
    }

    private void answerFile(boolean accept, String argument) {
        if (pendingFiles.isEmpty()) {
            out.println("No incoming file offers.");
            return;
        }
        int space = argument.indexOf(' ');
        String first = space < 0 ? argument : argument.substring(0, space);
        int offerNumber;
        String targetText;
        if (!first.isEmpty() && first.chars().allMatch(Character::isDigit)) {
            try {
                offerNumber = Integer.parseInt(first);
            } catch (NumberFormatException e) {
                offerNumber = -1;
            }
            targetText = space < 0 ? "" : argument.substring(space + 1).strip();
        } else if (pendingFiles.size() == 1) {
            offerNumber = pendingFiles.keySet().iterator().next();
            targetText = argument;
        } else {
            out.println("Several files are waiting. Use /accept NUMBER or /reject NUMBER.");
            return;
        }

        FileOffer offer = pendingFiles.remove(offerNumber);
        if (offer == null) {
            out.println("No file offer has that number.");
            return;
        }
        if (!accept) {
            offer.reject();
            out.println("Rejected " + offer.name() + ".");
            return;
        }
        try {
            Path destination;
            if (targetText.isEmpty()) {
                destination = downloadDirectory.resolve(offer.name());
            } else {
                destination = PathUtils.parseUserPath(targetText);
                if (Files.isDirectory(destination)) {
                    destination = destination.resolve(offer.name());
                }
            }
            destination = destination.toAbsolutePath();
            Files.createDirectories(destination.getParent());
            destination = PathUtils.uniquePath(destination);
            if (offer.accept(destination)) {
                out.println("Receiving " + offer.name() + " into " + destination + "...");
            } else {
                out.println("That offer has expired.");
            }
        } catch (IOException | InvalidPathException e) {
            offer.reject();
            out.println("Could not save file: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ network events

    void handleNetworkEvent(PeerEvent event) {
        switch (event) {
            case PeerEvent.PeersChanged ignored -> onPeersChanged();
            case PeerEvent.ChatReceived(String peerId, String name, String text) -> {
                out.println();
                out.println(name + ": " + text);
            }
            case PeerEvent.ChatSent(String peerId, String name, String text) ->
                    out.println("You -> " + name + ": " + text);
            case PeerEvent.FileOffered(FileOffer offer) -> {
                int number = nextOffer++;
                pendingFiles.put(number, offer);
                out.println();
                out.printf("File offer %d: %s wants to send %s (%s).%n",
                        number, offer.sender(), offer.name(), PathUtils.formatSize(offer.size()));
                out.printf("Type /accept %d or /reject %d.%n", number, number);
            }
            case PeerEvent.FileOfferExpired(FileOffer offer) -> {
                if (pendingFiles.values().remove(offer)) {
                    out.println();
                    out.println("File offer for " + offer.name() + " from " + offer.sender() + " expired.");
                }
            }
            case PeerEvent.FileProgress progress -> onProgress(progress);
            case PeerEvent.FileSent(String name, String peer, long size) ->
                    out.println("Sent " + name + " to " + peer + ".");
            case PeerEvent.FileReceived(String name, String sender, Path path, long size) ->
                    out.println("Received " + name + " from " + sender + " -> " + path + ".");
            case PeerEvent.FileRejected(String name, String peer) ->
                    out.println(peer + " rejected " + name + ".");
            case PeerEvent.Error(String message) -> out.println("Error: " + message);
        }
    }

    private void onPeersChanged() {
        if (selectedPeer().isPresent()) {
            out.println();
            out.println("Peer list changed. Type /peers to see it.");
            return;
        }
        List<Peer> peers = node.listPeers();
        selectedId = peers.size() == 1 ? peers.getFirst().peerId() : null;
        if (selectedId != null) {
            out.println();
            out.println("Connected to " + peers.getFirst().name() + ". You can type a message now.");
        }
    }

    private void onProgress(PeerEvent.FileProgress progress) {
        int percentage = progress.total() == 0 ? 100 : (int) (100 * progress.done() / progress.total());
        ProgressKey key = new ProgressKey(progress.transferId(), progress.direction());
        int previous = lastProgress.getOrDefault(key, -10);
        if (Math.floorDiv(percentage, 10) > Math.floorDiv(previous, 10) || percentage == 100) {
            lastProgress.put(key, percentage);
            out.println(progress.direction().label() + " " + progress.name() + ": " + percentage + "%");
        }
        if (percentage == 100) {
            lastProgress.remove(key);
        }
    }

    private Optional<Peer> selectedPeer() {
        return selectedId == null ? Optional.empty() : node.findPeer(selectedId);
    }

    /** Charset the terminal uses for keyboard input. */
    private static Charset inputCharset() {
        for (String property : List.of("stdin.encoding", "stdout.encoding", "native.encoding")) {
            String name = System.getProperty(property);
            if (name != null) {
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException ignored) {
                    // try the next candidate
                }
            }
        }
        return Charset.defaultCharset();
    }
}
