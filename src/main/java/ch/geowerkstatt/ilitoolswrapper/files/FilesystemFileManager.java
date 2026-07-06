package ch.geowerkstatt.ilitoolswrapper.files;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public final class FilesystemFileManager implements FileManager {
    @Override
    public ProcessingFile createProcessingFile(String folderName, String fileName, String fileExtension) {
        checkFileExtension(fileExtension);

        Path filePath = Paths.get(folderName, fileName + "." + fileExtension);
        return new FilesystemProcessingFile(filePath);
    }

    @Override
    public void deleteProcessingFiles(String folderName) throws IOException {
        Path path = Paths.get(folderName);
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            @Nonnull
            public FileVisitResult postVisitDirectory(@Nonnull Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return super.postVisitDirectory(dir, exc);
            }

            @Override
            @Nonnull
            public FileVisitResult visitFile(@Nonnull Path file, @Nonnull BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return super.visitFile(file, attrs);
            }
        });
    }

    private void checkFileExtension(String fileExtension) {
        if (fileExtension == null || fileExtension.isEmpty()) {
            throw new IllegalArgumentException("File extension cannot be null or empty.");
        }
        if (!fileExtension.matches("[a-zA-Z0-9]+")) {
            throw new IllegalArgumentException("File extension must be alphanumeric.");
        }
    }
}
