package ch.geowerkstatt.ilitoolswrapper.modeldir;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public final class RepositoryCatalogTest {
    @TempDir
    private Path root;

    @Test
    void availableIsEmptyWithoutConfiguredDirectory() {
        assertEquals(Set.of(), new RepositoryCatalog(null).available());
    }

    @Test
    void availableIsEmptyWhenTheDirectoryDoesNotExist() {
        assertEquals(Set.of(), new RepositoryCatalog(root.resolve("missing")).available());
    }

    @Test
    void availableOffersTheSubfoldersButNoFiles() throws IOException {
        Files.createDirectory(root.resolve("dmav@0.1.1"));
        Files.writeString(root.resolve("ilidata.xml"), "a file next to the repositories is none of them");

        assertEquals(Set.of("dmav@0.1.1"), new RepositoryCatalog(root).available());
    }

    @Test
    void availableIsReadOnEveryCallSoANewRepositoryNeedsNoRestart() throws IOException {
        RepositoryCatalog catalog = new RepositoryCatalog(root);
        assertEquals(Set.of(), catalog.available());

        Files.createDirectory(root.resolve("added-later@1.0"));

        assertEquals(Set.of("added-later@1.0"), catalog.available(), "The catalog must not cache the set it offers.");
    }

    @Test
    void resolveReturnsTheAbsoluteDirectoryOfAnOfferedRepository() throws IOException {
        Path repository = Files.createDirectory(root.resolve("dmav@0.1.1"));

        assertEquals(repository.toAbsolutePath().toString(), new RepositoryCatalog(root).resolve("%REPOSITORIES/dmav@0.1.1"));
    }

    @Test
    void resolveRejectsARepositoryThatIsNotOfferedAndNamesTheOfferedOnes() throws IOException {
        Files.createDirectory(root.resolve("dmav@0.1.1"));

        String message = assertRejected(new RepositoryCatalog(root), "%REPOSITORIES/dmav@0.2.0");
        assertTrue(message.contains("dmav@0.1.1"), "The message should name what is offered: " + message);
    }

    @Test
    void resolveRejectsEveryRepositoryWithoutConfiguredDirectory() {
        assertRejected(new RepositoryCatalog(null), "%REPOSITORIES/dmav@0.1.1");
    }

    @Test
    void resolveRejectsAnEntryThatNamesNoRepository() throws IOException {
        Files.createDirectory(root.resolve("dmav@0.1.1"));
        RepositoryCatalog catalog = new RepositoryCatalog(root);

        assertRejected(catalog, "%REPOSITORIES");
        assertRejected(catalog, "%REPOSITORIES/");
    }

    @Test
    void resolveRejectsAPathInsteadOfAnId() throws IOException {
        Files.createDirectories(root.resolve("dmav@0.1.1").resolve("sub"));
        RepositoryCatalog catalog = new RepositoryCatalog(root);

        assertRejected(catalog, "%REPOSITORIES/dmav@0.1.1/sub");
        assertRejected(catalog, "%REPOSITORIES/..");
        assertRejected(catalog, "%REPOSITORIES/.");
        assertRejected(catalog, "%REPOSITORIES/dmav@0.1.1\\..");
    }

    private static String assertRejected(RepositoryCatalog catalog, String modelDir) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.resolve(modelDir),
                "Entry should have been rejected: " + modelDir);
        String message = Objects.requireNonNull(exception.getMessage(), "Rejection must carry a message.");
        assertTrue(message.contains(modelDir), "Message should name the rejected entry, but was: " + message);
        return message;
    }
}
