package ch.geowerkstatt.ilitoolswrapper.files;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class FilesystemFileManager implements FileManager {
    private final Path basePath;

    /**
     * Creates a new {@link FilesystemFileManager} that uses the directory specified by the environment variable
     * {@code PROCESSING_DIR} or its fallback as the base directory for {@link ProcessingFile} entries.
     */
    public FilesystemFileManager() {
        String basePath = System.getenv("PROCESSING_DIR");
        if (basePath == null || basePath.isEmpty()) {
            basePath = "processing";
        }
        this.basePath = Path.of(basePath);
    }

    @Override
    public ProcessingFile createProcessingFile(String folderName, String fileName, String fileExtension) {
        checkFileExtension(fileExtension);

        Path filePath = basePath.resolve(folderName, fileName + "." + fileExtension);
        return new FilesystemProcessingFile(filePath);
    }

    @Override
    public void deleteProcessingFiles(String folderName) throws IOException {
        Path path = basePath.resolve(folderName);
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) throws IOException {
                Files.delete(dir);
                return super.postVisitDirectory(dir, exc);
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return super.visitFile(file, attrs);
            }
        });
    }

    private void checkFileExtension(@Nullable String fileExtension) {
        if (fileExtension == null || fileExtension.isEmpty()) {
            throw new IllegalArgumentException("File extension cannot be null or empty.");
        }
        if (!fileExtension.matches("[a-zA-Z0-9]+")) {
            throw new IllegalArgumentException("File extension must be alphanumeric.");
        }
    }
}
