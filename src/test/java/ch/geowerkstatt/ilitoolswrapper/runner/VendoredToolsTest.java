package ch.geowerkstatt.ilitoolswrapper.runner;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the tool versions vendored under {@code vendor/}, which the Dockerfile copies next to the downloaded ones.
 */
public final class VendoredToolsTest {
    private static final Path VENDORED_ILIVALIDATOR = Path.of("vendor", "ilivalidator");

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("vendoredIlivalidatorVersions")
    void vendoredIlivalidatorHoldsTheJarTheRunnerResolves(String version) {
        // Without ilivalidator-<version>.jar the runner does not offer the version, and nothing reports it missing.
        assertTrue(Files.isRegularFile(VENDORED_ILIVALIDATOR.resolve(version).resolve("ilivalidator-" + version + ".jar")), version + " lacks ilivalidator-" + version + ".jar");
    }

    @ParameterizedTest(name = "{0}", allowZeroInvocations = true)
    @MethodSource("vendoredIlivalidatorVersions")
    void vendoredIlivalidatorCarriesNoPluginsFolder(String version) {
        // The tool loads every jar in <jarDir>/plugins on every run, whatever plugins a request selected.
        assertFalse(Files.exists(VENDORED_ILIVALIDATOR.resolve(version).resolve("plugins")), version + " carries a plugins folder");
    }

    // Empty once no preview is vendored, which is why both tests allow zero invocations.
    static List<String> vendoredIlivalidatorVersions() throws IOException {
        if (!Files.isDirectory(VENDORED_ILIVALIDATOR)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(VENDORED_ILIVALIDATOR)) {
            return entries.filter(Files::isDirectory).map(entry -> entry.getFileName().toString()).toList();
        }
    }
}
