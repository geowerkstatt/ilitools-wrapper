package ch.geowerkstatt.ilitoolswrapper.files;

import java.io.IOException;
import java.io.InputStream;
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
     * Opens the stream that content can be read from. Calling this more than once returns the stream opened
     * by the first call.
     *
     * @return the input stream backing this file
     * @throws IOException if the file cannot be found or opened
     */
    InputStream inputStream() throws IOException;

    /**
     * Opens the stream that content can be written to, creating the underlying file. Calling this more than once
     * returns the stream opened by the first call.
     *
     * @return the output stream backing this file
     * @throws IOException if the file cannot be created or opened
     */
    OutputStream outputStream() throws IOException;
}
