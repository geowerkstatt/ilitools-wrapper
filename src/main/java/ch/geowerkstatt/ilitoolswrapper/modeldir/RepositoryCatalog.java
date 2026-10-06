package ch.geowerkstatt.ilitoolswrapper.modeldir;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The model repositories this deployment offers from a directory, so that data shared by every caller, such as
 * official models and federal reference data, need neither a web server nor a download per run.
 *
 * <p>The directory holds one subfolder per repository. Its name is the id a request addresses in the model dirs as
 * {@code %REPOSITORIES/<id>}; the convention {@code <name>@<version>} lets a pipeline pin the state it was written
 * against. Like the plugin catalog, the set is read from the filesystem on every request, so a repository added to
 * a mounted directory is available without a restart.
 *
 * <p>An id from a request is never used to build a path before it was matched against {@link #available()}, which
 * lists direct subfolders only. That match is what keeps a request from reaching any other directory.
 */
public final class RepositoryCatalog {
    /**
     * The model dir placeholder that addresses an offered repository, followed by {@code /<id>}.
     */
    public static final String PLACEHOLDER = "%REPOSITORIES";

    private static final String REPOSITORIES_DIR_ENV = "ILITOOLS_REPOSITORIES_DIR";

    private final @Nullable Path root;

    /**
     * Creates a catalog over the given directory.
     *
     * @param root the directory holding one subfolder per repository, or {@code null} when this deployment offers none
     */
    public RepositoryCatalog(@Nullable Path root) {
        this.root = root;
    }

    /**
     * Reads the catalog location from the environment variable {@code ILITOOLS_REPOSITORIES_DIR}. An unset or empty
     * value means this deployment offers no repositories, so every request that names one is rejected.
     *
     * @return a catalog over the configured directory, or an empty catalog
     */
    public static RepositoryCatalog fromEnvironment() {
        String configured = System.getenv(REPOSITORIES_DIR_ENV);
        return new RepositoryCatalog(configured == null || configured.isBlank() ? null : Path.of(configured));
    }

    /**
     * The ids a request may address, read from the filesystem on every call.
     *
     * @return the available repository ids, sorted, empty when no directory is configured or it holds no subfolder
     */
    public Set<String> available() {
        if (root == null || !Files.isDirectory(root)) {
            return Set.of();
        }

        try (Stream<Path> candidates = Files.list(root)) {
            // Sorted, so that the rejection of an unknown id lists the offered ones in a stable order.
            return candidates.filter(Files::isDirectory)
                    .map(directory -> directory.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            // A directory that cannot be listed offers nothing, the same fail-closed outcome as an empty one.
            return Set.of();
        }
    }

    /**
     * Resolves a model dir entry of the form {@code %REPOSITORIES/<id>} to the directory of the offered repository.
     * The tool receives that directory, because it does not know the placeholder.
     *
     * @param modelDir the entry of the request, starting with {@link #PLACEHOLDER}
     * @return the absolute path of the repository directory
     * @throws IllegalArgumentException if the entry does not name exactly one offered repository
     */
    public String resolve(String modelDir) {
        String id = modelDir.startsWith(PLACEHOLDER + "/") ? modelDir.substring(PLACEHOLDER.length() + 1) : "";
        Set<String> availableIds = available();
        if (root == null || !availableIds.contains(id)) {
            throw new IllegalArgumentException("Model dir entry \"" + modelDir + "\" names no offered repository, expected "
                    + PLACEHOLDER + "/<id> with one of " + availableIds + ".");
        }
        return root.resolve(id).toAbsolutePath().toString();
    }
}
