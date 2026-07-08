package ch.geowerkstatt.ilitoolswrapper.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * In-memory {@link ProcessingFile} that accumulates written chunks in a buffer instead of touching the filesystem.
 */
public final class InMemoryProcessingFile implements ProcessingFile {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final Path filePath;
    private boolean closed;

    public InMemoryProcessingFile(Path filePath) {
        this.filePath = filePath;
    }

    @Override
    public Path filePath() {
        return filePath;
    }

    @Override
    public InputStream inputStream() {
        if (closed) {
            throw new IllegalStateException("Cannot read from a closed file.");
        }
        return InputStream.nullInputStream();
    }

    @Override
    public OutputStream outputStream() {
        if (closed) {
            throw new IllegalStateException("Cannot write to a closed file.");
        }
        return buffer;
    }

    public boolean isClosed() {
        return closed;
    }

    public byte[] contents() {
        return buffer.toByteArray();
    }

    @Override
    public void close() throws IOException {
        buffer.close();
        closed = true;
    }
}
