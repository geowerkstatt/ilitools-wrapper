package ch.geowerkstatt.ilitoolswrapper.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class IlitoolsProcessRunner implements IlitoolsRunner {
    private final Map<Tool, String> toolPaths = new HashMap<>();

    @Override
    public CompletableFuture<Void> run(Tool tool, List<String> args) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(buildCommand(tool, args));

        Process process = processBuilder.start();
        return process.onExit()
                .thenCompose(p -> p.exitValue() == 0 ? CompletableFuture.completedStage(null) : CompletableFuture.failedStage(new RuntimeException(tool + " exited with code " + p.exitValue())));
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
