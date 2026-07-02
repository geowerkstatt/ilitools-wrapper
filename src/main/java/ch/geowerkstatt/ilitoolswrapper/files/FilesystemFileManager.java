package ch.geowerkstatt.ilitoolswrapper.files;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class FilesystemFileManager implements FileManager {
    @Override
    public ChunkedFile createChunkedFile(String folderName, String fileName, String fileExtension) throws Exception {
        checkFileExtension(fileExtension);

        Path directoryPath = Paths.get(folderName);
        Files.createDirectories(directoryPath);
        Path filePath = directoryPath.resolve(fileName + "." + fileExtension);
        return new FilesystemChunkedFile(filePath);
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
