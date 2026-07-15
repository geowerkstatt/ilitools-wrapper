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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import static org.junit.jupiter.api.Assertions.*;

public final class ServiceHealthCheckManagerTest {
    private static final long AWAIT_TIMEOUT_SECONDS = 10;

    @Test
    void initialCheckPollsEveryService() throws InterruptedException {
        RecordingServiceHealthCheck first = new RecordingServiceHealthCheck("first", ServingStatus.SERVING);
        RecordingServiceHealthCheck second = new RecordingServiceHealthCheck("second", ServingStatus.NOT_SERVING);

        try (ServiceHealthCheckManager _ = new ServiceHealthCheckManager(first, second)) {
            assertTrue(first.awaitPoll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "First service should be polled.");
            assertTrue(second.awaitPoll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Second service should be polled.");

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
    void checkPropagatesState(ServingStatus combined, ServingStatus firstStatus, ServingStatus secondStatus) throws InterruptedException {
        RecordingServiceHealthCheck first = new RecordingServiceHealthCheck("first", firstStatus);
        RecordingServiceHealthCheck second = new RecordingServiceHealthCheck("second", secondStatus);

        try (ServiceHealthCheckManager manager = new ServiceHealthCheckManager(first, second)) {
            assertTrue(first.awaitPoll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "First service should be polled.");
            assertTrue(second.awaitPoll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Second service should be polled.");

            assertTrue(first.statusChecks() >= 1, "First service health status should be queried.");
            assertTrue(second.statusChecks() >= 1, "Second service health status should be queried.");

            assertServiceState(manager, "first", firstStatus);
            assertServiceState(manager, "second", secondStatus);
            assertServiceState(manager, HealthStatusManager.SERVICE_NAME_ALL_SERVICES, combined);
        }
    }

    @Test
    void returnsNotServingAfterClose() throws InterruptedException {
        RecordingServiceHealthCheck service = new RecordingServiceHealthCheck("service", ServingStatus.SERVING);
        ServiceHealthCheckManager manager = new ServiceHealthCheckManager(service);

        assertTrue(service.awaitPoll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Service should be polled before closing.");
        assertServiceState(manager, "service", ServingStatus.SERVING);
        assertServiceState(manager, HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.SERVING);

        manager.close();

        assertServiceState(manager, "service", ServingStatus.NOT_SERVING);
        assertServiceState(manager, HealthStatusManager.SERVICE_NAME_ALL_SERVICES, ServingStatus.NOT_SERVING);
    }

    @Test
    void closeCanBeCalledRepeatedly() throws InterruptedException {
        RecordingServiceHealthCheck service = new RecordingServiceHealthCheck("service", ServingStatus.SERVING);
        ServiceHealthCheckManager manager = new ServiceHealthCheckManager(service);

        assertTrue(service.awaitPoll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Service should be polled before closing.");

        assertDoesNotThrow(manager::close);
        assertDoesNotThrow(manager::close);
    }

    @Test
    void managerWithoutServicesDoesNotFail() {
        assertDoesNotThrow(() -> {
            try (ServiceHealthCheckManager manager = new ServiceHealthCheckManager()) {
                assertNotNull(manager);
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
        private final AtomicInteger statusChecks = new AtomicInteger();
        private final CountDownLatch polled = new CountDownLatch(1);

        RecordingServiceHealthCheck(String name, ServingStatus status) {
            this.name = name;
            this.status = status;
        }

        boolean awaitPoll(long timeout, TimeUnit unit) throws InterruptedException {
            return polled.await(timeout, unit);
        }

        int statusChecks() {
            return statusChecks.get();
        }

        @Override
        public String getServiceName() {
            return name;
        }

        @Override
        public ServingStatus getHealthStatus() {
            statusChecks.incrementAndGet();
            polled.countDown();
            return status;
        }
    }
}
