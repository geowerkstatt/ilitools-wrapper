package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import ch.geowerkstatt.ilitoolswrapper.IlitoolsWrapperServer;
import ch.geowerkstatt.ilitoolswrapper.files.FilesystemFileManager;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertOperation;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertResponse;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsProcessRunner;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusException;
import io.grpc.stub.BlockingClientCall;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
public final class Ili2gpkgIntegrationTest {
    private record Response(boolean success, String log, Path outputFilePath) { }

    private static final int PORT = 5678;
    private static final Path OUTPUT_DIR = Path.of("test-out", "ili2gpkg");
    private static final String DATASET_NAME = "TestDataset";
    private static final String MODEL_NAME = "SimpleModel";
    private static IlitoolsWrapperServer server;
    private static ManagedChannel channel;

    @BeforeAll
    static void setup() throws IOException {
        var fileManager = new FilesystemFileManager();
        var ilitoolsRunner = new IlitoolsProcessRunner();
        var ili2gpkgService = new Ili2gpkgService(fileManager, ilitoolsRunner);
        server = new IlitoolsWrapperServer(PORT, ili2gpkgService);
        server.start();

        channel = ManagedChannelBuilder
                .forAddress("localhost", PORT)
                .usePlaintext()
                .build();
        if (!Files.exists(OUTPUT_DIR)) {
            Files.createDirectories(OUTPUT_DIR);
        }
    }

    @AfterAll
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    static void teardown() throws InterruptedException {
        channel.shutdownNow();
        server.stop();
    }

    @Test
    public void testSchemaImport() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_SCHEMA_IMPORT, info -> info.setCreateBasketCol(true)));
        writeResourceFile(call, Ili2gpkgFileType.MODEL_FILE, "ili2gpkg/model.ili");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.DB_FILE, "schema_import.gpkg");
        assertTrue(response.success, "Schema import failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");
    }

    @Test
    public void testImport() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_IMPORT, info -> info.setDataset(DATASET_NAME)));
        writeResourceFile(call, Ili2gpkgFileType.DB_FILE, "ili2gpkg/schema.gpkg");
        writeResourceFile(call, Ili2gpkgFileType.TRANSFER_FILE, "ili2gpkg/transfer.xtf");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.DB_FILE, "data_import.gpkg");
        assertTrue(response.success, "Import failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");
    }

    @Test
    public void testExport() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_EXPORT, info -> info.addModels(MODEL_NAME)));
        writeResourceFile(call, Ili2gpkgFileType.DB_FILE, "ili2gpkg/data.gpkg");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.TRANSFER_FILE, "data_export.xtf");
        assertTrue(response.success, "Export failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");
    }

    @Test
    public void testUpdate() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_UPDATE, info -> info.setDataset(DATASET_NAME)));
        writeResourceFile(call, Ili2gpkgFileType.DB_FILE, "ili2gpkg/data.gpkg");
        writeResourceFile(call, Ili2gpkgFileType.TRANSFER_FILE, "ili2gpkg/transfer.xtf");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.DB_FILE, "data_update.gpkg");
        assertTrue(response.success, "Update failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");
    }

    @Test
    public void testValidate() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_VALIDATE, info -> info.addModels(MODEL_NAME)));
        writeResourceFile(call, Ili2gpkgFileType.DB_FILE, "ili2gpkg/data.gpkg");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.XTF_LOG_FILE, "log.xtf");
        assertTrue(response.success, "Validation failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");
    }

    private static Response readResponse(
            BlockingClientCall<ConvertRequest, ConvertResponse> call,
            Ili2gpkgFileType outputFileType,
            String outputFileName) throws StatusException, InterruptedException, IOException {
        boolean success = false;
        StringBuilder logBuilder = new StringBuilder();
        Ili2gpkgFileStart currentFile = null;
        OutputStream outputFileStream = null;
        Path outputFilePath = OUTPUT_DIR.resolve(outputFileName);

        try {
            ConvertResponse response;
            while (true) {
                response = call.read();
                if (response == null) {
                    break;
                }

                switch (response.getPayloadCase()) {
                    case STATUS -> success = response.getStatus().getSuccess();
                    case FILESTART -> currentFile = response.getFileStart();
                    case CHUNK -> {
                        if (currentFile != null) {
                            ByteString chunkData = response.getChunk();
                            if (currentFile.getType() == Ili2gpkgFileType.LOG_FILE) {
                                logBuilder.append(chunkData.toStringUtf8());
                            } else if (currentFile.getType() == outputFileType) {
                                if (outputFileStream == null) {
                                    outputFileStream = Files.newOutputStream(outputFilePath);
                                }
                                chunkData.writeTo(outputFileStream);
                            } else {
                                Assertions.fail("Unexpected file type in response: " + currentFile.getType());
                            }
                        } else {
                            Assertions.fail("Received chunk without a corresponding file start");
                        }
                    }
                    default -> Assertions.fail("Unexpected response with no payload");
                }
            }
        } finally {
            if (outputFileStream != null) {
                outputFileStream.close();
            }
        }

        return new Response(success, logBuilder.toString(), outputFilePath);
    }

    private static ConvertRequest info(ConvertOperation operation, Consumer<ConvertRequestInfo.Builder> configureInfo) {
        ConvertRequestInfo.Builder infoBuilder = ConvertRequestInfo.newBuilder()
                .setOperation(operation);
        configureInfo.accept(infoBuilder);

        return ConvertRequest.newBuilder()
                .setInfo(infoBuilder)
                .build();
    }

    private static void writeResourceFile(
            BlockingClientCall<ConvertRequest, ConvertResponse> call,
            Ili2gpkgFileType fileType,
            String resourcePath) throws StatusException, InterruptedException, IOException {
        call.write(ConvertRequest.newBuilder()
                .setFileStart(Ili2gpkgFileStart.newBuilder()
                        .setType(fileType))
                .build());

        ClassLoader classLoader = Ili2gpkgIntegrationTest.class.getClassLoader();
        try (InputStream stream = classLoader.getResourceAsStream(resourcePath)) {
            call.write(ConvertRequest.newBuilder()
                    .setChunk(ByteString.readFrom(stream, 32 * 1024))
                    .build());
        }
    }
}
