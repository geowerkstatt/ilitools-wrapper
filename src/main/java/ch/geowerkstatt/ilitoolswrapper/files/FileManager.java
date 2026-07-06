package ch.geowerkstatt.ilitoolswrapper.files;

import java.io.IOException;

/**
 * Creates {@link ProcessingFile}s that are used as temporary files during processing.
 */
public interface FileManager {
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
