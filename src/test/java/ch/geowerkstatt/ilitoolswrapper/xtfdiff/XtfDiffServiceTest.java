package ch.geowerkstatt.ilitoolswrapper.xtfdiff;

import ch.geowerkstatt.ilitoolswrapper.RecordingStreamObserver;
import ch.geowerkstatt.ilitoolswrapper.files.InMemoryFileManager;
import ch.geowerkstatt.ilitoolswrapper.files.InMemoryProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.modeldir.PrivateNetworkPolicy;
import ch.geowerkstatt.ilitoolswrapper.modeldir.RepositoryCatalog;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.DiffRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.DiffRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.DiffResponse;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.XtfDiffFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.XtfDiffFileType;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunner;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunnerMock;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public final class XtfDiffServiceTest {
    @TempDir
    private Path repositoryRoot;

    private InMemoryFileManager fileManager;
    private IlitoolsRunnerMock ilitoolsRunner;
    private XtfDiffService service;
    private RecordingStreamObserver<DiffResponse> responseObserver;

    @BeforeEach
    void setUp() {
        fileManager = new InMemoryFileManager();
        ilitoolsRunner = new IlitoolsRunnerMock();
        // Private networks are allowed so that the unit tests never depend on name resolution.
        service = new XtfDiffService(
                fileManager,
                ilitoolsRunner,
                PrivateNetworkPolicy.ALLOW,
                new RepositoryCatalog(repositoryRoot),
                new IlitoolsRunner.Timeout(30, TimeUnit.SECONDS));
        responseObserver = new RecordingStreamObserver<>();
    }

    @Test
    void diffReceivesBothTransferFiles() {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(XtfDiffFileType.OLD_TRANSFER_FILE));
        requestObserver.onNext(chunk("<old/>"));
        InMemoryProcessingFile oldFile = fileManager.lastCreatedFile();
        requestObserver.onNext(fileStart(XtfDiffFileType.NEW_TRANSFER_FILE));
        requestObserver.onNext(chunk("<new"));
        requestObserver.onNext(chunk("/>"));
        InMemoryProcessingFile newFile = fileManager.lastCreatedFile();
        requestObserver.onCompleted();

        assertArrayEquals("<old/>".getBytes(StandardCharsets.UTF_8), oldFile.contents());
        assertArrayEquals("<new/>".getBytes(StandardCharsets.UTF_8), newFile.contents());
        assertTrue(oldFile.isClosed(), "File should be closed once the request completes.");
        assertTrue(newFile.isClosed(), "File should be closed once the request completes.");

        assertHasResponses(true, XtfDiffFileType.LOG_FILE, XtfDiffFileType.DIFF_FILE);
    }

    @Test
    void diffPassesTheOldStateFirstWhateverTheOrderOfTheFiles() {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        // The new state arrives first: its file type decides the role, not its position in the request.
        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(XtfDiffFileType.NEW_TRANSFER_FILE));
        requestObserver.onNext(chunk("<new/>"));
        InMemoryProcessingFile newFile = fileManager.lastCreatedFile();
        requestObserver.onNext(fileStart(XtfDiffFileType.OLD_TRANSFER_FILE));
        requestObserver.onNext(chunk("<old/>"));
        InMemoryProcessingFile oldFile = fileManager.lastCreatedFile();
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunner.Tool.XTF_DIFF, arguments.tool());
        assertTrue(arguments.useSessionCache(), "The tool resolves models through the repositories like the other tools.");

        // The tool takes the old state, the new state and the file to write the changes to as its last three arguments.
        List<String> args = arguments.args();
        assertTrue(args.size() >= 3, "Expected the three file arguments, but was " + args);
        List<String> fileArguments = args.subList(args.size() - 3, args.size());
        assertEquals(oldFile.filePath().toAbsolutePath().toString(), fileArguments.get(0), "The old state must come first.");
        assertEquals(newFile.filePath().toAbsolutePath().toString(), fileArguments.get(1), "The new state must come second.");
        assertTrue(fileArguments.get(2).endsWith(".json"), "The changes are written as JSON, but the last argument was " + fileArguments.get(2));
        assertTrue(args.contains("--logfile"), "The tool should write its log to the file that is returned.");

        assertHasResponses(true, XtfDiffFileType.LOG_FILE, XtfDiffFileType.DIFF_FILE);
    }

    @Test
    void diffFallsBackToTheOfficialRepository() {
        sendBothTransferFiles(info());

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertArgumentWithValue(arguments.args(), "--modeldir", "https://models.interlis.ch/");
    }

    @Test
    void diffPassesAnOfferedRepositoryAsItsDirectory() throws IOException {
        Path repository = Files.createDirectory(repositoryRoot.resolve("dmav@0.1.1"));

        sendBothTransferFiles(DiffRequest.newBuilder()
                .setInfo(DiffRequestInfo.newBuilder()
                        .addModelDirs("https://models.interlis.ch/")
                        .addModelDirs("%REPOSITORIES/dmav@0.1.1"))
                .build());

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        // The tool does not know the placeholder, so it has to receive the directory itself.
        assertArgumentWithValue(arguments.args(), "--modeldir", "https://models.interlis.ch/;" + repository.toAbsolutePath());
    }

    @Test
    void toolPlaceholderIsRejected() {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        // The XTF-Diff-Tool hands --modeldir to the model manager as it is and expands no placeholder.
        requestObserver.onNext(DiffRequest.newBuilder()
                .setInfo(DiffRequestInfo.newBuilder()
                        .addModelDirs("%ITF_DIR/models"))
                .build());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty(), "No file should be created for a rejected request.");
        assertNull(ilitoolsRunner.lastArguments(), "The tool should not run for a rejected request.");
    }

    @Test
    void missingNewStateIsRejected() {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(XtfDiffFileType.OLD_TRANSFER_FILE));
        requestObserver.onNext(chunk("<old/>"));
        requestObserver.onCompleted();

        assertRejectedBeforeTheToolRuns();
    }

    @Test
    void secondOldStateIsRejected() {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(XtfDiffFileType.OLD_TRANSFER_FILE));
        requestObserver.onNext(chunk("<old/>"));
        requestObserver.onNext(fileStart(XtfDiffFileType.OLD_TRANSFER_FILE));
        requestObserver.onNext(chunk("<older/>"));
        requestObserver.onNext(fileStart(XtfDiffFileType.NEW_TRANSFER_FILE));
        requestObserver.onNext(chunk("<new/>"));
        requestObserver.onCompleted();

        assertRejectedBeforeTheToolRuns();
    }

    @ParameterizedTest
    @EnumSource(value = XtfDiffFileType.class, names = {"LOG_FILE", "DIFF_FILE"})
    void outputFileTypeIsRejectedAsInput(XtfDiffFileType fileType) {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(fileType));

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty(), "No file should be created for a file the request may not send.");
    }

    @Test
    void chunkBeforeInfoIsRejected() {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        requestObserver.onNext(chunk("<old/>"));

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
    }

    @Test
    void fileStartBeforeInfoIsRejected() {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        requestObserver.onNext(fileStart(XtfDiffFileType.OLD_TRANSFER_FILE));

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty(), "No file should be created before the info message.");
    }

    @Test
    void duplicateInfoIsRejected() {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(info());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
    }

    @Test
    void completingWithoutContentIsRejected() {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        requestObserver.onCompleted();

        assertNotNull(responseObserver.error());
        assertFalse(responseObserver.isCompleted());
        assertEquals(Status.Code.ABORTED, statusCodeOf(responseObserver.error()));
    }

    @Test
    void reportsRunFailureWithTheLogAlone() {
        ilitoolsRunner.failRunWith(new RuntimeException("process failed"));

        sendBothTransferFiles(info());

        // The log says why the tool failed, for example two transfer files of different models; a diff does not exist.
        assertNull(responseObserver.error());
        assertHasResponses(false, XtfDiffFileType.LOG_FILE);
    }

    @Test
    void returnsHealthyOnSuccess() {
        ilitoolsRunner.offerVersions("1.0.20");

        assertEquals(HealthCheckResponse.ServingStatus.SERVING, service.getHealthStatus());

        List<IlitoolsRunnerMock.Arguments> allArguments = ilitoolsRunner.allArguments();
        assertFalse(allArguments.isEmpty(), "The runner should have been invoked.");
        IlitoolsRunnerMock.Arguments arguments = allArguments.getFirst();
        assertEquals(IlitoolsRunner.Tool.XTF_DIFF, arguments.tool());
        assertEquals(List.of("--version"), arguments.args());
        assertNotNull(arguments.timeout(), "The health check should use a timeout.");
        assertEquals("", arguments.toolVersion(), "The health check must probe the deployment default.");

        List<String> probedVersions = allArguments.stream().map(IlitoolsRunnerMock.Arguments::toolVersion).toList();
        assertEquals(List.of("", "1.0.20"), probedVersions, "The health check must probe the default and every offered version.");
    }

    @Test
    void returnsUnhealthyOnError() {
        ilitoolsRunner.failRunWith(new RuntimeException("process failed"));

        assertEquals(HealthCheckResponse.ServingStatus.NOT_SERVING, service.getHealthStatus());

        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunner.Tool.XTF_DIFF, arguments.tool());
        assertEquals(List.of("--version"), arguments.args());
    }

    @Test
    void clientCancellationCancelsToolRun() throws Exception {
        ilitoolsRunner.holdNextRun();
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(XtfDiffFileType.OLD_TRANSFER_FILE));
        requestObserver.onNext(chunk("<old/>"));
        InMemoryProcessingFile oldFile = fileManager.lastCreatedFile();
        requestObserver.onNext(fileStart(XtfDiffFileType.NEW_TRANSFER_FILE));
        requestObserver.onNext(chunk("<new/>"));
        requestObserver.onCompleted();

        assertTrue(responseObserver.hasCancelHandler(), "The service must register a cancel handler for a server call.");
        CompletableFuture<Void> pendingRun = ilitoolsRunner.pendingRun();
        assertNotNull(pendingRun, "The runner should have been invoked.");
        assertFalse(pendingRun.isDone(), "The tool should be running before cancellation.");

        responseObserver.cancel();

        assertTrue(pendingRun.isCancelled(), "Cancelling the call must cancel the tool run so the process is destroyed.");
        assertTrue(waitUntil(oldFile::isClosed), "The session files should be cleaned up after cancellation.");
        assertNull(responseObserver.error(), "A cancelled call must not receive an error response.");
        assertTrue(responseObserver.values().isEmpty(), "A cancelled call must not receive any response.");
    }

    private void sendBothTransferFiles(DiffRequest info) {
        StreamObserver<DiffRequest> requestObserver = service.diff(responseObserver);

        requestObserver.onNext(info);
        requestObserver.onNext(fileStart(XtfDiffFileType.OLD_TRANSFER_FILE));
        requestObserver.onNext(chunk("<old/>"));
        requestObserver.onNext(fileStart(XtfDiffFileType.NEW_TRANSFER_FILE));
        requestObserver.onNext(chunk("<new/>"));
        requestObserver.onCompleted();
    }

    private void assertRejectedBeforeTheToolRuns() {
        Throwable error = responseObserver.error();
        assertNotNull(error);
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(error));
        String description = String.valueOf(Status.fromThrowable(error).getDescription());
        assertTrue(description.contains("one old and one new"), "The rejection should name the expected files, but was: " + description);
        assertNull(ilitoolsRunner.lastArguments(), "The tool should not run for a rejected request.");
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static void assertArgumentWithValue(List<String> args, String name, String value) {
        int index = args.indexOf(name);
        assertTrue(index >= 0, "Expected argument " + name + " to be present.");
        assertTrue(index + 1 < args.size(), "Expected a value after " + name + ".");
        assertEquals(value, args.get(index + 1), "Unexpected value for " + name + ".");
    }

    void assertHasResponses(boolean success, XtfDiffFileType... expectedFiles) {
        assertDoesNotThrow(() -> responseObserver.completion().get(10, TimeUnit.SECONDS));

        List<DiffResponse> responses = responseObserver.values();
        int expectedResponseCount = expectedFiles.length + 1; // include status response
        assertEquals(expectedResponseCount, responses.size(), "Unexpected number of responses.");
        assertEquals(DiffResponse.PayloadCase.STATUS, responses.getFirst().getPayloadCase(), "First response should be status.");
        assertEquals(success, responses.getFirst().getStatus().getSuccess(), "Unexpected success status in response.");

        for (int i = 0; i < expectedFiles.length; i++) {
            DiffResponse response = responses.get(i + 1);
            assertEquals(DiffResponse.PayloadCase.FILESTART, response.getPayloadCase(), "Expected file start response at index " + (i + 1));
            assertEquals(expectedFiles[i], response.getFileStart().getType(), "Unexpected file type at index " + (i + 1));
            // the mocks do not provide any file content (chunks)
        }
    }

    private static Status.Code statusCodeOf(Throwable error) {
        return Status.fromThrowable(error).getCode();
    }

    private static DiffRequest info() {
        return DiffRequest.newBuilder()
                .setInfo(DiffRequestInfo.newBuilder())
                .build();
    }

    private static DiffRequest fileStart(XtfDiffFileType fileType) {
        return DiffRequest.newBuilder()
                .setFileStart(XtfDiffFileStart.newBuilder()
                        .setType(fileType))
                .build();
    }

    private static DiffRequest chunk(String content) {
        return DiffRequest.newBuilder()
                .setChunk(ByteString.copyFromUtf8(content))
                .build();
    }
}
