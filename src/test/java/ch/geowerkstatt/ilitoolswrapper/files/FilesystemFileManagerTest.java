package ch.geowerkstatt.ilitoolswrapper.files;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

public final class FilesystemFileManagerTest {
    private final FilesystemFileManager fileManager = new FilesystemFileManager();

    @ParameterizedTest
    @NullAndEmptySource
    void createProcessingFileRejectsMissingExtension(String fileExtension) {
        assertThrows(
                IllegalArgumentException.class,
                () -> fileManager.createProcessingFile("folder", "upload", fileExtension));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc", "x.tf", "xtf ", "x/f", "x.", "."})
    void createProcessingFileRejectsNonAlphanumericExtension(String fileExtension) {
        assertThrows(
                IllegalArgumentException.class,
                () -> fileManager.createProcessingFile("folder", "upload", fileExtension));
    }
}
