package lanpeer.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesAllOptions() {
        Options options = Options.parse("--name", "Alice", "--port=5002", "--discovery-port", "6000", "--no-discovery");
        assertEquals("Alice", options.name());
        assertEquals(5002, options.port());
        assertEquals(6000, options.discoveryPort());
        assertFalse(options.discovery());
        assertFalse(options.help());
    }

    @Test
    void usesDefaults() {
        Options options = Options.parse();
        assertEquals(5001, options.port());
        assertEquals(5000, options.discoveryPort());
        assertTrue(options.discovery());
        assertFalse(options.name().isBlank());
    }

    @Test
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse("--port", "70000"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("--port", "abc"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("--name"));
        assertThrows(IllegalArgumentException.class, () -> Options.parse("--bogus"));
    }

    @Test
    void formatsSizesLikeThePythonVersion() {
        assertEquals("0 B", PathUtils.formatSize(0));
        assertEquals("1023 B", PathUtils.formatSize(1023));
        assertEquals("1.0 KB", PathUtils.formatSize(1024));
        assertEquals("1.5 MB", PathUtils.formatSize(1024 * 1024 * 3 / 2));
        assertEquals("2048.0 TB", PathUtils.formatSize(2048L * 1024 * 1024 * 1024 * 1024));
    }

    @Test
    void uniquePathAddsCounterBeforeExtension() throws IOException {
        Path report = tempDir.resolve("report.tar.gz");
        assertEquals(report, PathUtils.uniquePath(report));
        Files.createFile(report);
        assertEquals(tempDir.resolve("report.tar (1).gz"), PathUtils.uniquePath(report));
        Files.createFile(tempDir.resolve("report.tar (1).gz"));
        assertEquals(tempDir.resolve("report.tar (2).gz"), PathUtils.uniquePath(report));

        Path dotfile = Files.createFile(tempDir.resolve(".env"));
        assertEquals(tempDir.resolve(".env (1)"), PathUtils.uniquePath(dotfile));
    }

    @Test
    void parsesUserPaths() {
        assertEquals(Path.of("my file.txt"), PathUtils.parseUserPath("\"my file.txt\""));
        assertEquals(Path.of(System.getProperty("user.home"), "docs"), PathUtils.parseUserPath("~/docs"));
    }
}
