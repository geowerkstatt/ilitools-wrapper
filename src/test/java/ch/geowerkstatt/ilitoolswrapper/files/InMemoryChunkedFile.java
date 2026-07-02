package ch.geowerkstatt.ilitoolswrapper.files;

import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;

/**
 * In-memory {@link ChunkedFile} that accumulates written chunks in a buffer instead of touching the filesystem.
 */
public final class InMemoryChunkedFile implements ChunkedFile {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean closed;
    private boolean deleted;

    @Override
    public void writeChunk(ByteString chunk) throws Exception {
        if (closed) {
            throw new IllegalStateException("Cannot write to a closed file.");
        }
        chunk.writeTo(buffer);
    }

    @Override
    public void delete() {
        deleted = true;
    }

    @Override
    public void close() {
        closed = true;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public boolean isClosed() {
        return closed;
    }

    public byte[] contents() {
        return buffer.toByteArray();
    }
}
