package ch.geowerkstatt.ilitoolswrapper.runner;

import ch.geowerkstatt.ilitoolswrapper.files.DeleteFileVisitor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages a session cache directory for ilitools processes. The session cache is a temporary directory that can be
 * populated with files from a shared cache and written back to the shared cache at the end of a session.
 */
public final class IliSessionCache implements Closeable {
    private static final String SESSION_CACHE_DIR_NAME = ".ili-session-cache";
    private static final Logger LOGGER = Logger.getLogger(IliSessionCache.class.getName());

    @Nullable
    private final Path sharedCacheDir;
    @Nullable
    private final Path sessionCacheParent;
    @Nullable
    private Path sessionCacheDir;

    /**
     * Creates a new {@link IliSessionCache} instance.
     */
    IliSessionCache(@Nullable Path sharedCacheDir, @Nullable Path sessionCacheParent) {
        this.sharedCacheDir = sharedCacheDir;
        this.sessionCacheParent = sessionCacheParent;
    }

    /**
     * Creates a new {@link IliSessionCache} instance using the "ILI_CACHE" environment variable as the shared cache
     * directory and creates the session inside "SESSION_CACHE_DIR".
     */
    public static IliSessionCache fromEnvironment() {
        String iliCacheEnv = System.getenv("ILI_CACHE");
        String sessionCacheEnv = System.getenv("SESSION_CACHE_DIR");
        Path sharedCacheDir = (iliCacheEnv != null && !iliCacheEnv.isEmpty()) ? Path.of(iliCacheEnv) : null;
        Path sessionCacheParent = (sessionCacheEnv != null && !sessionCacheEnv.isEmpty()) ? Path.of(sessionCacheEnv) : null;
        return new IliSessionCache(sharedCacheDir, sessionCacheParent);
    }

    /**
     * Deletes session cache directories left behind in "SESSION_CACHE_DIR" by processes that terminated abnormally.
     */
    public static void cleanupOrphanedSessionCaches() {
        String sessionCacheEnv = System.getenv("SESSION_CACHE_DIR");
        if (sessionCacheEnv == null || sessionCacheEnv.isEmpty()) {
            return;
        }

        Path sessionCacheParent = Path.of(sessionCacheEnv);
        if (!Files.isDirectory(sessionCacheParent)) {
            return;
        }

        try (var orphans = Files.newDirectoryStream(sessionCacheParent, SESSION_CACHE_DIR_NAME + "*")) {
            for (Path orphan : orphans) {
                try {
                    Files.walkFileTree(orphan, new DeleteFileVisitor());
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to delete orphaned session cache " + orphan + ".", e);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to scan for orphaned session caches in " + sessionCacheParent + ".", e);
        }
    }

    /**
     * Sets up the session cache directory. If a shared cache directory is specified, its contents are copied into the session cache.
     * @return the path to the session cache directory
     * @throws IOException if an I/O error occurs
     */
    public Path setupSessionCacheDir() throws IOException {
        if (sessionCacheParent != null) {
            Files.createDirectories(sessionCacheParent);
            sessionCacheDir = Files.createTempDirectory(sessionCacheParent, SESSION_CACHE_DIR_NAME);
        } else {
            sessionCacheDir = Files.createTempDirectory(SESSION_CACHE_DIR_NAME);
        }

        if (sharedCacheDir != null) {
            Files.createDirectories(sharedCacheDir);
            mirrorTree(sharedCacheDir, sessionCacheDir, IliSessionCache::copyFile);
        }
        return sessionCacheDir;
    }

    private static void copyFile(Path sourceFile, Path targetFile) throws IOException {
        // Preserve the modification time so writeToSharedCache can tell which entries the tool changed.
        Files.copy(sourceFile, targetFile, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /**
     * Writes the relevant contents of the session cache directory back to the shared cache directory.
     * @throws IOException if an I/O error occurs
     */
    public void writeToSharedCache() throws IOException {
        if (sharedCacheDir != null && sessionCacheDir != null) {
            mirrorTree(sessionCacheDir, sharedCacheDir, IliSessionCache::moveIfNewer);
        }
    }

    private static void moveIfNewer(Path file, Path targetFilePath) throws IOException {
        if (Files.exists(targetFilePath)
                && getModificationTime(file).compareTo(getModificationTime(targetFilePath)) <= 0) {
            return;
        }
        try {
            Files.move(file, targetFilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(file, targetFilePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    private interface CacheEntryAction {
        void apply(Path source, Path target) throws IOException;
    }

    private static void mirrorTree(Path source, Path target, CacheEntryAction fileAction)
            throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attrs) throws IOException {
                // Skip directories of models extracted from a GPKG file.
                if (dir.getFileName().toString().startsWith("jdbc&003a")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) {
                Path targetFilePath = target.resolve(source.relativize(file).toString());
                try {
                    fileAction.apply(file, targetFilePath);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to mirror cache entry " + file + " to " + targetFilePath + ".", e);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult visitFileFailed(@NonNull Path file, @NonNull IOException exc) {
                LOGGER.log(Level.WARNING, "Failed to access cache entry " + file + ".", exc);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static FileTime getModificationTime(Path path) throws IOException {
        BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
        return attr.lastModifiedTime();
    }

    @Override
    public void close() throws IOException {
        if (sessionCacheDir != null) {
            Files.walkFileTree(sessionCacheDir, new DeleteFileVisitor());
        }
    }
}
