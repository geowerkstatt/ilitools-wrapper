package ch.geowerkstatt.ilitoolswrapper.runner;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class IlitoolsRunnerMock implements IlitoolsRunner {
    @Override
    public CompletableFuture<Void> run(Tool tool, List<String> args) {
        return CompletableFuture.completedFuture(null);
    }
}
