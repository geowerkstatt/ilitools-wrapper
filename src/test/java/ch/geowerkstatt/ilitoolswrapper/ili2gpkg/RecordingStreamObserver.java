package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link StreamObserver} that records everything the service emits, so tests can assert on the response stream.
 */
public final class RecordingStreamObserver<T> implements StreamObserver<T> {
    private final List<T> values = new ArrayList<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(T value) {
        values.add(value);
    }

    @Override
    public void onError(Throwable error) {
        this.error = error;
        completion.completeExceptionally(error);
    }

    @Override
    public void onCompleted() {
        completed = true;
        completion.complete(null);
    }

    List<T> values() {
        return values;
    }

    Throwable error() {
        return error;
    }

    boolean isCompleted() {
        return completed;
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }
}
