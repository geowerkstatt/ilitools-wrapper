package ch.geowerkstatt.ilitoolswrapper.runner;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class IlitoolsProcessRunner implements IlitoolsRunner {
    private record ToolVersion(Tool tool, String version) { }

    private static final Logger LOGGER = Logger.getLogger(IlitoolsProcessRunner.class.getName());

    // Both caches are legitimate because the tools are part of the image: the offered set cannot change at
    // runtime. This is the deliberate difference to PluginCatalog, which reads its (mounted) directory on
    // every request. gRPC serves calls concurrently, so the caches must be concurrent maps. Only a non-empty
    // scan is cached, so a temporarily unreadable home recovers without a restart.
    private final Map<ToolVersion, String> toolPaths = new ConcurrentHashMap<>();
    private final Map<Tool, Set<String>> versionsByTool = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> run(Tool tool, String toolVersion, List<String> args, @Nullable Timeout timeout) throws IOException {
        // Resolved and validated once, up front: buildCommand/findTool then only ever see an already-valid
        // version, the cache is keyed by that effective version (no duplicate entry for the default), and
        // every failure message below names the version that actually ran.
        String version = resolveVersion(tool, toolVersion);

        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(buildCommand(tool, version, args));
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process = processBuilder.start();
        CompletableFuture<Process> processFuture = process.onExit();
        if (timeout != null) {
            processFuture = processFuture.completeOnTimeout(process, timeout.duration(), timeout.unit());
        }
        return processFuture.thenCompose(p -> handleProcessResult(p, tool, version));
    }

    @Override
    public Set<String> availableVersions(Tool tool) {
        Set<String> cached = versionsByTool.get(tool);
        if (cached != null) {
            return cached;
        }

        Set<String> scanned = scanVersions(tool);
        if (!scanned.isEmpty()) {
            versionsByTool.put(tool, scanned);
            // Logged once, at first scan (e.g. the startup health check): each deployment then names its
            // offered versions in its own log without extra tooling.
            LOGGER.info(tool + " offers versions " + scanned + ".");
        }
        return scanned;
    }

    // Matching the scanned set is what turns the version into a path: a request value never builds a path
    // before it matched a real directory name (the services check the same set in onInfo, this is the
    // backstop for callers that do not). Resolving and validating here, before the cache, is also what keeps
    // the caller/default distinction available: buildCommand and findTool only ever see an already-valid version.
    private String resolveVersion(Tool tool, String toolVersion) {
        Set<String> available = availableVersions(tool);
        if (available.isEmpty()) {
            // An empty set means the home itself is missing or wrong, so name that cause instead of the
            // versions; requireEnvironmentVariable restores the precise message for an unset variable.
            throw new IllegalStateException(tool + " offers no versions below " + requireEnvironmentVariable(tool + "_HOME") + ".");
        }

        String version = toolVersion.isEmpty() ? requireEnvironmentVariable(tool + "_VERSION") : toolVersion;
        if (!available.contains(version)) {
            // A non-empty (caller-requested) version that is not offered is the caller's fault, which the
            // services map to INVALID_ARGUMENT; an unavailable default is a deployment fault instead.
            String message = tool + " version " + version + " is not available, expected one of " + available + ".";
            throw toolVersion.isEmpty() ? new IllegalStateException(message) : new IllegalArgumentException(message);
        }
        return version;
    }

    private CompletionStage<Void> handleProcessResult(Process process, Tool tool, String version) {
        if (process.isAlive()) {
            process.destroyForcibly();
            return CompletableFuture.failedStage(new TimeoutException("Tool " + tool + " " + version + " timed out."));
        }

        if (process.exitValue() != 0) {
            String errorMessage = "Tool " + tool + " " + version + " exited with code " + process.exitValue();
            return CompletableFuture.failedStage(new RuntimeException(errorMessage));
        }

        return CompletableFuture.completedStage(null);
    }

    private List<String> buildCommand(Tool tool, String version, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(toolPaths.computeIfAbsent(new ToolVersion(tool, version), this::findTool));
        command.addAll(args);
        return command;
    }

    // A version is a whole directory below {TOOL}_HOME, because the manifest of a distribution pins its
    // exact libs/ dependencies, so two versions cannot share files.
    private Set<String> scanVersions(Tool tool) {
        String toolHome = System.getenv(tool + "_HOME");
        if (toolHome == null || toolHome.isEmpty()) {
            return Set.of();
        }

        Path home;
        try {
            home = Path.of(toolHome);
        } catch (InvalidPathException e) {
            // Same fail-closed outcome as an unreadable home: the tool offers nothing, and the health check
            // reports the unresolvable default.
            LOGGER.warning("The home of " + tool + " is not a valid path: " + toolHome);
            return Set.of();
        }
        if (!Files.isDirectory(home)) {
            return Set.of();
        }

        // A directory only counts as a version if it holds the matching jar, so a leftover folder is not
        // offered rather than failing later. Sorted, so rejection messages stay deterministic.
        try (Stream<Path> candidates = Files.list(home)) {
            TreeSet<String> versions = candidates.filter(Files::isDirectory)
                    .map(directory -> directory.getFileName().toString())
                    .filter(version -> Files.isRegularFile(home.resolve(version).resolve(jarName(tool, version))))
                    .collect(Collectors.toCollection(TreeSet::new));
            return Collections.unmodifiableSet(versions);
        } catch (IOException e) {
            // A home that cannot be listed offers nothing; requests naming a version then fail with the
            // regular rejection and the health check reports NOT_SERVING for the unresolvable default.
            LOGGER.log(Level.WARNING, "Failed to scan " + tool + " versions in " + home + ".", e);
            return Set.of();
        }
    }

    // Precondition: must only be called with a version that resolveVersion already matched against the offered set.
    private String findTool(ToolVersion toolVersion) {
        Tool tool = toolVersion.tool();
        String version = toolVersion.version();

        String toolHome = requireEnvironmentVariable(tool + "_HOME");
        Path toolPath = Path.of(toolHome, version, jarName(tool, version)).toAbsolutePath();
        if (!Files.isRegularFile(toolPath)) {
            throw new IllegalStateException(tool + " not found at: " + toolPath);
        }

        return toolPath.toString();
    }

    private static String jarName(Tool tool, String version) {
        return tool.name().toLowerCase(Locale.ROOT) + "-" + version + ".jar";
    }

    private String requireEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Environment variable \"" + name + "\" is not set.");
        }
        return value;
    }
}
