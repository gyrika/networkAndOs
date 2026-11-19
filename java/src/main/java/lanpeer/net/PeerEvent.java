package lanpeer.net;

import java.nio.file.Path;

/** Everything a {@link PeerNode} reports to its listener. Events arrive on background threads. */
public sealed interface PeerEvent {

    enum Direction {
        SENDING("Sending"), RECEIVING("Receiving");

        private final String label;

        Direction(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** The peer table gained, lost or changed an entry. */
    record PeersChanged() implements PeerEvent {
    }

    record ChatReceived(String peerId, String name, String text) implements PeerEvent {
    }

    /** A chat message was delivered and acknowledged. */
    record ChatSent(String peerId, String name, String text) implements PeerEvent {
    }

    /** A peer wants to send a file; answer it with {@link FileOffer#accept} or {@link FileOffer#reject}. */
    record FileOffered(FileOffer offer) implements PeerEvent {
    }

    record FileProgress(String transferId, String name, long done, long total, Direction direction)
            implements PeerEvent {
    }

    record FileSent(String name, String peer, long size) implements PeerEvent {
    }

    record FileReceived(String name, String sender, Path path, long size) implements PeerEvent {
    }

    record FileRejected(String name, String peer) implements PeerEvent {
    }

    /** No answer arrived in time, so the offer was declined automatically. */
    record FileOfferExpired(FileOffer offer) implements PeerEvent {
    }

    record Error(String message) implements PeerEvent {
    }
}
