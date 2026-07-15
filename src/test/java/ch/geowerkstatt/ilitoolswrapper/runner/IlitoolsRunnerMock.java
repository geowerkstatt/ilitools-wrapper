package ch.geowerkstatt.ilitoolswrapper.runner;

import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class IlitoolsRunnerMock implements IlitoolsRunner {
    private List<String> lastArguments;
    private Exception exception;

    @Override
    public CompletableFuture<Void> run(Tool tool, List<String> args, @Nullable ProcessingFile logFile, @Nullable Timeout timeout) {
        lastArguments = List.copyOf(args);
        return exception == null ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(exception);
    }

    /**
     * Returns the arguments passed to the most recent {@link #run} invocation.
     *
     * @return the arguments of the last run, or {@code null} if the runner was never invoked
     */
    public List<String> lastArguments() {
        return lastArguments;
    }

    /**
     * Configures the mock to fail the next {@link #run} invocation with the given exception.
     *
     * @param exception the exception to return on the next run
     */
    public void failRunWith(Exception exception) {
        this.exception = exception;
    }
}
