package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import ch.geowerkstatt.ilitoolswrapper.files.InMemoryChunkedFile;
import ch.geowerkstatt.ilitoolswrapper.files.InMemoryFileManager;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertOperation;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.FileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.StatusUpdate;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class Ili2gpkgServiceTest {
    private InMemoryFileManager fileManager;
    private Ili2gpkgService service;
    private RecordingStreamObserver<StatusUpdate> responseObserver;

    @BeforeEach
    void setUp() {
        fileManager = new InMemoryFileManager();
        service = new Ili2gpkgService(fileManager);
        responseObserver = new RecordingStreamObserver<>();
    }

    @Test
    void convertReportsCompletionStatus() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart("xtf"));
        requestObserver.onNext(chunk("data"));
        requestObserver.onCompleted();

        assertEquals(1, responseObserver.values().size());
        assertTrue(responseObserver.isCompleted());

        InMemoryChunkedFile created = fileManager.lastCreatedFile();
        assertArrayEquals("data".getBytes(StandardCharsets.UTF_8), created.contents());
        assertTrue(created.isClosed(), "File should be closed once the stream completes.");
        assertTrue(created.isDeleted(), "File should be deleted after processing.");
    }

    @Test
    void convertAssemblesReceivedChunks() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart("xtf"));
        requestObserver.onNext(chunk("<TRANSFER>"));
        requestObserver.onNext(chunk("<DATASECTION/>"));
        requestObserver.onNext(chunk("</TRANSFER>"));
        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        assertTrue(responseObserver.isCompleted());
        assertEquals(1, fileManager.createdFiles().size());

        InMemoryChunkedFile created = fileManager.lastCreatedFile();
        assertArrayEquals("<TRANSFER><DATASECTION/></TRANSFER>".getBytes(StandardCharsets.UTF_8), created.contents());
        assertTrue(created.isClosed(), "File should be closed once the stream completes.");
    }

    @Test
    void convertHandlesMultipleFiles() {
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart("xtf"));
        requestObserver.onNext(chunk("<TRANSFER>"));
        requestObserver.onNext(chunk("</TRANSFER>"));
        InMemoryChunkedFile xtfFile = fileManager.lastCreatedFile();

        requestObserver.onNext(fileStart("txt"));
        requestObserver.onNext(chunk("Hello World"));
        InMemoryChunkedFile txtFile = fileManager.lastCreatedFile();

        requestObserver.onCompleted();

        assertNull(responseObserver.error());
        assertTrue(responseObserver.isCompleted());
        assertEquals(2, fileManager.createdFiles().size());

        assertArrayEquals("<TRANSFER></TRANSFER>".getBytes(StandardCharsets.UTF_8), xtfFile.contents());
        assertTrue(xtfFile.isClosed(), "File should be closed once the stream completes.");

        assertArrayEquals("Hello World".getBytes(StandardCharsets.UTF_8), txtFile.contents());
        assertTrue(txtFile.isClosed(), "File should be closed once the stream completes.");
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

        requestObserver.onNext(fileStart("xtf"));

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
        assertInstanceOf(IllegalStateException.class, responseObserver.error());
    }

    @Test
    void fileManagerFailureIsPropagated() {
        fileManager.failNextCreationWith(new IllegalStateException("disk full"));
        StreamObserver<ConvertRequest> requestObserver = service.convert(responseObserver);

        requestObserver.onNext(info());
        requestObserver.onNext(fileStart("xtf"));

        assertNotNull(responseObserver.error());
        assertTrue(fileManager.createdFiles().isEmpty());
    }

    private static Status.Code statusCodeOf(Throwable error) {
        Status status = Status.fromThrowable(error);
        return status.getCode();
    }

    private static ConvertRequest info() {
        return ConvertRequest.newBuilder()
                .setInfo(ConvertRequestInfo.newBuilder()
                        .setOperation(ConvertOperation.OPERATION_IMPORT))
                .build();
    }

    private static ConvertRequest fileStart(String fileExtension) {
        return ConvertRequest.newBuilder()
                .setFileStart(FileStart.newBuilder()
                        .setFileExtension(fileExtension))
                .build();
    }

    private static ConvertRequest chunk(String content) {
        return ConvertRequest.newBuilder()
                .setChunk(ByteString.copyFromUtf8(content))
                .build();
    }
}
