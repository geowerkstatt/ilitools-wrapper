package ch.geowerkstatt.ilitoolswrapper.runner;

import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

public final class IlitoolsProcessRunner implements IlitoolsRunner {
    private final Map<Tool, String> toolPaths = new HashMap<>();

    @Override
    public CompletableFuture<Void> run(Tool tool, List<String> args, @Nullable ProcessingFile logFile, @Nullable Timeout timeout) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(buildCommand(tool, args));

        ProcessBuilder.Redirect logRedirect = logFile != null
                ? ProcessBuilder.Redirect.to(logFile.filePath().toFile())
                : ProcessBuilder.Redirect.DISCARD;
        processBuilder.redirectOutput(logRedirect);
        processBuilder.redirectError(logRedirect);

        Process process = processBuilder.start();
        CompletableFuture<Process> processFuture = process.onExit();
        if (timeout != null) {
            processFuture = processFuture.completeOnTimeout(process, timeout.duration(), timeout.unit());
        }
        return processFuture.thenCompose(p -> handleProcessResult(p, tool));
    }

    private CompletionStage<Void> handleProcessResult(Process process, Tool tool) {
        if (process.isAlive()) {
            process.destroyForcibly();
            return CompletableFuture.failedStage(new TimeoutException("Tool " + tool + " timed out."));
        }

        if (process.exitValue() != 0) {
            String errorMessage = "Tool " + tool + " exited with code " + process.exitValue();
            return CompletableFuture.failedStage(new RuntimeException(errorMessage));
        }

        return CompletableFuture.completedStage(null);
    }

    private List<String> buildCommand(Tool tool, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("java");
        command.add("-jar");
        command.add(toolPaths.computeIfAbsent(tool, this::findTool));
        command.addAll(args);
        return command;
    }

    private String findTool(Tool tool) {
        String toolHome = requireEnvironmentVariable(tool + "_HOME");
        String toolVersion = requireEnvironmentVariable(tool + "_VERSION");
        String fileName = tool.name().toLowerCase(Locale.ROOT) + "-" + toolVersion + ".jar";

        Path toolPath = Path.of(toolHome, fileName).toAbsolutePath();
        if (!Files.isRegularFile(toolPath)) {
            throw new IllegalStateException(tool + " not found at: " + toolPath);
        }

        return toolPath.toString();
    }

    private String requireEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Environment variable \"" + name + "\" is not set.");
        }
        return value;
    }
}
