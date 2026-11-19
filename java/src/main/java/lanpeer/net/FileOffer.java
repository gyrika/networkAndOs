package lanpeer.net;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An incoming file offer waiting for the user's decision. Exactly one of {@link #accept},
 * {@link #reject} or the timeout wins; later answers return {@code false}.
 */
public final class FileOffer {
    private final String name;
    private final long size;
    private final String sender;
    private final CompletableFuture<Optional<Path>> decision = new CompletableFuture<>();

    FileOffer(String name, long size, String sender) {
        this.name = name;
        this.size = size;
        this.sender = sender;
    }

    public String name() {
        return name;
    }

    public long size() {
        return size;
    }

    public String sender() {
        return sender;
    }

    /** Accepts the offer, saving to {@code destination}. Returns false if already answered or expired. */
    public boolean accept(Path destination) {
        return decision.complete(Optional.of(destination));
    }

    /** Declines the offer. Returns false if already answered or expired. */
    public boolean reject() {
        return decision.complete(Optional.empty());
    }

    /**
     * Blocks for the decision. An unanswered offer is rejected after {@code timeout}, and
     * {@code onExpired} runs if that automatic rejection is what decided it.
     */
    Optional<Path> awaitDecision(Duration timeout, Runnable onExpired) throws InterruptedException {
        try {
            return decision.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (reject()) {
                onExpired.run();
            }
            return decision.getNow(Optional.empty());
        } catch (ExecutionException e) {
            return Optional.empty();
        }
    }
}
