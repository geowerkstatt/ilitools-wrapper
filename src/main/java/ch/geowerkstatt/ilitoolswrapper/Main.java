package ch.geowerkstatt.ilitoolswrapper;

import io.grpc.protobuf.services.ProtoReflectionServiceV1;

import java.io.IOException;

public final class Main {
    private Main() { }

    /**
     * Application entry point.
     */
    static void main() throws InterruptedException, IOException {
        final IlitoolsWrapperServer server = new IlitoolsWrapperServer(
                getPort(),
                ProtoReflectionServiceV1.newInstance()
        );
        server.start();
        server.blockUntilShutdown();
    }

    private static int getPort() {
        final int defaultPort = 5555;
        final String portEnv = System.getenv("GRPC_PORT");
        return portEnv != null && !portEnv.isEmpty() ? Integer.parseInt(portEnv) : defaultPort;
    }
}
