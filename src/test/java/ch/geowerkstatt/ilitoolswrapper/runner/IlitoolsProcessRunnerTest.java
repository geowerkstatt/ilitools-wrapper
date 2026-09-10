package ch.geowerkstatt.ilitoolswrapper.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
                        new IlitoolsRunner.Timeout(30, TimeUnit.SECONDS), false)
                .get();
    }

    @Test
    void runRejectsAVersionOutsideTheOfferedSet() {
        IlitoolsProcessRunner runner = new IlitoolsProcessRunner();

        // Also covers traversal attempts: a value that matched no scanned directory name never becomes a path.
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(IlitoolsRunner.Tool.ILIVALIDATOR, "../escape", List.of("--version"), null, false));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void cancelTerminatesIlitoolsProcess() throws Exception {
        try (NeverRespondingServer server = NeverRespondingServer.start()) {
            // Let ilivalidator request the transfer file directly from the server.
            String transferFile = server.baseUrl() + "transfer.xtf";
            CompletableFuture<Void> runFuture = new IlitoolsProcessRunner().run(
                    IlitoolsRunner.Tool.ILIVALIDATOR,
                    "",
                    List.of(transferFile),
                    null,
                    true);
            try {
                // Wait for a client connection to ensure that the tool is running.
                server.clientConnected().get(30, TimeUnit.SECONDS);
                assertFalse(runFuture.isDone(), "The tool should be running as it waits for the transfer file.");

                assertTrue(runFuture.cancel(true), "Cancelling the run future should succeed.");
                assertTrue(runFuture.isCancelled(), "The run future should report cancellation.");

                // The connection drops when the process is terminated.
                server.clientDisconnected().get(30, TimeUnit.SECONDS);
            } finally {
                // Stop the process if an assertion failed.
                runFuture.cancel(true);
            }
        }
    }
}
