package ch.geowerkstatt.ilitoolswrapper.files;

import org.jspecify.annotations.Nullable;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * In-memory {@link FileManager} that hands out {@link InMemoryProcessingFile}s and records every creation request,
 * so tests can inspect the received content and arguments without writing anything to disk.
 */
public final class InMemoryFileManager implements FileManager {
    private final List<InMemoryProcessingFile> createdFiles = new ArrayList<>();
    private final Set<String> createdFolders = new HashSet<>();
    private @Nullable RuntimeException failure;

    public void failNextCreationWith(RuntimeException exception) {
        this.failure = exception;
    }

    @Override
    public void setupProcessingDirectory(String folderName) {
        createdFolders.add(folderName);
    }

    @Override
    public ProcessingFile createProcessingFile(String folderName, String fileName, String fileExtension) {
        if (failure != null) {
            throw failure;
        }
        String firstFolder = Pattern.compile("[/\\\\]").split(folderName)[0];
        if (!createdFolders.contains(firstFolder)) {
            throw new IllegalStateException("Processing directory not set up: " + folderName);
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
