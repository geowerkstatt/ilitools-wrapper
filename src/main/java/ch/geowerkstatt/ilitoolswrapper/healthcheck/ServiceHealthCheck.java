package ch.geowerkstatt.ilitoolswrapper.healthcheck;

import io.grpc.health.v1.HealthCheckResponse;

/**
 * A service that reports its own health status.
 */
public interface ServiceHealthCheck {
    /**
     * Returns the name of the service.
     *
     * @return the service name
     */
    String getServiceName();

    /**
     * Returns the current health status of the service.
     *
     * @return the health status
     */
    HealthCheckResponse.ServingStatus getHealthStatus();
}
