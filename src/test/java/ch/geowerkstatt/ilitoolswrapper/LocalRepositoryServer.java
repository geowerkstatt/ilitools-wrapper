package ch.geowerkstatt.ilitoolswrapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Serves the model repository fixture from {@code repositories/repository/} over HTTP on localhost, so the
 * integration tests can exercise the URL route of {@code modelDirs} without reaching a remote repository.
 *
 * <p>The server binds an ephemeral port, which also keeps the INTERLIS model cache from reusing entries of an
 * earlier run. Requested paths are recorded so a test can assert which repository files the tool actually read.
 */
public final class LocalRepositoryServer implements AutoCloseable {
    private static final String RESOURCE_DIRECTORY = "repositories/repository/";

    private final HttpServer server;
    private final ConcurrentLinkedQueue<String> requestedPaths = new ConcurrentLinkedQueue<>();
    private final Set<String> servedFiles;

    private LocalRepositoryServer(HttpServer server, Set<String> servedFiles) {
        this.server = server;
        this.servedFiles = servedFiles;
    }

    public static LocalRepositoryServer start() throws IOException {
        return start(Set.of("ilidata.xml", "test_profile.toml"));
    }

    public static LocalRepositoryServer startWithModels() throws IOException {
        return start(Set.of("ilidata.xml", "test_profile.toml", "ilimodels.xml", "model.ili"));
    }

    public static LocalRepositoryServer startWithReferenceData() throws IOException {
        return start(Set.of("ilidata.xml", "refmapping.xtf", "refdata_449.xtf"));
    }

    private static LocalRepositoryServer start(Set<String> servedFiles) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        LocalRepositoryServer repository = new LocalRepositoryServer(server, servedFiles);
        server.createContext("/", repository::handle);
        server.start();
        return repository;
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/";
    }

    public List<String> requestedPaths() {
        return List.copyOf(requestedPaths);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requestedPaths.add(path);

        // Only the known fixture files are served, which also rules out any path traversal.
        String fileName = path.startsWith("/") ? path.substring(1) : path;
        if (!servedFiles.contains(fileName)) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        byte[] content = IntegrationTestSupport.getResourceBytes(RESOURCE_DIRECTORY + fileName);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(content);
        }
    }
}
