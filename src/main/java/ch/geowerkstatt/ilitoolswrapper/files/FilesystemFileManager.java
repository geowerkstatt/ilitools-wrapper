package ch.geowerkstatt.ilitoolswrapper.files;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class FilesystemFileManager implements FileManager {
    @Override
    public ProcessingFile createProcessingFile(String folderName, String fileName, String fileExtension) {
        checkFileExtension(fileExtension);

        Path filePath = Paths.get(folderName, fileName + "." + fileExtension);
        return new FilesystemProcessingFile(filePath);
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
