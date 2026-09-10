package ch.geowerkstatt.ilitoolswrapper;

import io.grpc.stub.ServerCallStreamObserver;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link ServerCallStreamObserver} that records everything the service emits, so tests can assert on the response stream.
 */
public final class RecordingStreamObserver<T> extends ServerCallStreamObserver<T> {
    private final List<T> values = new ArrayList<>();
    private final CompletableFuture<Void> completion = new CompletableFuture<>();
    private @Nullable Throwable error;
    private boolean completed;
    private boolean cancelled;
    private @Nullable Runnable onCancelHandler;

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

    public List<T> values() {
        return values;
    }

    public @Nullable Throwable error() {
        return error;
    }

    public boolean isCompleted() {
        return completed;
    }

    public CompletableFuture<Void> completion() {
        return completion;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setOnCancelHandler(@Nullable Runnable onCancelHandler) {
        this.onCancelHandler = onCancelHandler;
    }

    @Override
    public void setCompression(String compression) {
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setOnReadyHandler(Runnable onReadyHandler) {
    }

    @Override
    public void disableAutoInboundFlowControl() {
    }

    @Override
    public void request(int count) {
    }

    @Override
    public void setMessageCompression(boolean enable) {
    }

    public boolean hasCancelHandler() {
        return onCancelHandler != null;
    }

    public void cancel() {
        cancelled = true;
        if (onCancelHandler != null) {
            onCancelHandler.run();
        }
    }
}
