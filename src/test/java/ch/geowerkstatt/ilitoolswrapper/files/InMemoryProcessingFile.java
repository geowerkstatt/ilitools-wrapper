package ch.geowerkstatt.ilitoolswrapper.files;

import java.io.ByteArrayOutputStream;
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
    public OutputStream outputStream() {
        if (closed) {
            throw new IllegalStateException("Cannot write to a closed file.");
        }
        return buffer;
    }

    @Override
    public void closeOutputStream() {
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    public byte[] contents() {
        return buffer.toByteArray();
    }
}
