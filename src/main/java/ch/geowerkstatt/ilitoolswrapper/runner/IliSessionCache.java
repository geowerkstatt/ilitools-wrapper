package ch.geowerkstatt.ilitoolswrapper.runner;

import ch.geowerkstatt.ilitoolswrapper.files.DeleteFileVisitor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * Manages a session cache directory for ilitools processes. The session cache is a temporary directory that can be
 * populated with files from a shared cache and written back to the shared cache at the end of a session.
 */
public final class IliSessionCache implements Closeable {
    private static final String SESSION_CACHE_DIR_NAME = ".ili-session-cache";

    @Nullable
    private final Path sharedCacheDir;
    @Nullable
    private Path sessionCacheDir;

    /**
     * Creates a new {@link IliSessionCache} instance.
     */
    public IliSessionCache() {
        String iliCacheEnv = System.getenv("ILI_CACHE");
        if (iliCacheEnv != null && !iliCacheEnv.isEmpty()) {
            this.sharedCacheDir = Path.of(iliCacheEnv);
        } else {
            this.sharedCacheDir = null;
        }
    }

    /**
     * Sets up the session cache directory. If a shared cache directory is specified, its contents are copied into the session cache.
     * @return the path to the session cache directory
     * @throws IOException if an I/O error occurs
     */
    public Path setupSessionCacheDir() throws IOException {
        this.sessionCacheDir = Files.createTempDirectory(SESSION_CACHE_DIR_NAME);
        Files.createDirectories(sessionCacheDir);
        if (sharedCacheDir != null) {
            if (Files.exists(sharedCacheDir)) {
                copyFolder(sharedCacheDir, sessionCacheDir);
            } else {
                Files.createDirectories(sharedCacheDir);
            }
        }
        return sessionCacheDir;
    }

    /**
     * Writes the relevant contents of the session cache directory back to the shared cache directory.
     * @throws IOException if an I/O error occurs
     */
    public void writeToSharedCache() throws IOException {
        if (sharedCacheDir != null && sessionCacheDir != null) {
            moveFolderExceptJdbc(sessionCacheDir, sharedCacheDir);
        }
    }

    private static void copyFolder(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void moveFolderExceptJdbc(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attrs)
                    throws IOException {
                String directoryName = dir.getFileName().toString();
                // Skip directories of models extracted from a GPKG file
                if (directoryName.startsWith("jdbc&003a")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                Path targetSubPath = target.resolve(source.relativize(dir).toString());
                if (!Files.exists(targetSubPath)) {
                    Files.move(dir, targetSubPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.SKIP_SUBTREE;
                }

                Files.createDirectories(targetSubPath);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs)
                    throws IOException {
                Path targetFilePath = target.resolve(source.relativize(file).toString());
                if (!Files.exists(targetFilePath)) {
                    Files.move(file, targetFilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }

                boolean sourceFileIsNewer = getModificationTime(file).compareTo(getModificationTime(targetFilePath)) > 0;
                if (sourceFileIsNewer) {
                    Files.move(file, targetFilePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }

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
