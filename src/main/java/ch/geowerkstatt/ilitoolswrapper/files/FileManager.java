package ch.geowerkstatt.ilitoolswrapper.files;

/**
 * Creates {@link ChunkedFile}s that accept uploaded content in successive chunks.
 */
public interface FileManager {
    /**
     * Creates a file to receive an upload, ready to be written to in chunks.
     *
     * @param folderName    the directory the file is created in, automatically created if it does not exist
     * @param fileName      the file name without extension
     * @param fileExtension the file extension without the leading dot
     * @return a {@link ChunkedFile} the caller writes to and then closes
     * @throws IllegalArgumentException if {@code fileExtension} is invalid
     * @throws Exception                if the file cannot be created
     */
    ChunkedFile createChunkedFile(String folderName, String fileName, String fileExtension) throws Exception;
}
