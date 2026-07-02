package ch.geowerkstatt.ilitoolswrapper.files;

import com.google.protobuf.ByteString;

/**
 * A file that is written incrementally as chunks arrive. Chunks are appended in the order received and
 * the file is finalized when it is {@linkplain AutoCloseable#close() closed}.
 */
public interface ChunkedFile extends AutoCloseable {
    /**
     * Appends a chunk of content to the end of the file.
     *
     * @param chunk the content to append
     * @throws Exception if the chunk cannot be written
     */
    void writeChunk(ByteString chunk) throws Exception;

    /**
     * Deletes the file. Should be called if the file is no longer needed.
     */
    void delete() throws Exception;
}
