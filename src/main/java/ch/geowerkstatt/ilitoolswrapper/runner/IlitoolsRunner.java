package ch.geowerkstatt.ilitoolswrapper.runner;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Invokes an INTERLIS command-line tool as an external process. Implementations resolve the executable for
 * a given {@link Tool} and run it with the supplied arguments.
 */
public interface IlitoolsRunner {
    /**
     * The INTERLIS tools this runner can invoke.
     */
    enum Tool {
        /** The {@code ili2gpkg} tool, converting between INTERLIS transfer files and GeoPackage. */
        ILI2GPKG,
        /** The {@code ilivalidator} tool, validating INTERLIS transfer files against their models. */
        ILIVALIDATOR,
    }

    /**
     * Represents a timeout for a tool execution.
     *
     * @param duration the duration of the timeout
     * @param unit the time unit of the duration
     */
    record Timeout(long duration, TimeUnit unit) { }

    /**
     * Runs the given tool with the supplied command-line arguments and returns a future for the process termination.
     *
     * @param tool the tool to invoke
     * @param toolVersion the version to run, or an empty string for the deployment default from {@code {TOOL}_VERSION};
     *                    a non-empty value must come from {@link #availableVersions}
     * @param args the command-line arguments passed to the tool, in order
     * @param timeout an optional {@link Timeout} specifying the maximum duration to wait for the process to complete
     * @return a CompletableFuture that completes or fails when the process exits or times out
     * @throws IOException if the tool cannot be located or started
     * @throws IllegalArgumentException if a non-empty {@code toolVersion} is not in {@link #availableVersions}
     * @throws IllegalStateException if the default version cannot be resolved or its jar is missing
     */
    CompletableFuture<Void> run(Tool tool, String toolVersion, List<String> args, @Nullable Timeout timeout) throws IOException;

    /**
     * The versions of the given tool this deployment offers, which is what a request may select.
     *
     * @param tool the tool whose versions are offered
     * @return the available versions; empty when the tool is not configured, its home cannot be read, or no
     *         subdirectory holds the matching jar
     */
    Set<String> availableVersions(Tool tool);
}
