package ch.geowerkstatt.ilitoolswrapper.healthcheck;

import ch.geowerkstatt.ilitoolswrapper.RecordingStreamObserver;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.protobuf.services.HealthStatusManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import static org.junit.jupiter.api.Assertions.*;

public final class ServiceHealthCheckManagerTest {
    @Test
    void initialCheckPollsEveryService() {
        RecordingServiceHealthCheck first = new RecordingServiceHealthCheck("first", ServingStatus.SERVING);
        RecordingServiceHealthCheck second = new RecordingServiceHealthCheck("second", ServingStatus.NOT_SERVING);

        try (ServiceHealthCheckManager manager = new ServiceHealthCheckManager(first, second)) {
            manager.checkHealth();

            assertTrue(first.statusChecks() >= 1, "First service health status should be queried.");
            assertTrue(second.statusChecks() >= 1, "Second service health status should be queried.");
        }
    }

    public static Stream<Arguments> checkPropagatesStateProvider() {
        return Stream.of(
                Arguments.of(ServingStatus.SERVING, ServingStatus.SERVING, ServingStatus.SERVING),
                Arguments.of(ServingStatus.NOT_SERVING, ServingStatus.SERVING, ServingStatus.NOT_SERVING),
                Arguments.of(ServingStatus.NOT_SERVING, ServingStatus.NOT_SERVING, ServingStatus.SERVING),
                Arguments.of(ServingStatus.NOT_SERVING, ServingStatus.NOT_SERVING, ServingStatus.NOT_SERVING)
        );
    }

    @ParameterizedTest
    @MethodSource("checkPropagatesStateProvider")
    void checkPropagatesState(ServingStatus combined, ServingStatus firstStatus, ServingStatus secondStatus) {
        RecordingServiceHealthCheck first = new RecordingServiceHealthCheck("first", firstStatus);
        RecordingServiceHealthCheck second = new RecordingServiceHealthCheck("second", secondStatus);

        try (ServiceHealthCheckManager manager = new ServiceHealthCheckManager(first, second)) {
            manager.checkHealth();

            assertTrue(first.statusChecks() >= 1, "First service health status should be queried.");
            assertTrue(second.statusChecks() >= 1, "Second service health status should be queried.");

            assertServiceState(manager, "first", firstStatus);
            assertServiceState(manager, "second", secondStatus);
            assertServiceState(manager, HealthStatusManager.SERVICE_NAME_ALL_SERVICES, combined);
        }
    }

    @Test
    void returnsNotServingAfterClose() {
        RecordingServiceHealthCheck service = new RecordingServiceHealthCheck("service", ServingStatus.SERVING);
        ServiceHealthCheckManager manager = new ServiceHealthCheckManager(service);

        manager.checkHealth();
        assertServiceState(manager, "service", ServingStatus.SERVING);
        assertServiceState(manager, HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.SERVING);

        manager.close();
        assertServiceState(manager, "service", ServingStatus.NOT_SERVING);
        assertServiceState(manager, HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.NOT_SERVING);
    }

    @Test
    void closeCanBeCalledRepeatedly() {
        RecordingServiceHealthCheck service = new RecordingServiceHealthCheck("service", ServingStatus.SERVING);
        ServiceHealthCheckManager manager = new ServiceHealthCheckManager(service);

        manager.checkHealth();

        assertDoesNotThrow(manager::close);
        assertDoesNotThrow(manager::close);
    }

    @Test
    void managerWithoutServicesDoesNotFail() {
        assertDoesNotThrow(() -> {
            try (ServiceHealthCheckManager manager = new ServiceHealthCheckManager()) {
                assertNotNull(manager);
                manager.checkHealth();
                assertServiceState(manager, HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.SERVING);
            }
        });
    }

    private void assertServiceState(ServiceHealthCheckManager manager, String name, ServingStatus expectedStatus) {
        HealthGrpc.HealthImplBase healthService = assertInstanceOf(HealthGrpc.HealthImplBase.class, manager.getHealthService());

        RecordingStreamObserver<HealthCheckResponse> responseObserver = new RecordingStreamObserver<>();
        HealthCheckRequest request = HealthCheckRequest.newBuilder().setService(name).build();
        healthService.check(request, responseObserver);

        assertTrue(responseObserver.isCompleted());
        assertEquals(1, responseObserver.values().size());
        assertEquals(expectedStatus, responseObserver.values().getFirst().getStatus());
    }

    private static final class RecordingServiceHealthCheck implements ServiceHealthCheck {
        private final String name;
        private final ServingStatus status;
        private int statusChecks;

        RecordingServiceHealthCheck(String name, ServingStatus status) {
            this.name = name;
            this.status = status;
        }

        int statusChecks() {
            return statusChecks;
        }

        @Override
        public String getServiceName() {
            return name;
        }

        @Override
        public ServingStatus getHealthStatus() {
            statusChecks++;
            return status;
        }
    }
}
