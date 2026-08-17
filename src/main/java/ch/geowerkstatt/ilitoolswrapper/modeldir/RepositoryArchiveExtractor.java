package ch.geowerkstatt.ilitoolswrapper.modeldir;

import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Materializes an INTERLIS model repository that a client sent as a ZIP archive into the session directory of the
 * request, where the tool placeholders {@code %ITF_DIR} and {@code %XTF_DIR} resolve to it.
 *
 * <p>The threat model treats the calling instance as possibly compromised, so entry paths are confined to the target
 * directory (Zip Slip) and the extraction is bounded by an entry count and by the total extracted size (archive
 * bombs). Every violation is an {@link IllegalArgumentException}, which the services map to {@code INVALID_ARGUMENT}.
 */
public final class RepositoryArchiveExtractor {
    private static final int DEFAULT_MAX_ENTRIES = 2000;
    private static final long DEFAULT_MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;
    private static final int COPY_BUFFER_SIZE = 8192;

    private final int maxEntries;
    private final long maxUncompressedBytes;

    /**
     * Creates an extractor with the limits used in production.
     */
    public RepositoryArchiveExtractor() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_MAX_UNCOMPRESSED_BYTES);
    }

    /**
     * Creates an extractor with explicit limits.
     *
     * @param maxEntries the maximum number of entries the archive may contain
     * @param maxUncompressedBytes the maximum total size the extracted entries may reach
     */
    public RepositoryArchiveExtractor(int maxEntries, long maxUncompressedBytes) {
        this.maxEntries = maxEntries;
        this.maxUncompressedBytes = maxUncompressedBytes;
    }

    /**
     * Extracts the repository archive a request carried, if it carried one. The archive is extracted into the
     * directory it was received in, which is the session directory of the request and what the tool placeholders
     * {@code %ITF_DIR} and {@code %XTF_DIR} expand to.
     *
     * @param archiveFiles the received files of the repository archive type, empty when the request carried none
     * @throws IllegalArgumentException if more than one archive was sent, or for any reason
     *     {@link #extract(Path, Path)} rejects the archive
     * @throws IOException if the session directory cannot be determined, or reading or writing fails
     */
    public void extractReceived(List<ProcessingFile> archiveFiles) throws IOException {
        if (archiveFiles.isEmpty()) {
            return;
        }
        if (archiveFiles.size() > 1) {
            throw new IllegalArgumentException("At most one repository archive can be sent.");
        }

        Path archive = archiveFiles.getFirst().filePath().toAbsolutePath();
        Path sessionDirectory = archive.getParent();
        if (sessionDirectory == null) {
            throw new IOException("The repository archive " + archive + " is not inside a session directory.");
        }

        extract(archive, sessionDirectory);
    }

    /**
     * Extracts every entry of the archive into the target directory, keeping the directory structure of the archive.
     *
     * @param archive the ZIP archive to read
     * @param targetDirectory the directory the entries are written to, the session directory of the request
     * @throws IllegalArgumentException if the archive cannot be read as a ZIP archive, an entry escapes the target
     *     directory, an entry would replace an existing file, or a limit is exceeded
     * @throws IOException if reading the archive or writing an entry fails
     */
    public void extract(Path archive, Path targetDirectory) throws IOException {
        Path root = targetDirectory.toAbsolutePath().normalize();
        int entryCount = 0;
        long extractedBytes = 0;

        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                entryCount++;
                if (entryCount > maxEntries) {
                    throw new IllegalArgumentException("Repository archive must not contain more than " + maxEntries + " entries.");
                }

                Path target = resolveWithin(root, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    extractedBytes += extractEntry(zipFile, entry, target, maxUncompressedBytes - extractedBytes);
                }
            }
        } catch (ZipException e) {
            throw new IllegalArgumentException("Repository archive is not a readable ZIP archive.", e);
        }
    }

    private long extractEntry(ZipFile zipFile, ZipEntry entry, Path target, long remainingBytes) throws IOException {
        if (Files.exists(target)) {
            throw new IllegalArgumentException("Repository archive entry \"" + entry.getName() + "\" would replace a file that already exists in the session directory.");
        }

        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Repository archive entry \"" + entry.getName() + "\" does not name a file.");
        }
        Files.createDirectories(parent);

        long written = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream entryStream = zipFile.getInputStream(entry);
             OutputStream targetStream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            int read = entryStream.read(buffer);
            while (read > 0) {
                if (written + read > remainingBytes) {
                    throw new IllegalArgumentException("Repository archive must not exceed " + maxUncompressedBytes + " bytes when extracted.");
                }

                targetStream.write(buffer, 0, read);
                written += read;
                read = entryStream.read(buffer);
            }
        }
        return written;
    }

    private static Path resolveWithin(Path root, String entryName) {
        // ZIP entry names are relative by specification. A leading separator is rejected explicitly, because it is
        // absolute on Linux but would silently be resolved inside the target directory on Windows.
        if (entryName.startsWith("/") || entryName.startsWith("\\")) {
            throw new IllegalArgumentException("Repository archive entry \"" + entryName + "\" must be a relative path.");
        }

        Path target;
        try {
            target = root.resolve(entryName).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Repository archive entry \"" + entryName + "\" is not a valid path.", e);
        }

        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Repository archive entry \"" + entryName + "\" would be written outside the session directory.");
        }
        return target;
    }
}
