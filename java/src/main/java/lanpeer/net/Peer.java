package lanpeer.net;

/**
 * A known peer. Immutable: updates replace the record in {@link PeerNode}'s table.
 *
 * @param lastSeen {@link System#nanoTime()} of the last announcement or contact
 * @param manual   added with {@code /connect}; manual peers never expire
 */
public record Peer(String peerId, String name, String host, int port, long lastSeen, boolean manual) {
}
