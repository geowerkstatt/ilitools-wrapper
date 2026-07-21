package ch.geowerkstatt.ilitoolswrapper.healthcheck;

import io.grpc.BindableService;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.HealthStatusManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A manager that periodically checks the health status of multiple services and provides a gRPC health service.
 */
public final class ServiceHealthCheckManager implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(ServiceHealthCheckManager.class.getName());
    private static final ThreadFactory THREAD_FACTORY = Thread.ofPlatform()
            .name("health-check-", 0)
            .daemon()
            .factory();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(THREAD_FACTORY);
    private final HealthStatusManager healthStatusManager = new HealthStatusManager();
    private final ServiceHealthCheck[] services;
    private volatile boolean closed;

    /**
     * Creates a new ServiceHealthCheckManager that periodically checks the health of the given services.
     *
     * @param services the services to monitor
     */
    public ServiceHealthCheckManager(ServiceHealthCheck... services) {
        this.services = services;
        var _ = scheduler.scheduleWithFixedDelay(this::checkHealth, 2, 5 * 60, TimeUnit.SECONDS);
    }

    /**
     * Returns the service using the grpc.health.v1.Health API that can be registered with a gRPC server.
     *
     * @return the gRPC health service
     */
    public BindableService getHealthService() {
        return healthStatusManager.getHealthService();
    }

    /**
     * Closes the health check manager, stopping the periodic health checks and entering a terminal state.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            healthStatusManager.enterTerminalState();
            scheduler.shutdownNow();
        }
    }

    void checkHealth() {
        HealthCheckResponse.ServingStatus combinedStatus = HealthCheckResponse.ServingStatus.SERVING;

        for (ServiceHealthCheck service : services) {
            if (closed) {
                return;
            }

            HealthCheckResponse.ServingStatus status;
            try {
                status = service.getHealthStatus();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Health check for service " + service.getServiceName() + " failed", e);
                status = HealthCheckResponse.ServingStatus.NOT_SERVING;
            }
            healthStatusManager.setStatus(service.getServiceName(), status);

            if (status == HealthCheckResponse.ServingStatus.NOT_SERVING) {
                combinedStatus = HealthCheckResponse.ServingStatus.NOT_SERVING;
            }
        }

        healthStatusManager.setStatus(HealthStatusManager.SERVICE_NAME_ALL_SERVICES, combinedStatus);
    }
}
