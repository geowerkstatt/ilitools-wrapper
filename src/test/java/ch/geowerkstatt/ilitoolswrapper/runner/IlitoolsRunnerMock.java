package ch.geowerkstatt.ilitoolswrapper.runner;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

public final class IlitoolsRunnerMock implements IlitoolsRunner {
    public record Arguments(Tool tool, String toolVersion, List<String> args, @Nullable Timeout timeout) { }

    private @Nullable Arguments lastArguments;
    private @Nullable Exception exception;
    private Set<String> availableVersions = Set.of();

    @Override
    @NonNull
    public CompletableFuture<Void> run(@NonNull Tool tool, @NonNull String toolVersion, @NonNull List<String> args, @Nullable Timeout timeout) {
        lastArguments = new Arguments(tool, toolVersion, List.copyOf(args), timeout);
        return exception == null ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(exception);
    }

    @Override
    @NonNull
    public Set<String> availableVersions(@NonNull Tool tool) {
        return availableVersions;
    }

    /**
     * Returns the arguments passed to the most recent {@link #run} invocation.
     *
     * @return the arguments of the last run, or {@code null} if the runner was never invoked
     */
    public @Nullable Arguments lastArguments() {
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

    /**
     * Configures the versions {@link #availableVersions} offers, for any tool.
     *
     * @param versions the versions to offer
     */
    public void offerVersions(String... versions) {
        this.availableVersions = new TreeSet<>(List.of(versions));
    }
}
