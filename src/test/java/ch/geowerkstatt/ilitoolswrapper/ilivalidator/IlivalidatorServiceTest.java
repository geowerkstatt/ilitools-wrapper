package ch.geowerkstatt.ilitoolswrapper.ilivalidator;

import ch.geowerkstatt.ilitoolswrapper.RecordingStreamObserver;
import ch.geowerkstatt.ilitoolswrapper.files.InMemoryFileManager;
import ch.geowerkstatt.ilitoolswrapper.files.InMemoryProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.modeldir.PrivateNetworkPolicy;
import ch.geowerkstatt.ilitoolswrapper.plugins.PluginCatalog;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateResponse;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunnerMock;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public final class IlivalidatorServiceTest {
    @TempDir
    private Path pluginRoot;

    private InMemoryFileManager fileManager;
    private IlitoolsRunnerMock ilitoolsRunner;
    private IlivalidatorService service;
    private RecordingStreamObserver<ValidateResponse> responseObserver;

    @BeforeEach
    void setUp() {
        fileManager = new InMemoryFileManager();
        ilitoolsRunner = new IlitoolsRunnerMock();
        // Private networks are allowed so that the unit tests never depend on name resolution.
        service = new IlivalidatorService(fileManager, ilitoolsRunner, PrivateNetworkPolicy.ALLOW, new PluginCatalog(pluginRoot));
        responseObserver = new RecordingStreamObserver<>();
    }

    @Test
    void validateAcceptsRequestData() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("data"));
        InMemoryProcessingFile created = fileManager.lastCreatedFile();
        requestObserver.onCompleted();

        assertArrayEquals("data".getBytes(StandardCharsets.UTF_8), created.contents());
        assertTrue(created.isClosed(), "File should be closed once the request completes.");

        assertHasResponses(true, IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE);
    }

    @Test
    void validateAssemblesReceivedChunks() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("Hello"));
        requestObserver.onNext(chunk(" "));
        requestObserver.onNext(chunk("World"));
        InMemoryProcessingFile created = fileManager.lastCreatedFile();
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        assertEquals(3, fileManager.createdFiles().size(), "File manager should create the uploaded and both log files");

        assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), created.contents());
        assertTrue(created.isClosed(), "File should be closed once the request completes.");

        assertHasResponses(true, IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE);
    }

    @Test
    void validatePassesInfoOptionsAsArguments() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(ValidateRequest.newBuilder()
                .setInfo(ValidateRequestInfo.newBuilder()
                        .setForceTypeValidation(true)
                        .setDisableAreaValidation(true)
                        .setDisableConstraintValidation(true)
                        .setAllObjectsAccessible(true)
                        .setMultiplicityOff(true)
                        .setSkipPolygonBuilding(true))
                .build());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunnerMock.Tool.ILIVALIDATOR, arguments.tool());

        List<String> args = arguments.args();
        assertTrue(args.contains("--log"), "The runner should write a text log file.");
        assertTrue(args.contains("--xtflog"), "The runner should write an XTF log file.");
        assertTrue(args.contains("--forceTypeValidation"));
        assertTrue(args.contains("--disableAreaValidation"));
        assertTrue(args.contains("--disableConstraintValidation"));
        assertTrue(args.contains("--allObjectsAccessible"));
        assertTrue(args.contains("--multiplicityOff"));
        assertTrue(args.contains("--skipPolygonBuilding"));

        assertHasResponses(true, IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE);
    }

    @Test
    void validateOmitsDisabledInfoOptions() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunnerMock.Tool.ILIVALIDATOR, arguments.tool());

        List<String> args = arguments.args();
        assertTrue(args.contains("--log"), "The runner should write a text log file.");
        assertTrue(args.contains("--xtflog"), "The runner should write an XTF log file.");
        assertFalse(args.contains("--forceTypeValidation"));
        assertFalse(args.contains("--disableAreaValidation"));
        assertFalse(args.contains("--disableConstraintValidation"));
        assertFalse(args.contains("--allObjectsAccessible"));
        assertFalse(args.contains("--multiplicityOff"));
        assertFalse(args.contains("--skipPolygonBuilding"));
        assertFalse(args.contains("--modeldir"), "Without model dirs the tool default must stay in effect.");
        assertFalse(args.contains("--metaConfig"));

        assertHasResponses(true, IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE);
    }

    @Test
    void transferFileIsPassedAsLastArgument() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("data"));
        InMemoryProcessingFile transferFile = fileManager.lastCreatedFile();
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");

        List<String> args = arguments.args();
        String expected = transferFile.filePath().toAbsolutePath().toString();
        assertEquals(expected, args.getLast(), "The transfer file should be the last, positional argument.");
    }

    @Test
    void chunkBeforeInfoIsRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(chunk("data"));

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty());
    }

    @Test
    void fileStartBeforeInfoIsRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty());
    }

    @Test
    void nonTransferFileTypeIsRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.LOG_FILE));

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty());
    }

    @Test
    void multipleTransferFilesAreRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("first"));
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("second"));
        requestObserver.onCompleted();

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertNull(ilitoolsRunner.lastArguments(), "ilivalidator should not run when more than one transfer file is sent.");
    }

    @Test
    void repositoryArchiveIsReceivedAsZipFile() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.REPOSITORY_ARCHIVE));
        requestObserver.onNext(chunk("PK"));

        assertNull(responseObserver.error());
        InMemoryProcessingFile created = fileManager.lastCreatedFile();
        assertTrue(created.filePath().toString().endsWith(".zip"), "The archive should be stored as a zip file, but was " + created.filePath());
    }

    @Test
    void multipleRepositoryArchivesAreRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("data"));
        requestObserver.onNext(fileStart(IlivalidatorFileType.REPOSITORY_ARCHIVE));
        requestObserver.onNext(chunk("first"));
        requestObserver.onNext(fileStart(IlivalidatorFileType.REPOSITORY_ARCHIVE));
        requestObserver.onNext(chunk("second"));
        requestObserver.onCompleted();

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertNull(ilitoolsRunner.lastArguments(), "ilivalidator should not run when more than one archive is sent.");
    }

    @Test
    void modelFileIsReceivedAsIliFile() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.MODEL_FILE));
        requestObserver.onNext(chunk("MODEL Example (en) = END Example."));

        assertNull(responseObserver.error());
        InMemoryProcessingFile created = fileManager.lastCreatedFile();
        String path = created.filePath().toString().replace('\\', '/');
        assertTrue(path.endsWith("models/file1.ili"), "The model should be stored as an ili file in the models subfolder, but was " + created.filePath());
    }

    @Test
    void multipleModelFilesAreAccepted() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("data"));
        requestObserver.onNext(fileStart(IlivalidatorFileType.MODEL_FILE));
        requestObserver.onNext(chunk("first model"));
        requestObserver.onNext(fileStart(IlivalidatorFileType.MODEL_FILE));
        requestObserver.onNext(chunk("second model"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        assertNotNull(ilitoolsRunner.lastArguments(), "The runner should have been invoked.");
        assertEquals(5, fileManager.createdFiles().size(), "File manager should create the transfer file, both model files and both log files");

        assertHasResponses(true, IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE);
    }

    @Test
    void modelFilesAreNotPassedAsArguments() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("data"));
        InMemoryProcessingFile transferFile = fileManager.lastCreatedFile();
        requestObserver.onNext(fileStart(IlivalidatorFileType.MODEL_FILE));
        requestObserver.onNext(chunk("model"));
        InMemoryProcessingFile modelFile = fileManager.lastCreatedFile();
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");

        List<String> args = arguments.args();
        assertEquals(transferFile.filePath().toAbsolutePath().toString(), args.getLast(), "The transfer file should stay the last, positional argument.");
        assertFalse(args.contains(modelFile.filePath().toAbsolutePath().toString()), "Model files work by lying in the session directory, not through arguments.");
    }

    @Test
    void itfTransferFileIsStoredWithItfExtension() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_ITF));
        requestObserver.onNext(chunk("SCNT"));

        assertNull(responseObserver.error());
        InMemoryProcessingFile created = fileManager.lastCreatedFile();
        assertTrue(created.filePath().toString().endsWith(".itf"), "An ITF transfer file should keep the itf extension, but was " + created.filePath());
    }

    @Test
    void mixedTransferFileTypesAreRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("first"));
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_ITF));
        requestObserver.onNext(chunk("second"));
        requestObserver.onCompleted();

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertNull(ilitoolsRunner.lastArguments(), "The transfer file must be unique across both transfer file types.");
    }

    @Test
    void modelFileWithoutTransferFileIsRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.MODEL_FILE));
        requestObserver.onNext(chunk("model"));
        requestObserver.onCompleted();

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        String description = Status.fromThrowable(responseObserver.error()).getDescription();
        assertNotNull(description);
        assertTrue(description.contains("transfer file"), "The request should fail on the missing transfer file, but was: " + description);
        assertNull(ilitoolsRunner.lastArguments(), "ilivalidator should not run without a transfer file.");
    }

    @Test
    void duplicateInfoIsRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(info());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
    }

    @Test
    void completingWithoutContentIsRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onCompleted();

        assertNotNull(responseObserver.error());
        assertFalse(responseObserver.isCompleted());
        assertEquals(Status.Code.ABORTED, statusCodeOf(responseObserver.error()));
    }

    @Test
    void fileManagerFailureIsPropagated() {
        fileManager.failNextCreationWith(new IllegalStateException("disk full"));
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));

        assertNotNull(responseObserver.error());
        assertTrue(fileManager.createdFiles().isEmpty());
    }

    @Test
    void returnsBothLogsOnFailure() {
        ilitoolsRunner.failRunWith(new RuntimeException("validation failed"));
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        assertHasResponses(false, IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE);
    }

    @Test
    void validatePassesModelRepositoryOptionsAsArguments() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(ValidateRequest.newBuilder()
                .setInfo(ValidateRequestInfo.newBuilder()
                        .addModelDirs("%ITF_DIR")
                        .addModelDirs("https://models.interlis.ch/")
                        .setMetaConfig("ilidata:DEFAULT"))
                .build());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("data"));
        InMemoryProcessingFile transferFile = fileManager.lastCreatedFile();
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");

        List<String> args = arguments.args();
        assertArgumentWithValue(args, "--modeldir", "%ITF_DIR;https://models.interlis.ch/");
        assertArgumentWithValue(args, "--metaConfig", "ilidata:DEFAULT");
        assertEquals(transferFile.filePath().toAbsolutePath().toString(), args.getLast(), "The transfer file should stay the last, positional argument.");

        assertHasResponses(true, IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE);
    }

    @Test
    void invalidModelDirIsRejectedBeforeAnyFileIsReceived() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(ValidateRequest.newBuilder()
                .setInfo(ValidateRequestInfo.newBuilder()
                        .addModelDirs("file:///etc/"))
                .build());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty(), "No file should be created for a rejected request.");
        assertNull(ilitoolsRunner.lastArguments(), "ilivalidator should not run for a rejected request.");
    }

    @Test
    void placeholderOfOtherToolIsRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(ValidateRequest.newBuilder()
                .setInfo(ValidateRequestInfo.newBuilder()
                        .addModelDirs("%XTF_DIR"))
                .build());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertNull(ilitoolsRunner.lastArguments(), "ilivalidator should not run for a rejected request.");
    }

    @Test
    void metaConfigFilePathIsRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(ValidateRequest.newBuilder()
                .setInfo(ValidateRequestInfo.newBuilder()
                        .setMetaConfig("/repositories/profile.toml"))
                .build());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertNull(ilitoolsRunner.lastArguments(), "ilivalidator should not run for a rejected request.");
    }

    @Test
    void pluginsAreNotPassedWithoutASelection() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(IlivalidatorFileType.TRANSFER_FILE_XTF));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        // Leaving the argument out is what keeps the tool on its own default, which points into the tool
        // installation and carries no plugin. Measured: the tool logs a plugin folder on every run, so its log
        // says nothing about whether a plugin was loaded.
        assertFalse(arguments.args().contains("--plugins"), "Without a selection no plugin directory may be passed.");

        assertHasResponses(true, IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE);
    }

    @Test
    void unknownPluginIsRejected() {
        StreamObserver<ValidateRequest> requestObserver = service.validate(responseObserver);

        // The catalog directory of this test is empty, so no id is on offer.
        requestObserver.onNext(ValidateRequest.newBuilder()
                .setInfo(ValidateRequestInfo.newBuilder()
                        .addPluginIds("geow-interlis-functions"))
                .build());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty(), "No file should be created for a rejected request.");
        assertNull(ilitoolsRunner.lastArguments(), "ilivalidator should not run for a rejected request.");
    }

    @Test
    void returnsHealthyOnSuccess() {
        assertEquals(HealthCheckResponse.ServingStatus.SERVING, service.getHealthStatus());

        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunnerMock.Tool.ILIVALIDATOR, arguments.tool());
        assertEquals(List.of("--version"), arguments.args());
        assertNotNull(arguments.timeout(), "The health check should use a timeout.");
    }

    @Test
    void returnsUnhealthyOnError() {
        ilitoolsRunner.failRunWith(new RuntimeException("process failed"));
        assertEquals(HealthCheckResponse.ServingStatus.NOT_SERVING, service.getHealthStatus());

        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunnerMock.Tool.ILIVALIDATOR, arguments.tool());
        assertEquals(List.of("--version"), arguments.args());
        assertNotNull(arguments.timeout(), "The health check should use a timeout.");
    }

    void assertHasResponses(boolean success, IlivalidatorFileType... expectedFiles) {
        assertDoesNotThrow(() -> responseObserver.completion().get(10, TimeUnit.SECONDS));

        List<ValidateResponse> responses = responseObserver.values();
        int expectedResponseCount = expectedFiles.length + 1; // include status response
        assertEquals(expectedResponseCount, responses.size(), "Unexpected number of responses.");
        assertEquals(ValidateResponse.PayloadCase.STATUS, responses.getFirst().getPayloadCase(), "First response should be status.");
        assertEquals(success, responses.getFirst().getStatus().getSuccess(), "Unexpected success status in response.");

        for (int i = 0; i < expectedFiles.length; i++) {
            ValidateResponse response = responses.get(i + 1);
            assertEquals(ValidateResponse.PayloadCase.FILESTART, response.getPayloadCase(), "Expected file start response at index " + (i + 1));
            assertEquals(expectedFiles[i], response.getFileStart().getType(), "Unexpected file type at index " + (i + 1));
            // the mocks do not provide any file content (chunks)
        }
    }

    private static void assertArgumentWithValue(List<String> args, String name, String value) {
        int index = args.indexOf(name);
        assertTrue(index >= 0, "Expected argument " + name + " to be present.");
        assertTrue(index + 1 < args.size(), "Expected a value after " + name + ".");
        assertEquals(value, args.get(index + 1), "Unexpected value for " + name + ".");
    }

    private static Status.Code statusCodeOf(Throwable error) {
        Status status = Status.fromThrowable(error);
        return status.getCode();
    }

    private static ValidateRequest info() {
        return ValidateRequest.newBuilder()
                .setInfo(ValidateRequestInfo.newBuilder())
                .build();
    }

    private static ValidateRequest fileStart(IlivalidatorFileType fileType) {
        return ValidateRequest.newBuilder()
                .setFileStart(IlivalidatorFileStart.newBuilder()
                        .setType(fileType))
                .build();
    }

    private static ValidateRequest chunk(String content) {
        return ValidateRequest.newBuilder()
                .setChunk(ByteString.copyFromUtf8(content))
                .build();
    }
}
