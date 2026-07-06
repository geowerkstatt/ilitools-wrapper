package ch.geowerkstatt.ilitoolswrapper.runner;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class IlitoolsRunnerMock implements IlitoolsRunner {
    private List<String> lastArguments;

    @Override
    public CompletableFuture<Void> run(Tool tool, List<String> args) {
        lastArguments = List.copyOf(args);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Returns the arguments passed to the most recent {@link #run} invocation.
     *
     * @return the arguments of the last run, or {@code null} if the runner was never invoked
     */
    public List<String> lastArguments() {
        return lastArguments;
    }
}
