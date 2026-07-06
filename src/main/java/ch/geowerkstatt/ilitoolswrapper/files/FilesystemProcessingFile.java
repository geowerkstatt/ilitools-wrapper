package ch.geowerkstatt.ilitoolswrapper.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FilesystemProcessingFile implements ProcessingFile {
    private final Path filePath;
    private InputStream inputStream;
    private OutputStream outputStream;

    /**
     * Creates a new {@link FilesystemProcessingFile} for the specified file path. The underlying file is not
     * created until {@link #outputStream()} is called.
     *
     * @param filePath the path of the file to write to
     */
    public FilesystemProcessingFile(Path filePath) {
        this.filePath = filePath;
    }

    @Override
    public Path filePath() {
        return filePath;
    }

    @Override
    public InputStream inputStream() throws IOException {
        if (inputStream == null) {
            inputStream = Files.newInputStream(filePath, StandardOpenOption.READ);
        }
        return inputStream;
    }

    @Override
    public OutputStream outputStream() throws IOException {
        if (outputStream == null) {
            Files.createDirectories(filePath.getParent());
            outputStream = Files.newOutputStream(filePath, StandardOpenOption.CREATE_NEW);
        }
        return outputStream;
    }
}
