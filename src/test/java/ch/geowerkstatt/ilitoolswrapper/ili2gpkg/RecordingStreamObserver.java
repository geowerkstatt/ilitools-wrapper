package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link StreamObserver} that records everything the service emits, so tests can assert on the response stream.
 */
public final class RecordingStreamObserver<T> implements StreamObserver<T> {
    private final List<T> values = new ArrayList<>();
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(T value) {
        values.add(value);
    }

    @Override
    public void onError(Throwable error) {
        this.error = error;
    }

    @Override
    public void onCompleted() {
        completed = true;
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
}
