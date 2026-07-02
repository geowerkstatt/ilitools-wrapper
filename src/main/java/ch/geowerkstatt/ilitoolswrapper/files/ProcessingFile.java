package ch.geowerkstatt.ilitoolswrapper.files;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * A temporary file used for processing.
 */
public interface ProcessingFile {
    /**
     * Returns the path of the underlying file.
     *
     * @return the path of the file
     */
    Path filePath();

    /**
     * Opens the stream that content can be written to, creating the underlying file. Calling this more than once
     * returns the stream opened by the first call. The stream is closed by {@link #closeOutputStream()}.
     *
     * @return the output stream backing this file
     * @throws IOException if the file cannot be created or opened
     */
    OutputStream outputStream() throws IOException;

    /**
     * Closes the stream opened by {@link #outputStream()}.
     *
     * @throws IOException if an error occurred while closing the stream
     */
    void closeOutputStream() throws IOException;

    /**
     * Deletes the file. Should be called if the file is no longer needed.
     */
    void delete() throws IOException;
}
