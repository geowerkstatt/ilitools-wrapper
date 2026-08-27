package ch.geowerkstatt.ilitoolswrapper.runner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against the real layout: the Gradle test task downloads the distributions into the versioned
 * directories and sets {TOOL}_HOME / {TOOL}_VERSION, so no fixture is needed.
 */
public final class IlitoolsProcessRunnerTest {
    @Test
    void availableVersionsContainsTheConfiguredDefault() {
        String defaultVersion = System.getenv("ILIVALIDATOR_VERSION");
        assertNotNull(defaultVersion, "The test task must set ILIVALIDATOR_VERSION.");

        var versions = new IlitoolsProcessRunner().availableVersions(IlitoolsRunner.Tool.ILIVALIDATOR);
        assertTrue(versions.contains(defaultVersion),
                "The scan should find the downloaded default version, but offered " + versions);
    }

    @Test
    void runResolvesAnExplicitlyNamedVersion() throws Exception {
        String defaultVersion = System.getenv("ILIVALIDATOR_VERSION");
        assertNotNull(defaultVersion, "The test task must set ILIVALIDATOR_VERSION.");

        new IlitoolsProcessRunner()
                .run(IlitoolsRunner.Tool.ILIVALIDATOR, defaultVersion, List.of("--version"),
                        new IlitoolsRunner.Timeout(30, TimeUnit.SECONDS))
                .get();
    }

    @Test
    void runRejectsAVersionOutsideTheOfferedSet() {
        IlitoolsProcessRunner runner = new IlitoolsProcessRunner();

        // Also covers traversal attempts: a value that matched no scanned directory name never becomes a path.
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(IlitoolsRunner.Tool.ILIVALIDATOR, "../escape", List.of("--version"), null));
    }
}
