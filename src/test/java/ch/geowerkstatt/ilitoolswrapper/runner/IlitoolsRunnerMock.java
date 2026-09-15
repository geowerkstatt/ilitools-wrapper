package ch.geowerkstatt.ilitoolswrapper.runner;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

public final class IlitoolsRunnerMock implements IlitoolsRunner {
    public record Arguments(Tool tool, String toolVersion, List<String> args, @Nullable Timeout timeout, boolean useSessionCache) { }

    private @Nullable Arguments lastArguments;
    private final List<Arguments> allArguments = new ArrayList<>();
    private @Nullable Exception exception;
    private Set<String> availableVersions = Set.of();
    private @Nullable Tool versionsQueriedFor;
    private boolean holdNextRun;
    private @Nullable CompletableFuture<Void> pendingRun;

    @Override
    @NonNull
    public CompletableFuture<Void> run(@NonNull Tool tool, @NonNull String toolVersion, @NonNull List<String> args, @Nullable Timeout timeout, boolean useSessionCache) {
        Arguments arguments = new Arguments(tool, toolVersion, List.copyOf(args), timeout, useSessionCache);
        lastArguments = arguments;
        allArguments.add(arguments);
        if (holdNextRun) {
            holdNextRun = false;
            CompletableFuture<Void> pending = new CompletableFuture<>();
            pendingRun = pending;
            return pending;
        }
        return exception == null ? CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(exception);
    }

    @Override
    @NonNull
    public Set<String> availableVersions(@NonNull Tool tool) {
        versionsQueriedFor = tool;
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
     * Returns the arguments of every {@link #run} invocation, in call order.
     *
     * @return the recorded invocations, empty if the runner was never invoked
     */
    public List<Arguments> allArguments() {
        return List.copyOf(allArguments);
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
     * Makes the next {@link #run} invocation return a future that never completes on its own, so a test can
     * observe cancellation. The returned future is available through {@link #pendingRun()}.
     */
    public void holdNextRun() {
        this.holdNextRun = true;
    }

    /**
     * Returns the never-completing future handed out for a {@link #holdNextRun()} invocation.
     *
     * @return the pending run future, or {@code null} if no run was held
     */
    public @Nullable CompletableFuture<Void> pendingRun() {
        return pendingRun;
    }

    /**
     * Configures the versions {@link #availableVersions} offers, for any tool.
     *
     * @param versions the versions to offer
     */
    public void offerVersions(String... versions) {
        this.availableVersions = new TreeSet<>(List.of(versions));
    }

    /**
     * Returns the tool of the most recent {@link #availableVersions} query.
     *
     * @return the queried tool, or {@code null} if the versions were never queried
     */
    public @Nullable Tool versionsQueriedFor() {
        return versionsQueriedFor;
    }
}
