package ch.geowerkstatt.ilitoolswrapper.files;

import java.io.IOException;

/**
 * Creates {@link ProcessingFile}s that are used as temporary files during processing.
 */
public interface FileManager {
    /**
     * Name of the subfolder in the processing directory where model files are stored, addressed as
     * {@code %ITF_DIR/models} or {@code %XTF_DIR/models} in the model dirs.
     */
    String MODEL_FILES_SUBFOLDER = "models";

    /**
     * Sets up the processing directory structure for a given folder name.
     *
     * @param folderName the name of the folder to set up
     * @throws IOException if an I/O error occurs while creating the directories
     */
    void setupProcessingDirectory(String folderName) throws IOException;

    /**
     * Creates a temporary file for processing.
     *
     * @param folderName    the directory the file is created in, automatically created on write if it does not exist
     * @param fileName      the file name without extension
     * @param fileExtension the file extension without the leading dot
     * @return a {@link ProcessingFile} the caller can write to
     * @throws IllegalArgumentException if {@code fileExtension} is invalid
     */
    ProcessingFile createProcessingFile(String folderName, String fileName, String fileExtension);

    /**
     * Deletes the specified directory of processing files.
     *
     * @param folderName the directory name of the files
     * @throws IOException if deleting the directory failed
     */
    void deleteProcessingFiles(String folderName) throws IOException;
}
