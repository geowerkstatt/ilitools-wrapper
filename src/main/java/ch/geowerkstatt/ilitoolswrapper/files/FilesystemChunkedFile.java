package ch.geowerkstatt.ilitoolswrapper.files;

import com.google.protobuf.ByteString;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FilesystemChunkedFile implements ChunkedFile {
    private final Path filePath;
    private final OutputStream file;

    /**
     * Creates a new {@link FilesystemChunkedFile} that writes to the specified file path.
     *
     * @param filePath the path of the file to write to
     * @throws Exception if the file cannot be created or opened
     */
    public FilesystemChunkedFile(Path filePath) throws Exception {
        this.filePath = filePath;
        this.file = Files.newOutputStream(filePath, StandardOpenOption.CREATE_NEW);
    }

    @Override
    public void writeChunk(ByteString chunk) throws Exception {
        chunk.writeTo(file);
    }

    @Override
    public void delete() throws Exception {
        close();
        Files.delete(filePath);
    }

    @Override
    public void close() throws Exception {
        file.close();
    }
}
