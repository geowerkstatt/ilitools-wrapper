package ch.geowerkstatt.ilitoolswrapper;

import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Stateless helpers shared by the gRPC integration tests: loading classpath resources and preparing the
 * per-service output directory.
 */
public final class IntegrationTestSupport {
    private IntegrationTestSupport() {
    }

    /**
     * Opens a classpath resource as a stream.
     *
     * @throws IOException if the resource does not exist
     */
    public static InputStream getResourceStream(String resourcePath) throws IOException {
        ClassLoader classLoader = IntegrationTestSupport.class.getClassLoader();
        InputStream stream = classLoader.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        return stream;
    }

    /**
     * Reads a classpath resource fully into a byte array.
     *
     * @throws IOException if the resource does not exist
     */
    public static byte[] getResourceBytes(String resourcePath) throws IOException {
        try (InputStream stream = getResourceStream(resourcePath)) {
            return stream.readAllBytes();
        }
    }

    /**
     * Creates the output directory, or deletes any files left from a previous run when it already exists.
     */
    public static void prepareOutputDirectory(Path outputDir) throws IOException {
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
            return;
        }

        Files.walkFileTree(outputDir, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return super.visitFile(file, attrs);
            }
        });
    }
}
