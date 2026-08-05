package ch.geowerkstatt.ilitoolswrapper;

import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusException;
import io.grpc.stub.BlockingClientCall;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Base class for gRPC integration tests that start an {@link IlitoolsWrapperServer} with a single service and
 * exercise it over a real in-process channel.
 *
 * <p>Subclasses supply the port and output directory through the constructor and the service under test through
 * {@link #createService()}. The server starts once per test class ({@link TestInstance.Lifecycle#PER_CLASS}); the
 * shared {@link #channel} is available to every test method.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IlitoolsIntegrationTestBase {
    private final int port;
    private final Path outputDir;
    private IlitoolsWrapperServer server;
    protected ManagedChannel channel;

    protected IlitoolsIntegrationTestBase(int port, Path outputDir) {
        this.port = port;
        this.outputDir = outputDir;
    }

    /**
     * Creates the service under test. Called once before the server starts.
     */
    protected abstract BindableService createService() throws IOException;

    @BeforeAll
    protected final void startServer() throws IOException {
        server = new IlitoolsWrapperServer(port, createService());
        server.start();

        channel = ManagedChannelBuilder
                .forAddress("localhost", port)
                .usePlaintext()
                .build();

        IntegrationTestSupport.prepareOutputDirectory(outputDir);
    }

    @AfterAll
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    protected final void stopServer() throws InterruptedException {
        channel.shutdownNow();
        server.stop();
    }

    /**
     * Sends a file to the server as a file-start message followed by the resource content split into chunks.
     *
     * @param call             the streaming call to write to
     * @param fileStartRequest the request announcing the file and its type
     * @param chunkRequest     wraps a chunk of bytes into a request message
     * @param resourcePath     the classpath resource providing the file content
     * @param <ReqT>           the request message type of the streaming call
     */
    protected static <ReqT> void writeResourceFile(
            BlockingClientCall<ReqT, ?> call,
            ReqT fileStartRequest,
            Function<ByteString, ReqT> chunkRequest,
            String resourcePath) throws StatusException, InterruptedException, IOException {
        call.write(fileStartRequest);

        try (InputStream stream = IntegrationTestSupport.getResourceStream(resourcePath)) {
            // send in small chunks to test file streaming
            byte[] buffer = new byte[32 * 1024];
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) > 0) {
                call.write(chunkRequest.apply(ByteString.copyFrom(buffer, 0, bytesRead)));
            }
        }
    }
}
