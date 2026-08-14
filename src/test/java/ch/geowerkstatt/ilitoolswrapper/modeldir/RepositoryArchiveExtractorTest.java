package ch.geowerkstatt.ilitoolswrapper.modeldir;

import ch.geowerkstatt.ilitoolswrapper.files.FilesystemProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public final class RepositoryArchiveExtractorTest {
    @TempDir
    Path tempDir;

    @Test
    void extractsEntriesAndKeepsDirectoryStructure() throws IOException {
        Path archive = archiveWith(Map.of(
                "ilidata.xml", "<TRANSFER/>",
                "profiles/default.toml", "[ch.ehi.ilivalidator]",
                "models/SimpleModel.ili", "MODEL SimpleModel"));
        Path target = targetDirectory();

        new RepositoryArchiveExtractor().extract(archive, target);

        assertEquals("<TRANSFER/>", Files.readString(target.resolve("ilidata.xml")), "The repository index belongs at the top level.");
        assertEquals("[ch.ehi.ilivalidator]", Files.readString(target.resolve("profiles/default.toml")));
        assertEquals("MODEL SimpleModel", Files.readString(target.resolve("models/SimpleModel.ili")));
    }

    @Test
    void rejectsEntryPointingOutsideTheTargetDirectory() throws IOException {
        Path archive = archiveWith(Map.of("../escaped.xml", "gotcha"));
        Path target = targetDirectory();

        assertRejected(archive, target, "escaped.xml");
        assertFalse(Files.exists(Objects.requireNonNull(target.getParent()).resolve("escaped.xml")), "Nothing may be written outside the target directory.");
    }

    @Test
    void rejectsEntryWithAbsolutePath() throws IOException {
        Path archive = archiveWith(Map.of("/etc/passwd", "root:x:0:0"));
        Path target = targetDirectory();

        assertRejected(archive, target, "passwd");
    }

    @Test
    void rejectsMoreEntriesThanAllowed() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            entries.put("model" + i + ".ili", "MODEL");
        }
        Path archive = archiveWith(entries);
        Path target = targetDirectory();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RepositoryArchiveExtractor(2, 1024).extract(archive, target));
        assertTrue(messageOf(exception).contains("2"), "The message should state the limit, but was: " + messageOf(exception));
    }

    @Test
    void rejectsArchiveExceedingTheUncompressedSizeLimit() throws IOException {
        Path archive = archiveWith(Map.of("catalog.xml", "x".repeat(100)));
        Path target = targetDirectory();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RepositoryArchiveExtractor(10, 16).extract(archive, target));
        assertTrue(messageOf(exception).contains("16"), "The message should state the limit, but was: " + messageOf(exception));
    }

    @Test
    void rejectsEntryThatWouldReplaceAnExistingFile() throws IOException {
        Path archive = archiveWith(Map.of("file1.xtf", "archive content"));
        Path target = targetDirectory();
        Files.writeString(target.resolve("file1.xtf"), "received content");

        assertRejected(archive, target, "file1.xtf");
        assertEquals("received content", Files.readString(target.resolve("file1.xtf")), "An already received file must not be replaced.");
    }

    @Test
    void rejectsArchiveThatIsNotAZipFile() throws IOException {
        Path archive = tempDir.resolve("broken.zip");
        Files.writeString(archive, "this is not a zip archive");
        Path target = targetDirectory();

        assertRejected(archive, target, "ZIP");
    }

    @Test
    void extractsAReceivedArchiveNextToItself() throws IOException {
        Path archive = archiveWith(Map.of("ilidata.xml", "<TRANSFER/>"));

        new RepositoryArchiveExtractor().extractReceived(List.of(new FilesystemProcessingFile(archive)));

        assertEquals("<TRANSFER/>", Files.readString(tempDir.resolve("ilidata.xml")), "The archive should be extracted into the directory it was received in.");
    }

    @Test
    void doesNothingWhenNoArchiveWasReceived() {
        assertDoesNotThrow(() -> new RepositoryArchiveExtractor().extractReceived(List.of()));
    }

    @Test
    void rejectsMoreThanOneReceivedArchive() throws IOException {
        Path archive = archiveWith(Map.of("ilidata.xml", "<TRANSFER/>"));
        List<ProcessingFile> archives = List.of(new FilesystemProcessingFile(archive), new FilesystemProcessingFile(archive));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RepositoryArchiveExtractor().extractReceived(archives));
        assertTrue(messageOf(exception).contains("one repository archive"), "Message should state the rule, but was: " + messageOf(exception));
    }

    private static void assertRejected(Path archive, Path target, String expectedInMessage) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RepositoryArchiveExtractor().extract(archive, target));
        assertTrue(messageOf(exception).contains(expectedInMessage), "Message should mention \"" + expectedInMessage + "\", but was: " + messageOf(exception));
    }

    private static String messageOf(Exception exception) {
        return Objects.requireNonNull(exception.getMessage(), "Rejection must carry a message.");
    }

    private Path targetDirectory() throws IOException {
        return Files.createDirectories(tempDir.resolve("session"));
    }

    private Path archiveWith(Map<String, String> entries) throws IOException {
        Path archive = tempDir.resolve("repository.zip");
        try (OutputStream fileStream = Files.newOutputStream(archive);
             ZipOutputStream zipStream = new ZipOutputStream(fileStream)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zipStream.putNextEntry(new ZipEntry(entry.getKey()));
                zipStream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zipStream.closeEntry();
            }
        }
        return archive;
    }
}
