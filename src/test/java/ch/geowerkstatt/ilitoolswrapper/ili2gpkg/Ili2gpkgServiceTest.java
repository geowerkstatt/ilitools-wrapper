package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import ch.geowerkstatt.ilitoolswrapper.RecordingStreamObserver;
import ch.geowerkstatt.ilitoolswrapper.files.InMemoryFileManager;
import ch.geowerkstatt.ilitoolswrapper.files.InMemoryProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.modeldir.PrivateNetworkPolicy;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertOperation;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertResponse;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileType;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunnerMock;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public final class Ili2gpkgServiceTest {
    private InMemoryFileManager fileManager;
    private IlitoolsRunnerMock ilitoolsRunner;
    private Ili2gpkgService service;
    private RecordingStreamObserver<ConvertResponse> responseObserver;

    @BeforeEach
    void setUp() {
        fileManager = new InMemoryFileManager();
        ilitoolsRunner = new IlitoolsRunnerMock();
        // Private networks are allowed so that the unit tests never depend on name resolution.
        service = new Ili2gpkgService(fileManager, ilitoolsRunner, PrivateNetworkPolicy.ALLOW);
        responseObserver = new RecordingStreamObserver<>();
    }

    @Test
    void convertAcceptsRequestData() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("data"));
        InMemoryProcessingFile created = fileManager.lastCreatedFile();
        requestObserver.onCompleted();

        assertArrayEquals("data".getBytes(StandardCharsets.UTF_8), created.contents());
        assertTrue(created.isClosed(), "File should be closed once the request completes.");

        assertHasResponses(true, Ili2gpkgFileType.LOG_FILE, Ili2gpkgFileType.DB_FILE);
    }

    @Test
    void convertAssemblesReceivedChunks() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("Hello"));
        requestObserver.onNext(chunk(" "));
        requestObserver.onNext(chunk("World"));
        InMemoryProcessingFile created = fileManager.lastCreatedFile();
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        assertEquals(3, fileManager.createdFiles().size(), "File manager should create uploaded and output files");

        assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), created.contents());
        assertTrue(created.isClosed(), "File should be closed once the request completes.");

        assertHasResponses(true, Ili2gpkgFileType.LOG_FILE, Ili2gpkgFileType.DB_FILE);
    }

    @Test
    void convertHandlesMultipleFiles() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.TRANSFER_FILE));
        requestObserver.onNext(chunk("<TRANSFER>"));
        requestObserver.onNext(chunk("</TRANSFER>"));
        InMemoryProcessingFile xtfFile1 = fileManager.lastCreatedFile();

        requestObserver.onNext(fileStart(Ili2gpkgFileType.TRANSFER_FILE));
        requestObserver.onNext(chunk("<TRANSFER/>"));
        InMemoryProcessingFile xtfFile2 = fileManager.lastCreatedFile();

        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("Hello World"));
        InMemoryProcessingFile txtFile = fileManager.lastCreatedFile();

        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        assertEquals(5, fileManager.createdFiles().size(), "File manager should create uploaded and output files");

        assertArrayEquals("<TRANSFER></TRANSFER>".getBytes(StandardCharsets.UTF_8), xtfFile1.contents());
        assertTrue(xtfFile1.isClosed(), "File should be closed once the request completes.");

        assertArrayEquals("<TRANSFER/>".getBytes(StandardCharsets.UTF_8), xtfFile2.contents());
        assertTrue(xtfFile2.isClosed(), "File should be closed once the request completes.");

        assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), txtFile.contents());
        assertTrue(txtFile.isClosed(), "File should be closed once the request completes.");

        assertHasResponses(true, Ili2gpkgFileType.LOG_FILE, Ili2gpkgFileType.DB_FILE);
    }

    @Test
    void convertPassesInfoOptionsAsArguments() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(ConvertRequest.newBuilder()
                .setInfo(ConvertRequestInfo.newBuilder()
                        .setOperation(ConvertOperation.OPERATION_SCHEMA_IMPORT)
                        .addModels("ModelA")
                        .addModels("ModelB")
                        .setDefaultSrsCode(2056)
                        .setDisableValidation(true)
                        .setCreateBasketCol(true)
                        .setSqlEnableNull(true)
                        .setSkipReferenceErrors(true)
                        .setSkipGeometryErrors(true)
                        .setImportTid(true)
                        .setStrokeArcs(true))
                .build());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunnerMock.Tool.ILI2GPKG, arguments.tool());

        List<String> args = arguments.args();
        assertTrue(args.contains("--log"), "The runner should redirect the output to a log file.");
        assertArgumentWithValue(args, "--models", "ModelA;ModelB");
        assertArgumentWithValue(args, "--defaultSrsCode", "2056");
        assertTrue(args.contains("--disableValidation"));
        assertTrue(args.contains("--createBasketCol"));
        assertTrue(args.contains("--sqlEnableNull"));
        assertTrue(args.contains("--skipReferenceErrors"));
        assertTrue(args.contains("--skipGeometryErrors"));
        assertTrue(args.contains("--importTid"));
        assertTrue(args.contains("--strokeArcs"));

        assertHasResponses(true, Ili2gpkgFileType.LOG_FILE, Ili2gpkgFileType.DB_FILE);
    }

    @Test
    void convertOmitsDisabledInfoOptions() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunnerMock.Tool.ILI2GPKG, arguments.tool());

        List<String> args = arguments.args();
        assertTrue(args.contains("--log"), "The runner should redirect the output to a log file.");
        assertFalse(args.contains("--models"));
        assertFalse(args.contains("--defaultSrsCode"));
        assertFalse(args.contains("--disableValidation"));
        assertFalse(args.contains("--createBasketCol"));
        assertFalse(args.contains("--sqlEnableNull"));
        assertFalse(args.contains("--skipReferenceErrors"));
        assertFalse(args.contains("--skipGeometryErrors"));
        assertFalse(args.contains("--importTid"));
        assertFalse(args.contains("--strokeArcs"));
        assertFalse(args.contains("--modeldir"), "Without model dirs the tool default must stay in effect.");
        assertFalse(args.contains("--metaConfig"));

        assertHasResponses(true, Ili2gpkgFileType.LOG_FILE, Ili2gpkgFileType.DB_FILE);
    }

    @Test
    void validateRemovesDisableValidation() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        ConvertRequest request = ConvertRequest.newBuilder()
                .setInfo(ConvertRequestInfo.newBuilder()
                        .setOperation(ConvertOperation.OPERATION_VALIDATE)
                        .setDisableValidation(true))
                .build();
        requestObserver.onNext(request);
        requestObserver.onNext(fileStart(Ili2gpkgFileType.DB_FILE));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunnerMock.Tool.ILI2GPKG, arguments.tool());

        List<String> args = arguments.args();
        assertFalse(args.contains("--disableValidation"));

        assertHasResponses(true, Ili2gpkgFileType.LOG_FILE, Ili2gpkgFileType.XTF_LOG_FILE);
    }

    @Test
    void convertPassesModelRepositoryOptionsAsArguments() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(ConvertRequest.newBuilder()
                .setInfo(ConvertRequestInfo.newBuilder()
                        .setOperation(ConvertOperation.OPERATION_SCHEMA_IMPORT)
                        .addModelDirs("%ILI_FROM_DB")
                        .addModelDirs("%XTF_DIR")
                        .addModelDirs("https://models.interlis.ch/")
                        .setMetaConfig("ilidata:DEFAULT"))
                .build());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");

        List<String> args = arguments.args();
        assertArgumentWithValue(args, "--modeldir", "%ILI_FROM_DB;%XTF_DIR;https://models.interlis.ch/");
        assertArgumentWithValue(args, "--metaConfig", "ilidata:DEFAULT");

        assertHasResponses(true, Ili2gpkgFileType.LOG_FILE, Ili2gpkgFileType.DB_FILE);
    }

    @Test
    void invalidModelDirIsRejectedBeforeAnyFileIsReceived() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(ConvertRequest.newBuilder()
                .setInfo(ConvertRequestInfo.newBuilder()
                        .setOperation(ConvertOperation.OPERATION_SCHEMA_IMPORT)
                        .addModelDirs("/etc/models"))
                .build());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty(), "No file should be created for a rejected request.");
        assertNull(ilitoolsRunner.lastArguments(), "ili2gpkg should not run for a rejected request.");
    }

    @Test
    void placeholderOfOtherToolIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(ConvertRequest.newBuilder()
                .setInfo(ConvertRequestInfo.newBuilder()
                        .setOperation(ConvertOperation.OPERATION_SCHEMA_IMPORT)
                        .addModelDirs("%ITF_DIR"))
                .build());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertNull(ilitoolsRunner.lastArguments(), "ili2gpkg should not run for a rejected request.");
    }

    @Test
    void metaConfigFilePathIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(ConvertRequest.newBuilder()
                .setInfo(ConvertRequestInfo.newBuilder()
                        .setOperation(ConvertOperation.OPERATION_SCHEMA_IMPORT)
                        .setMetaConfig("profile.toml"))
                .build());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertNull(ilitoolsRunner.lastArguments(), "ili2gpkg should not run for a rejected request.");
    }

    public static Stream<Arguments> expectedFileTypeProvider() {
        return Stream.of(
                Arguments.of(ConvertOperation.OPERATION_SCHEMA_IMPORT, Ili2gpkgFileType.DB_FILE, List.of(Ili2gpkgFileType.MODEL_FILE)),
                Arguments.of(ConvertOperation.OPERATION_IMPORT, Ili2gpkgFileType.DB_FILE, List.of(Ili2gpkgFileType.TRANSFER_FILE, Ili2gpkgFileType.DB_FILE)),
                Arguments.of(ConvertOperation.OPERATION_EXPORT, Ili2gpkgFileType.TRANSFER_FILE, List.of(Ili2gpkgFileType.DB_FILE)),
                Arguments.of(ConvertOperation.OPERATION_UPDATE, Ili2gpkgFileType.DB_FILE, List.of(Ili2gpkgFileType.TRANSFER_FILE, Ili2gpkgFileType.DB_FILE)),
                Arguments.of(ConvertOperation.OPERATION_VALIDATE, Ili2gpkgFileType.XTF_LOG_FILE, List.of(Ili2gpkgFileType.DB_FILE))
        );
    }

    @ParameterizedTest
    @MethodSource("expectedFileTypeProvider")
    void operationReturnsExpectedFileType(ConvertOperation operation, Ili2gpkgFileType resultType, List<Ili2gpkgFileType> inputFiles) {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info(operation));
        for (Ili2gpkgFileType inputFile : inputFiles) {
            requestObserver.onNext(fileStart(inputFile));
            requestObserver.onNext(chunk("data"));
        }
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunnerMock.Tool.ILI2GPKG, arguments.tool());

        assertHasResponses(true, Ili2gpkgFileType.LOG_FILE, resultType);
    }

    @Test
    void operationWithoutItsRequiredFilesIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        // Import needs at least one transfer file next to the database file.
        requestObserver.onNext(info(ConvertOperation.OPERATION_IMPORT));
        requestObserver.onNext(fileStart(Ili2gpkgFileType.DB_FILE));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertNull(ilitoolsRunner.lastArguments(), "ili2gpkg should not run when a required file is missing.");
    }

    @Test
    void schemaImportWithMultipleModelFilesIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info(ConvertOperation.OPERATION_SCHEMA_IMPORT));
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("first"));
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("second"));
        requestObserver.onCompleted();

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertNull(ilitoolsRunner.lastArguments(), "ili2gpkg should not run for an ambiguous schema import.");
    }

    @Test
    void chunkBeforeInfoIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(chunk("data"));

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty());
    }

    @Test
    void fileStartBeforeInfoIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(fileStart(Ili2gpkgFileType.DB_FILE));

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty());
    }

    @Test
    void repositoryArchiveIsReceivedAsZipFile() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.REPOSITORY_ARCHIVE));
        requestObserver.onNext(chunk("PK"));

        assertNull(responseObserver.error());
        InMemoryProcessingFile created = fileManager.lastCreatedFile();
        assertTrue(created.filePath().toString().endsWith(".zip"), "The archive should be stored as a zip file, but was " + created.filePath());
    }

    @Test
    void multipleRepositoryArchivesAreRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("data"));
        requestObserver.onNext(fileStart(Ili2gpkgFileType.REPOSITORY_ARCHIVE));
        requestObserver.onNext(chunk("first"));
        requestObserver.onNext(fileStart(Ili2gpkgFileType.REPOSITORY_ARCHIVE));
        requestObserver.onNext(chunk("second"));
        requestObserver.onCompleted();

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertNull(ilitoolsRunner.lastArguments(), "ili2gpkg should not run when more than one archive is sent.");
    }

    @Test
    void duplicateInfoIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(info());

        assertNotNull(responseObserver.error());
        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
    }

    @Test
    void completingWithoutContentIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onCompleted();

        assertNotNull(responseObserver.error());
        assertFalse(responseObserver.isCompleted());
        assertEquals(Status.Code.ABORTED, statusCodeOf(responseObserver.error()));
    }

    @Test
    void fileManagerFailureIsPropagated() {
        fileManager.failNextCreationWith(new IllegalStateException("disk full"));
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.DB_FILE));

        assertNotNull(responseObserver.error());
        assertTrue(fileManager.createdFiles().isEmpty());
    }

    @Test
    void reportsRunFailure() {
        ilitoolsRunner.failRunWith(new RuntimeException("process failed"));
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        assertHasResponses(false, Ili2gpkgFileType.LOG_FILE);
    }

    @Test
    void validateReturnsXtfLogOnFailure() {
        ilitoolsRunner.failRunWith(new RuntimeException("validation failed"));
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info(ConvertOperation.OPERATION_VALIDATE));
        requestObserver.onNext(fileStart(Ili2gpkgFileType.DB_FILE));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        assertHasResponses(false, Ili2gpkgFileType.LOG_FILE, Ili2gpkgFileType.XTF_LOG_FILE);
    }

    @Test
    void returnsHealthyOnSuccess() {
        assertEquals(HealthCheckResponse.ServingStatus.SERVING, service.getHealthStatus());

        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunnerMock.Tool.ILI2GPKG, arguments.tool());
        assertEquals(List.of("--version"), arguments.args());
        assertNotNull(arguments.timeout(), "The health check should use a timeout.");
    }

    @Test
    void returnsUnhealthyOnError() {
        ilitoolsRunner.failRunWith(new RuntimeException("process failed"));
        assertEquals(HealthCheckResponse.ServingStatus.NOT_SERVING, service.getHealthStatus());

        IlitoolsRunnerMock.Arguments arguments = ilitoolsRunner.lastArguments();
        assertNotNull(arguments, "The runner should have been invoked.");
        assertEquals(IlitoolsRunnerMock.Tool.ILI2GPKG, arguments.tool());
        assertEquals(List.of("--version"), arguments.args());
        assertNotNull(arguments.timeout(), "The health check should use a timeout.");
    }

    private static void assertArgumentWithValue(List<String> args, String name, String value) {
        int index = args.indexOf(name);
        assertTrue(index >= 0, "Expected argument " + name + " to be present.");
        assertTrue(index + 1 < args.size(), "Expected a value after " + name + ".");
        assertEquals(value, args.get(index + 1), "Unexpected value for " + name + ".");
    }

    void assertHasResponses(boolean success, Ili2gpkgFileType... expectedFiles) {
        assertDoesNotThrow(() -> responseObserver.completion().get(10, TimeUnit.SECONDS));

        List<ConvertResponse> responses = responseObserver.values();
        int expectedResponseCount = expectedFiles.length + 1; // include status response
        assertEquals(expectedResponseCount, responses.size(), "Unexpected number of responses.");
        assertEquals(ConvertResponse.PayloadCase.STATUS, responses.getFirst().getPayloadCase(), "First response should be status.");
        assertEquals(success, responses.getFirst().getStatus().getSuccess(), "Unexpected success status in response.");

        for (int i = 0; i < expectedFiles.length; i++) {
            ConvertResponse response = responses.get(i + 1);
            assertEquals(ConvertResponse.PayloadCase.FILESTART, response.getPayloadCase(), "Expected file start response at index " + (i + 1));
            assertEquals(expectedFiles[i], response.getFileStart().getType(), "Unexpected file type at index " + (i + 1));
            // the mocks do not provide any file content (chunks)
        }
    }

    private static Status.Code statusCodeOf(Throwable error) {
        Status status = Status.fromThrowable(error);
        return status.getCode();
    }

    private static ConvertRequest info() {
        return info(ConvertOperation.OPERATION_SCHEMA_IMPORT);
    }

    private static ConvertRequest info(ConvertOperation operation) {
        return ConvertRequest.newBuilder()
                .setInfo(ConvertRequestInfo.newBuilder()
                        .setOperation(operation))
                .build();
    }

    private static ConvertRequest fileStart(Ili2gpkgFileType fileType) {
        return ConvertRequest.newBuilder()
                .setFileStart(Ili2gpkgFileStart.newBuilder()
                        .setType(fileType))
                .build();
    }

    private static ConvertRequest chunk(String content) {
        return ConvertRequest.newBuilder()
                .setChunk(ByteString.copyFromUtf8(content))
                .build();
    }
}
