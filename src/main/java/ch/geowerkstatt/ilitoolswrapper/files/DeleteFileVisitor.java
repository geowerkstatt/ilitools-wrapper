package ch.geowerkstatt.ilitoolswrapper.files;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A {@link SimpleFileVisitor} that deletes all files and directories.
 */
public final class DeleteFileVisitor extends SimpleFileVisitor<Path> {
    @Override
    @NonNull
    public FileVisitResult postVisitDirectory(@NonNull Path dir, @Nullable IOException exc) throws IOException {
        Files.delete(dir);
        return super.postVisitDirectory(dir, exc);
    }

    @Override
    @NonNull
    public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
        Files.delete(file);
        return super.visitFile(file, attrs);
    }
}
