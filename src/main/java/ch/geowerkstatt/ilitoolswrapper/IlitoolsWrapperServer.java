package ch.geowerkstatt.ilitoolswrapper;

import io.grpc.BindableService;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Manages startup and shutdown of a gRPC server.
 */
public final class IlitoolsWrapperServer {
    private static final Logger LOGGER = Logger.getLogger(IlitoolsWrapperServer.class.getName());

    private final Server server;

    /**
     * Creates a new server to listen on the specified port with the given services.
     */
    public IlitoolsWrapperServer(int port, BindableService... services) {
        ServerBuilder<?> builder = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .maxInboundMessageSize(100 * 1024 * 1024); // max 100 MB per message

        for (BindableService service : services) {
            builder.addService(service);
        }
        server = builder.build();
    }

    /**
     * Starts the server on the specified port.
     */
    public void start() throws IOException {
        server.start();
        LOGGER.info("gRPC Server started on port " + server.getPort());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("Shutting down gRPC server...");
            try {
                IlitoolsWrapperServer.this.stop();
            } catch (InterruptedException e) {
                server.shutdownNow();
                e.printStackTrace(System.err);
            }
            System.err.println("gRPC server shut down");
        }));
    }

    /**
     * Stops the server and waits for termination.
     */
    public void stop() throws InterruptedException {
        server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    public void blockUntilShutdown() throws InterruptedException {
        server.awaitTermination();
    }
}
