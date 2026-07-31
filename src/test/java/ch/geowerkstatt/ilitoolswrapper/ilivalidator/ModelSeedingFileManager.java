package ch.geowerkstatt.ilitoolswrapper.ilivalidator;

import ch.geowerkstatt.ilitoolswrapper.files.FileManager;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link FileManager} that writes an INTERLIS model file into every processing session directory before
 * delegating to a wrapped manager.
 *
 * <p>{@code ilivalidator} resolves models from the default model directory
 * {@code %ITF_DIR;http://models.interlis.ch/;%JAR_DIR/ilimodels}, where {@code %ITF_DIR} is the folder of the
 * transfer file being validated. Placing the model next to the transfer file lets the integration tests resolve
 * their custom model locally, keeping them independent of the remote model repository.
 */
final class ModelSeedingFileManager implements FileManager {
    private final FileManager delegate;
    private final String modelFileName;
    private final byte[] modelContent;
    private final Set<String> seededFolders = ConcurrentHashMap.newKeySet();

    ModelSeedingFileManager(FileManager delegate, String modelFileName, byte[] modelContent) {
        this.delegate = delegate;
        this.modelFileName = modelFileName;
        this.modelContent = modelContent.clone();
    }

    @Override
    public ProcessingFile createProcessingFile(String folderName, String fileName, String fileExtension) {
        seedModel(folderName);
        return delegate.createProcessingFile(folderName, fileName, fileExtension);
    }

    @Override
    public void deleteProcessingFiles(String folderName) throws IOException {
        seededFolders.remove(folderName);
        delegate.deleteProcessingFiles(folderName);
    }

    private void seedModel(String folderName) {
        if (!seededFolders.add(folderName)) {
            return;
        }

        try (ProcessingFile modelFile = delegate.createProcessingFile(folderName, modelFileName, "ili");
             OutputStream outputStream = modelFile.outputStream()) {
            outputStream.write(modelContent);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to provide model file for validation.", e);
        }
    }
}
