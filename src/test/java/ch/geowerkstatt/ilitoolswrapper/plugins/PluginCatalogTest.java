package ch.geowerkstatt.ilitoolswrapper.plugins;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public final class PluginCatalogTest {
    @TempDir
    private Path root;

    @TempDir
    private Path sessionDirectory;

    @Test
    void availableIsEmptyWithoutConfiguredDirectory() {
        assertEquals(Set.of(), new PluginCatalog(null).available());
    }

    @Test
    void availableIsEmptyWhenTheDirectoryDoesNotExist() {
        assertEquals(Set.of(), new PluginCatalog(root.resolve("missing")).available());
    }

    @Test
    void availableOffersOnlyFoldersThatContainAJar() throws IOException {
        givenPlugin("functions", "functions.jar");
        Files.createDirectory(root.resolve("leftover"));
        Files.writeString(root.resolve("loose.jar"), "not a plugin folder");

        assertEquals(Set.of("functions"), new PluginCatalog(root).available());
    }

    @Test
    void availableIsReadOnEveryCallSoANewPluginNeedsNoRestart() throws IOException {
        PluginCatalog catalog = new PluginCatalog(root);
        assertEquals(Set.of(), catalog.available());

        givenPlugin("added-later", "added.jar");

        assertEquals(Set.of("added-later"), catalog.available(), "The catalog must not cache the set it offers.");
    }

    @Test
    void validateReturnsNothingForARequestWithoutPlugins() {
        assertEquals(Set.of(), new PluginCatalog(root).validate(List.of()));
    }

    @Test
    void validateAcceptsAnAvailableId() throws IOException {
        givenPlugin("functions", "functions.jar");

        assertEquals(Set.of("functions"), new PluginCatalog(root).validate(List.of("functions")));
    }

    @Test
    void validateRejectsAnUnknownId() throws IOException {
        givenPlugin("functions", "functions.jar");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new PluginCatalog(root).validate(List.of("nope")));
        String message = Objects.requireNonNull(exception.getMessage());
        assertTrue(message.contains("functions"), "The message should name what is available: " + message);
    }

    @Test
    void validateRejectsAPathInsteadOfAnId() throws IOException {
        givenPlugin("functions", "functions.jar");

        assertThrows(
                IllegalArgumentException.class,
                () -> new PluginCatalog(root).validate(List.of("../functions")));
    }

    @Test
    void validateRejectsABlankId() {
        assertThrows(IllegalArgumentException.class, () -> new PluginCatalog(root).validate(List.of(" ")));
    }

    @Test
    void validateRejectsARepeatedId() throws IOException {
        givenPlugin("functions", "functions.jar");

        assertThrows(
                IllegalArgumentException.class,
                () -> new PluginCatalog(root).validate(List.of("functions", "functions")));
    }

    @Test
    void materializeReturnsNothingWithoutASelection() throws IOException {
        assertEquals(Optional.empty(), new PluginCatalog(root).materialize(Set.of(), sessionDirectory));
    }

    @Test
    void materializeCopiesTheJarsOfEverySelectedPlugin() throws IOException {
        givenPlugin("first", "one.jar");
        givenPlugin("second", "two.jar", "three.jar");
        PluginCatalog catalog = new PluginCatalog(root);

        Path pluginDirectory = catalog.materialize(catalog.validate(List.of("first", "second")), sessionDirectory).orElseThrow();

        assertEquals(sessionDirectory.resolve(PluginCatalog.PLUGIN_SUBFOLDER), pluginDirectory);
        assertTrue(Files.exists(pluginDirectory.resolve("one.jar")));
        assertTrue(Files.exists(pluginDirectory.resolve("two.jar")));
        assertTrue(Files.exists(pluginDirectory.resolve("three.jar")));
    }

    @Test
    void materializeRejectsTwoPluginsCarryingTheSameJarName() throws IOException {
        givenPlugin("first", "same.jar");
        givenPlugin("second", "same.jar");
        PluginCatalog catalog = new PluginCatalog(root);
        Set<String> selection = catalog.validate(List.of("first", "second"));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.materialize(selection, sessionDirectory));
        String message = Objects.requireNonNull(exception.getMessage());
        assertTrue(message.contains("same.jar"), message);
    }

    private void givenPlugin(String id, String... jarNames) throws IOException {
        Path directory = Files.createDirectories(root.resolve(id));
        for (String jarName : jarNames) {
            Files.writeString(directory.resolve(jarName), "jar content of " + id);
        }
    }
}
