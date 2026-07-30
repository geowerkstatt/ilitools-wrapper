package ch.geowerkstatt.ilitoolswrapper;

import ch.geowerkstatt.ilitoolswrapper.files.FileManager;
import ch.geowerkstatt.ilitoolswrapper.files.FilesystemFileManager;
import ch.geowerkstatt.ilitoolswrapper.healthcheck.ServiceHealthCheckManager;
import ch.geowerkstatt.ilitoolswrapper.ili2gpkg.Ili2gpkgService;
import ch.geowerkstatt.ilitoolswrapper.ilivalidator.IlivalidatorService;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsProcessRunner;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunner;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;

import java.io.IOException;

public final class Main {
    private Main() { }

    /**
     * Application entry point.
     */
    static void main() throws InterruptedException, IOException {
        final FileManager fileManager = new FilesystemFileManager();
        final IlitoolsRunner ilitoolsRunner = new IlitoolsProcessRunner();
        final Ili2gpkgService ili2gpkgService = new Ili2gpkgService(fileManager, ilitoolsRunner);
        final IlivalidatorService ilivalidatorService = new IlivalidatorService(fileManager, ilitoolsRunner);

        final ServiceHealthCheckManager serviceHealthCheckManager = new ServiceHealthCheckManager(
                ili2gpkgService,
                ilivalidatorService
        );

        final IlitoolsWrapperServer server = new IlitoolsWrapperServer(
                getPort(),
                ProtoReflectionServiceV1.newInstance(),
                serviceHealthCheckManager.getHealthService(),
                ili2gpkgService,
                ilivalidatorService
        );
        server.closeOnShutdown(serviceHealthCheckManager);
        server.start();
        server.blockUntilShutdown();
    }

    private static int getPort() {
        final int defaultPort = 5555;
        final String portEnv = System.getenv("GRPC_PORT");
        return portEnv != null && !portEnv.isEmpty() ? Integer.parseInt(portEnv) : defaultPort;
    }
}
