package ch.geowerkstatt.ilitoolswrapper.files;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory {@link FileManager} that hands out {@link InMemoryChunkedFile}s and records every creation request,
 * so tests can inspect the received content and arguments without writing anything to disk.
 */
public final class InMemoryFileManager implements FileManager {
    private final List<InMemoryChunkedFile> createdFiles = new ArrayList<>();
    private Exception failure;

    public void failNextCreationWith(Exception exception) {
        this.failure = exception;
    }

    @Override
    public ChunkedFile createChunkedFile(String folderName, String fileName, String fileExtension) throws Exception {
        if (failure != null) {
            throw failure;
        }
        InMemoryChunkedFile file = new InMemoryChunkedFile();
        createdFiles.add(file);
        return file;
    }

    public List<InMemoryChunkedFile> createdFiles() {
        return createdFiles;
    }

    public InMemoryChunkedFile lastCreatedFile() {
        return createdFiles.getLast();
    }
}
