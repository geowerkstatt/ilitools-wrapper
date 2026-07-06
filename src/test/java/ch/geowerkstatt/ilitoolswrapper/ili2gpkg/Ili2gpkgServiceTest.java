package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunnerMock;
import ch.geowerkstatt.ilitoolswrapper.files.InMemoryProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.files.InMemoryFileManager;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertOperation;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.StatusUpdate;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class Ili2gpkgServiceTest {
    private InMemoryFileManager fileManager;
    private IlitoolsRunnerMock ilitoolsRunner;
    private Ili2gpkgService service;
    private RecordingStreamObserver<StatusUpdate> responseObserver;

    @BeforeEach
    void setUp() {
        fileManager = new InMemoryFileManager();
        ilitoolsRunner = new IlitoolsRunnerMock();
        service = new Ili2gpkgService(fileManager, ilitoolsRunner);
        responseObserver = new RecordingStreamObserver<>();
    }

    @Test
    void convertReportsCompletionStatus() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("data"));
        InMemoryProcessingFile created = fileManager.lastCreatedFile();
        requestObserver.onCompleted();

        assertArrayEquals("data".getBytes(StandardCharsets.UTF_8), created.contents());
        assertTrue(created.isClosed(), "File should be closed once the stream completes.");
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
        assertEquals(2, fileManager.createdFiles().size(), "File manager should create uploaded and output files");

        assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), created.contents());
        assertTrue(created.isClosed(), "File should be closed once the stream completes.");
    }

    @Test
    void convertHandlesMultipleFiles() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.TRANSFER_FILE));
        requestObserver.onNext(chunk("<TRANSFER>"));
        requestObserver.onNext(chunk("</TRANSFER>"));
        InMemoryProcessingFile xtfFile = fileManager.lastCreatedFile();

        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("Hello World"));
        InMemoryProcessingFile txtFile = fileManager.lastCreatedFile();

        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        assertTrue(responseObserver.isCompleted());
        assertEquals(3, fileManager.createdFiles().size(), "File manager should create uploaded and output files");

        assertArrayEquals("<TRANSFER></TRANSFER>".getBytes(StandardCharsets.UTF_8), xtfFile.contents());
        assertTrue(xtfFile.isClosed(), "File should be closed once the stream completes.");

        assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), txtFile.contents());
        assertTrue(txtFile.isClosed(), "File should be closed once the stream completes.");
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
        List<String> args = ilitoolsRunner.lastArguments();
        assertNotNull(args, "The runner should have been invoked.");
        assertArgumentWithValue(args, "--models", "ModelA;ModelB");
        assertArgumentWithValue(args, "--defaultSrsCode", "2056");
        assertTrue(args.contains("--disableValidation"));
        assertTrue(args.contains("--createBasketCol"));
        assertTrue(args.contains("--sqlEnableNull"));
        assertTrue(args.contains("--skipReferenceErrors"));
        assertTrue(args.contains("--skipGeometryErrors"));
        assertTrue(args.contains("--importTid"));
        assertTrue(args.contains("--strokeArcs"));
    }

    @Test
    void convertOmitsDisabledInfoOptions() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart(Ili2gpkgFileType.MODEL_FILE));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        List<String> args = ilitoolsRunner.lastArguments();
        assertNotNull(args, "The runner should have been invoked.");
        assertFalse(args.contains("--models"));
        assertFalse(args.contains("--defaultSrsCode"));
        assertFalse(args.contains("--disableValidation"));
        assertFalse(args.contains("--createBasketCol"));
        assertFalse(args.contains("--sqlEnableNull"));
        assertFalse(args.contains("--skipReferenceErrors"));
        assertFalse(args.contains("--skipGeometryErrors"));
        assertFalse(args.contains("--importTid"));
        assertFalse(args.contains("--strokeArcs"));
    }

    @Test
    void chunkBeforeInfoIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(chunk("data"));

        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty());
    }

    @Test
    void fileStartBeforeInfoIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(fileStart(Ili2gpkgFileType.DB_FILE));

        assertEquals(Status.Code.INVALID_ARGUMENT, statusCodeOf(responseObserver.error()));
        assertTrue(fileManager.createdFiles().isEmpty());
    }

    @Test
    void duplicateInfoIsRejected() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(info());

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

    private static ConvertRequest info() {
        return ConvertRequest.newBuilder()
                .setInfo(ConvertRequestInfo.newBuilder()
                        .setOperation(ConvertOperation.OPERATION_SCHEMA_IMPORT))
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
