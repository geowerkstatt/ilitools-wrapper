package ch.geowerkstatt.ilitoolswrapper.runner;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    }

    /**
     * Runs the given tool with the supplied command-line arguments and returns a future for the process termination.
     *
     * @param tool the tool to invoke
     * @param args the command-line arguments passed to the tool, in order
     * @return a CompletableFuture that completes or fails when the process exits
     * @throws IOException if the tool cannot be located or started
     */
    CompletableFuture<Void> run(Tool tool, List<String> args) throws IOException;
}
