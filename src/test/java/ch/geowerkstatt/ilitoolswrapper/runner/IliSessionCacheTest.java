package ch.geowerkstatt.ilitoolswrapper.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IliSessionCacheTest {
    @TempDir
    Path sharedTempDir;
    @TempDir
    Path sessionTempParent;

    @Test
    public void testSetupSessionCacheDir() throws Exception {
        Path sessionCacheDir;
        try (IliSessionCache sessionCache = new IliSessionCache(null, null)) {
            sessionCacheDir = sessionCache.setupSessionCacheDir();
            assertNotNull(sessionCacheDir);
            assertTrue(Files.exists(sessionCacheDir));
        }

        assertFalse(Files.exists(sessionCacheDir), "Closing the IliSessionCache should delete the session cache directory");
    }

    @Test
    public void testSetupSessionCacheDirWithSharedCache() throws Exception {
        Files.writeString(sharedTempDir.resolve("testfile.txt"), "test file content");
        Path sessionCacheDir;
        try (IliSessionCache sessionCache = new IliSessionCache(sharedTempDir, sessionTempParent)) {
            sessionCacheDir = sessionCache.setupSessionCacheDir();
            assertNotNull(sessionCacheDir);
            assertTrue(Files.exists(sessionCacheDir.resolve("testfile.txt")), "Shared cache file should be copied to session cache");
            assertEquals("test file content", Files.readString(sessionCacheDir.resolve("testfile.txt")));
        }

        assertFalse(Files.exists(sessionCacheDir), "Closing the IliSessionCache should delete the session cache directory");
        assertTrue(Files.exists(sharedTempDir.resolve("testfile.txt")), "Shared cache file should still exist after closing the IliSessionCache");
    }

    @Test
    public void testWriteToSharedCache() throws Exception {
        final String folderName = "testfolder";
        final String fileName = "newfile.txt";
        Path sessionCacheDir;
        try (IliSessionCache sessionCache = new IliSessionCache(sharedTempDir, sessionTempParent)) {
            sessionCacheDir = sessionCache.setupSessionCacheDir();
            Files.createDirectories(sessionCacheDir.resolve(folderName));
            Files.writeString(sessionCacheDir.resolve(folderName, fileName), "new file content");
            sessionCache.writeToSharedCache();
        }

        assertTrue(Files.exists(sharedTempDir.resolve(folderName, fileName)), "New file should be written back to shared cache");
        assertEquals("new file content", Files.readString(sharedTempDir.resolve(folderName, fileName)));
    }

    @Test
    public void testWriteToSharedCacheOverwritesExisting() throws Exception {
        Files.writeString(sharedTempDir.resolve("updated.txt"), "old file content");

        Path sessionCacheDir;
        try (IliSessionCache sessionCache = new IliSessionCache(sharedTempDir, sessionTempParent)) {
            sessionCacheDir = sessionCache.setupSessionCacheDir();
            Files.writeString(sessionCacheDir.resolve("updated.txt"), "new file content");
            sessionCache.writeToSharedCache();
        }

        assertEquals("new file content", Files.readString(sharedTempDir.resolve("updated.txt")));
    }

    @Test
    public void testWriteToSharedCacheIgnoresJdbc() throws Exception {
        final String folderName = "jdbc&003asqlite&003aexample.gpkg";
        Path sessionCacheDir;
        try (IliSessionCache sessionCache = new IliSessionCache(sharedTempDir, sessionTempParent)) {
            sessionCacheDir = sessionCache.setupSessionCacheDir();

            Path folderPath = sessionCacheDir.resolve(folderName);
            Files.createDirectories(folderPath);
            Files.writeString(folderPath.resolve("file.txt"), "new file content");
            sessionCache.writeToSharedCache();
        }

        assertFalse(Files.exists(sharedTempDir.resolve(folderName)), "Folder starting with 'jdbc&003a' should not be written back to shared cache");
    }
}
