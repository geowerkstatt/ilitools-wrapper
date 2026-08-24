package ch.geowerkstatt.ilitoolswrapper.plugins;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The ilivalidator plugins this deployment offers, and the materialization of a selected subset for a single
 * request.
 *
 * <p>The catalog is a directory with one subfolder per plugin; the subfolder name is the id a request selects,
 * and the jars inside it are what the tool loads. Whether that directory is baked into the image or mounted
 * into it is a deployment decision, which is why the set is resolved from the filesystem on every request
 * instead of being cached: a newly added plugin is then selectable without restarting the service. This is a
 * deliberate difference from the tool resolution in {@code IlitoolsProcessRunner}, which caches per tool
 * because the tools are part of the image.
 *
 * <p>An id from a request is never used to build a path before it was matched against {@link #available()}.
 * That match is what takes the place of a closed set in the request contract.
 */
public final class PluginCatalog {
    /**
     * Name of the session subfolder the jars of the selected plugins are copied into, which is what
     * {@code --plugins} is pointed at.
     */
    public static final String PLUGIN_SUBFOLDER = "plugins";

    private static final String PLUGINS_DIR_ENV = "ILIVALIDATOR_PLUGINS_DIR";
    private static final String JAR_SUFFIX = ".jar";

    private final @Nullable Path root;

    /**
     * Creates a catalog over the given directory.
     *
     * @param root the directory holding one subfolder per plugin, or {@code null} when this deployment offers none
     */
    public PluginCatalog(@Nullable Path root) {
        this.root = root;
    }

    /**
     * Reads the catalog location from the environment variable {@code ILIVALIDATOR_PLUGINS_DIR}. An unset or
     * empty value means this deployment offers no plugins, so every request that names one is rejected.
     *
     * @return a catalog over the configured directory, or an empty catalog
     */
    public static PluginCatalog fromEnvironment() {
        String configured = System.getenv(PLUGINS_DIR_ENV);
        return new PluginCatalog(configured == null || configured.isBlank() ? null : Path.of(configured));
    }

    /**
     * The ids a request may select, read from the filesystem on every call. A subfolder counts as a plugin only
     * if it contains at least one jar, so a leftover empty folder is not offered rather than failing later.
     *
     * @return the available plugin ids, sorted, empty when no directory is configured or it holds no plugin
     */
    public Set<String> available() {
        if (root == null || !Files.isDirectory(root)) {
            return Set.of();
        }

        Set<String> ids = new TreeSet<>();
        try (Stream<Path> candidates = Files.list(root)) {
            candidates.filter(Files::isDirectory)
                    .filter(PluginCatalog::containsJar)
                    .forEach(directory -> ids.add(directory.getFileName().toString()));
        } catch (IOException e) {
            // A catalog that cannot be listed offers nothing. Requests naming a plugin then fail with the
            // regular "unknown plugin" rejection, which is the same fail-closed outcome as an empty catalog.
            return Set.of();
        }
        return ids;
    }

    /**
     * Validates the plugin ids of a request against {@link #available()}.
     *
     * @param requestedIds the ids the request selected, in request order
     * @return the validated ids without duplicates, empty when the request selected none
     * @throws IllegalArgumentException if an id is blank, repeated, or not offered by this deployment
     */
    public Set<String> validate(List<String> requestedIds) {
        Set<String> validated = new LinkedHashSet<>();
        if (requestedIds.isEmpty()) {
            return validated;
        }

        Set<String> availableIds = available();
        for (String requestedId : requestedIds) {
            if (requestedId.isBlank()) {
                throw new IllegalArgumentException("Plugin id must not be blank.");
            }
            if (!availableIds.contains(requestedId)) {
                throw new IllegalArgumentException("Plugin \"" + requestedId + "\" is not available, expected one of " + availableIds + ".");
            }
            if (!validated.add(requestedId)) {
                throw new IllegalArgumentException("Plugin \"" + requestedId + "\" was requested more than once.");
            }
        }
        return validated;
    }

    /**
     * Copies the jars of the selected plugins into the plugin subfolder of the session directory. The tool takes a
     * single directory, so several plugins are merged into one; the jars keep their name, because the tool
     * identifies a function by the class that declares it and not by the file it came from.
     *
     * @param validatedIds ids that {@link #validate} accepted for this request
     * @param sessionDirectory the session directory of the request, which the subfolder is created in
     * @return the directory to pass to {@code --plugins}, empty when no plugin was selected
     * @throws IllegalArgumentException if two selected plugins carry a jar of the same name
     * @throws IOException if a plugin directory cannot be read or a jar cannot be copied
     */
    public Optional<Path> materialize(Set<String> validatedIds, Path sessionDirectory) throws IOException {
        if (validatedIds.isEmpty()) {
            return Optional.empty();
        }
        if (root == null) {
            throw new IOException("No plugin directory is configured, so the selected plugins cannot be materialized.");
        }

        Path target = sessionDirectory.resolve(PLUGIN_SUBFOLDER);
        Files.createDirectories(target);

        for (String id : validatedIds) {
            for (Path jar : jarsOf(root.resolve(id))) {
                try {
                    Files.copy(jar, target.resolve(jar.getFileName().toString()));
                } catch (FileAlreadyExistsException e) {
                    throw new IllegalArgumentException("The selected plugins carry more than one jar named \"" + jar.getFileName() + "\".", e);
                }
            }
        }
        return Optional.of(target);
    }

    private static List<Path> jarsOf(Path pluginDirectory) throws IOException {
        try (Stream<Path> entries = Files.list(pluginDirectory)) {
            List<Path> jars = new ArrayList<>(entries.filter(PluginCatalog::isJar).toList());
            if (jars.isEmpty()) {
                // available() only offers directories with a jar, so this means the catalog changed between the
                // validation and the materialization of the same request.
                throw new IOException("Plugin directory " + pluginDirectory + " no longer contains a jar.");
            }
            return jars;
        }
    }

    private static boolean containsJar(Path directory) {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.anyMatch(PluginCatalog::isJar);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isJar(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(JAR_SUFFIX);
    }
}
