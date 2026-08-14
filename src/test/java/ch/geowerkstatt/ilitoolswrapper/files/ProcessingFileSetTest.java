package ch.geowerkstatt.ilitoolswrapper.files;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ProcessingFileSetTest {
    private enum TestType {
        FIRST,
        SECOND
    }

    private final InMemoryFileManager fileManager = new InMemoryFileManager();
    private final ProcessingFileSet<TestType> files = new ProcessingFileSet<>(fileManager);

    @Test
    void createRegistersFileAndReturnsIt() {
        ProcessingFile file = files.create(TestType.FIRST, "upload", "xtf");

        assertSame(file, fileManager.lastCreatedFile());
        assertEquals(file, files.getSingle(TestType.FIRST).orElseThrow());
    }

    @Test
    void createSharesOneSessionDirectoryAcrossFiles() {
        ProcessingFile first = files.create(TestType.FIRST, "a", "xtf");
        ProcessingFile second = files.create(TestType.SECOND, "b", "xtf");

        assertEquals(first.filePath().getName(0), second.filePath().getName(0));
    }

    @Test
    void getSingleReturnsEmptyWhenTypeAbsent() {
        assertTrue(files.getSingle(TestType.FIRST).isEmpty());
    }

    @Test
    void getSingleReturnsEmptyWhenMultipleOfType() {
        files.create(TestType.FIRST, "a", "xtf");
        files.create(TestType.FIRST, "b", "xtf");

        assertTrue(files.getSingle(TestType.FIRST).isEmpty());
    }

    @Test
    void getAllReturnsEveryFileOfType() {
        ProcessingFile first = files.create(TestType.FIRST, "a", "xtf");
        ProcessingFile second = files.create(TestType.FIRST, "b", "xtf");

        assertEquals(List.of(first, second), files.getAll(TestType.FIRST));
    }

    @Test
    void getAllReturnsEmptyListWhenTypeAbsent() {
        assertTrue(files.getAll(TestType.FIRST).isEmpty());
    }

    @Test
    void getAllReturnsACopyTheCallerCannotModify() {
        ProcessingFile file = files.create(TestType.FIRST, "a", "xtf");

        assertThrows(UnsupportedOperationException.class, () -> files.getAll(TestType.FIRST).add(file));
        assertEquals(1, files.getAll(TestType.FIRST).size());
    }

    @Test
    void isEmptyReflectsRegisteredFiles() {
        assertTrue(files.isEmpty());

        files.create(TestType.FIRST, "a", "xtf");

        assertFalse(files.isEmpty());
    }

    @Test
    void sizeCountsAllFilesOfAnyType() {
        files.create(TestType.FIRST, "a", "xtf");
        files.create(TestType.FIRST, "b", "xtf");
        files.create(TestType.SECOND, "c", "xtf");

        assertEquals(3, files.size());
    }

    @Test
    void closeAllClosesEveryFileAndReportsSuccess() {
        InMemoryProcessingFile first = (InMemoryProcessingFile) files.create(TestType.FIRST, "a", "xtf");
        InMemoryProcessingFile second = (InMemoryProcessingFile) files.create(TestType.SECOND, "b", "xtf");

        boolean success = files.closeAll();

        assertTrue(success);
        assertTrue(first.isClosed());
        assertTrue(second.isClosed());
    }

    @Test
    void deleteAllClosesFilesAndClearsTheSet() {
        InMemoryProcessingFile file = (InMemoryProcessingFile) files.create(TestType.FIRST, "a", "xtf");

        files.deleteAll();

        assertTrue(file.isClosed());
        assertTrue(files.isEmpty());
        assertTrue(files.getSingle(TestType.FIRST).isEmpty());
    }
}
