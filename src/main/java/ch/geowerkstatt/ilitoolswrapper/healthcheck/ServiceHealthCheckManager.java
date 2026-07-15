package ch.geowerkstatt.ilitoolswrapper.healthcheck;

import io.grpc.BindableService;
import io.grpc.protobuf.services.HealthStatusManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A manager that periodically checks the health status of multiple services and provides a gRPC health service.
 */
public final class ServiceHealthCheckManager implements AutoCloseable {
    private static final ThreadFactory THREAD_FACTORY = Thread.ofVirtual()
            .name("health-check-", 0)
            .factory();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, THREAD_FACTORY);
    private final HealthStatusManager healthStatusManager = new HealthStatusManager();
    private final ServiceHealthCheck[] services;

    /**
     * Creates a new ServiceHealthCheckManager that periodically checks the health of the given services.
     *
     * @param services the services to monitor
     */
    public ServiceHealthCheckManager(ServiceHealthCheck... services) {
        this.services = services;
        scheduler.scheduleWithFixedDelay(this::checkHealth, 0, 5, TimeUnit.MINUTES);
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
        healthStatusManager.enterTerminalState();
        scheduler.shutdown();
    }

    private void checkHealth() {
        for (ServiceHealthCheck service : services) {
            healthStatusManager.setStatus(service.getServiceName(), service.getHealthStatus());
        }
    }
}
