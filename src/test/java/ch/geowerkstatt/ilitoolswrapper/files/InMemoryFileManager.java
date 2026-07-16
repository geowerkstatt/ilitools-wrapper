package ch.geowerkstatt.ilitoolswrapper.files;

import org.jspecify.annotations.Nullable;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link FileManager} that hands out {@link InMemoryProcessingFile}s and records every creation request,
 * so tests can inspect the received content and arguments without writing anything to disk.
 */
public final class InMemoryFileManager implements FileManager {
    private final List<InMemoryProcessingFile> createdFiles = new ArrayList<>();
    private @Nullable RuntimeException failure;

    public void failNextCreationWith(RuntimeException exception) {
        this.failure = exception;
    }

    @Override
    public ProcessingFile createProcessingFile(String folderName, String fileName, String fileExtension) {
        if (failure != null) {
            throw failure;
        }
        InMemoryProcessingFile file = new InMemoryProcessingFile(Paths.get(folderName, fileName + "." + fileExtension));
        createdFiles.add(file);
        return file;
    }

    @Override
    public void deleteProcessingFiles(String folderName) {
    }

    public List<InMemoryProcessingFile> createdFiles() {
        return createdFiles;
    }

    public InMemoryProcessingFile lastCreatedFile() {
        return createdFiles.getLast();
    }
}
