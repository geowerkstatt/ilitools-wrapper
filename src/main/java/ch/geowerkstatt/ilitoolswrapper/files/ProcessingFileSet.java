package ch.geowerkstatt.ilitoolswrapper.files;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bookkeeping for the temporary files of a single processing session. Files are grouped by a caller-defined
 * type key and share one session directory managed by a {@link FileManager}, so the whole set can be closed
 * and deleted together.
 *
 * @param <F> the enum used to classify the files by role
 */
public final class ProcessingFileSet<F extends Enum<F>> {
    private static final Logger LOGGER = Logger.getLogger(ProcessingFileSet.class.getName());

    private final FileManager fileManager;
    private final UUID sessionId = UUID.randomUUID();
    private final Map<F, List<ProcessingFile>> files = new HashMap<>();

    /**
     * Creates a new file set backed by the given {@link FileManager}.
     *
     * @param fileManager the file manager used to create and delete the underlying temporary files
     */
    public ProcessingFileSet(FileManager fileManager) {
        this.fileManager = fileManager;
    }

    /**
     * Creates a temporary file of the given type and registers it in the set.
     *
     * @param type the type the file is grouped under
     * @param fileName the file name without extension
     * @param extension the file extension without the leading dot
     * @return the created {@link ProcessingFile}
     * @throws IllegalArgumentException if {@code extension} is invalid
     */
    public ProcessingFile create(F type, String fileName, String extension) {
        ProcessingFile file = fileManager.createProcessingFile(sessionId.toString(), fileName, extension);
        files.computeIfAbsent(type, _ -> new ArrayList<>()).add(file);
        return file;
    }

    /**
     * Creates a temporary file of the given type in a subfolder of the session directory and registers it in the
     * set. Subfolders keep files of different provenance apart, so a model dir entry can address one of them.
     *
     * @param type the type the file is grouped under
     * @param subfolder the folder below the session directory, a plain folder name decided by the calling service
     * @param fileName the file name without extension
     * @param extension the file extension without the leading dot
     * @return the created {@link ProcessingFile}
     * @throws IllegalArgumentException if {@code extension} is invalid
     */
    public ProcessingFile create(F type, String subfolder, String fileName, String extension) {
        ProcessingFile file = fileManager.createProcessingFile(sessionId + "/" + subfolder, fileName, extension);
        files.computeIfAbsent(type, _ -> new ArrayList<>()).add(file);
        return file;
    }

    /**
     * Returns the single file registered for the given type, or an empty optional if there is no file or more
     * than one file of that type. Callers that have to tell those two cases apart use {@link #getAll} instead.
     *
     * @param type the type to look up
     * @return the single file of that type, if exactly one exists
     */
    public Optional<ProcessingFile> getSingle(F type) {
        List<ProcessingFile> filesOfType = files.get(type);
        if (filesOfType == null || filesOfType.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(filesOfType.getFirst());
    }

    /**
     * Returns all files registered for the given type, in the order they were created.
     *
     * @param type the type to look up
     * @return the files of that type, an empty list if there is none
     */
    public List<ProcessingFile> getAll(F type) {
        List<ProcessingFile> filesOfType = files.get(type);
        return filesOfType == null ? List.of() : List.copyOf(filesOfType);
    }

    /**
     * Returns whether no files have been registered yet.
     *
     * @return {@code true} if the set contains no files
     */
    public boolean isEmpty() {
        return files.isEmpty();
    }

    /**
     * Returns the total number of files of any type.
     *
     * @return the total number of files of any type
     */
    public int size() {
        return files.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Closes all registered files, logging and continuing on individual failures.
     *
     * @return {@code true} if every file closed successfully
     */
    public boolean closeAll() {
        boolean success = true;
        for (List<ProcessingFile> filesOfType : files.values()) {
            for (ProcessingFile file : filesOfType) {
                try {
                    file.close();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to close processing file.", e);
                    success = false;
                }
            }
        }
        return success;
    }

    /**
     * Closes all files, deletes the session directory, and clears the set.
     */
    public void deleteAll() {
        closeAll();
        try {
            fileManager.deleteProcessingFiles(sessionId.toString());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to delete processing files.", e);
        }
        files.clear();
    }
}
