package ch.geowerkstatt.ilitoolswrapper.files;

/**
 * Creates {@link ProcessingFile}s that are used as temporary files during processing.
 */
public interface FileManager {
    /**
     * Creates a file to receive an upload, ready to be written to in chunks.
     *
     * @param folderName    the directory the file is created in, automatically created if it does not exist
     * @param fileName      the file name without extension
     * @param fileExtension the file extension without the leading dot
     * @return a {@link ProcessingFile} the caller can write to
     * @throws IllegalArgumentException if {@code fileExtension} is invalid
     */
    ProcessingFile createProcessingFile(String folderName, String fileName, String fileExtension);
}
