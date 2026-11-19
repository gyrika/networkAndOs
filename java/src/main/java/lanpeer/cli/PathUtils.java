package lanpeer.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/** File-name and size helpers for the terminal interface. */
final class PathUtils {
    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private PathUtils() {
    }

    /** Human-readable size, e.g. {@code 512 B}, {@code 1.5 KB}, {@code 2.0 MB}. */
    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }

    /** {@code path} if it is free, otherwise {@code name (1).ext}, {@code name (2).ext}, ... */
    static Path uniquePath(Path path) throws IOException {
        if (!Files.exists(path)) {
            return path;
        }
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String suffix = dot > 0 ? filename.substring(dot) : "";
        for (int number = 1; number < 10_000; number++) {
            Path candidate = path.resolveSibling(stem + " (" + number + ")" + suffix);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Could not find a free filename");
    }

    /**
     * Parses a path typed by the user: surrounding quotes are removed and a leading {@code ~}
     * expands to the home directory.
     */
    static Path parseUserPath(String text) {
        String cleaned = text.strip();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.equals("~") || cleaned.startsWith("~/") || cleaned.startsWith("~\\")) {
            cleaned = System.getProperty("user.home") + cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            throw new InvalidPathException(text, "Empty path");
        }
        return Path.of(cleaned);
    }
}
