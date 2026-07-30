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
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.w3c.dom.Attr;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.diff.DefaultNodeMatcher;
import org.xmlunit.diff.Diff;
import org.xmlunit.diff.Difference;
import org.xmlunit.diff.ElementSelectors;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        } else {
            // Delete files in the output directory before running tests
            Files.walkFileTree(OUTPUT_DIR, new SimpleFileVisitor<>() {
                @Override
                @NonNull
                public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return super.visitFile(file, attrs);
                }
            });
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

        try (var connection = new GpkgConnection(response.outputFilePath)) {
            connection.assertHasTable("classa", Set.of("T_Id", "T_Ili_Tid", "aname", "T_basket"));
            connection.assertHasTable("apoint", Set.of("T_Id", "T_Ili_Tid", "ageometry", "T_basket"));
            connection.assertHasTable("gpkg_contents", Set.of("table_name", "data_type", "identifier", "description", "last_change", "min_x", "min_y", "max_x", "max_y", "srs_id"));
            connection.assertHasTable("gpkg_geometry_columns", Set.of("table_name", "column_name", "geometry_type_name", "srs_id", "z", "m"));
            connection.assertHasTable("gpkg_spatial_ref_sys", Set.of("srs_name", "srs_id", "organization", "organization_coordsys_id", "definition", "description"));

            connection.assertData("classa", "T_Id", List.of());
            connection.assertData("apoint", "T_Id", List.of());
            connection.assertData("gpkg_geometry_columns", "table_name", List.of(
                    Map.of("table_name", "apoint", "column_name", "ageometry", "geometry_type_name", "POINT", "srs_id", 2056, "z", 0, "m", 0)
            ));
        }
    }

    @Test
    public void testSchemaImportFailsWithInvalidModel() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_SCHEMA_IMPORT, info -> info.setCreateBasketCol(true)));
        writeResourceFile(call, Ili2gpkgFileType.MODEL_FILE, "ili2gpkg/model_invalid.ili");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.DB_FILE, "schema_import_invalid.gpkg");
        assertFalse(response.success, "Schema import should have failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");
        assertFalse(Files.exists(response.outputFilePath), "Failed schema import should not create a database");
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

        try (var connection = new GpkgConnection(response.outputFilePath)) {
            connection.assertHasTable("classa", Set.of("T_Id", "T_Ili_Tid", "aname", "T_basket"));
            connection.assertHasTable("apoint", Set.of("T_Id", "T_Ili_Tid", "ageometry", "T_basket"));

            connection.assertData("classa", "T_Id", List.of(
                    Map.of("T_Ili_Tid", "11111111-1111-4111-8111-111111111111", "aname", "Alpha"),
                    Map.of("T_Ili_Tid", "22222222-2222-4222-8222-222222222222", "aname", "Beta")
            ));
            connection.assertData("apoint", "T_Id", List.of(
                    Map.of("T_Ili_Tid", "33333333-3333-4333-8333-333333333333")
            ));
        }
    }

    @Test
    public void testImportFailsWithInvalidData() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_IMPORT, info -> info.setDataset(DATASET_NAME)));
        writeResourceFile(call, Ili2gpkgFileType.DB_FILE, "ili2gpkg/schema.gpkg");
        writeResourceFile(call, Ili2gpkgFileType.TRANSFER_FILE, "ili2gpkg/transfer_invalid.xtf");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.DB_FILE, "data_import_invalid.gpkg");
        assertFalse(response.success, "Import should have failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");
        assertFalse(Files.exists(response.outputFilePath), "Failed import should not create a database");
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

        try (InputStream expectedStream = getResourceStream("ili2gpkg/expected/export.xtf");
             InputStream actualStream = Files.newInputStream(response.outputFilePath)) {
            assertEqualXtfFiles(expectedStream, actualStream);
        }
    }

    @Test
    public void testExportFailsWithWrongModel() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_EXPORT, info -> info.addModels("WrongModel")));
        writeResourceFile(call, Ili2gpkgFileType.DB_FILE, "ili2gpkg/data.gpkg");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.TRANSFER_FILE, "data_export_invalid.xtf");
        assertFalse(response.success, "Export should have failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");
        assertFalse(Files.exists(response.outputFilePath), "Failed export should not create a transfer file");
    }

    @Test
    public void testUpdate() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_UPDATE, info -> {
            info.setDataset(DATASET_NAME);
            info.setDisableValidation(true); // allow import of data with a name that is too short for the constraint
        }));
        writeResourceFile(call, Ili2gpkgFileType.DB_FILE, "ili2gpkg/data.gpkg");
        writeResourceFile(call, Ili2gpkgFileType.TRANSFER_FILE, "ili2gpkg/transfer_invalid.xtf");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.DB_FILE, "data_update.gpkg");
        assertTrue(response.success, "Update failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");

        try (var connection = new GpkgConnection(response.outputFilePath)) {
            connection.assertHasTable("classa", Set.of("T_Id", "T_Ili_Tid", "aname", "T_basket"));
            connection.assertHasTable("apoint", Set.of("T_Id", "T_Ili_Tid", "ageometry", "T_basket"));

            connection.assertData("classa", "T_Id", List.of(
                    Map.of("T_Ili_Tid", "11111111-1111-4111-8111-111111111111", "aname", "Alpha"),
                    Map.of("T_Ili_Tid", "22222222-2222-4222-8222-222222222222", "aname", "B")
            ));
            connection.assertData("apoint", "T_Id", List.of(
                    Map.of("T_Ili_Tid", "33333333-3333-4333-8333-333333333333")
            ));
        }
    }

    @Test
    public void testUpdateFailsWithInvalidData() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_UPDATE, info -> info.setDataset(DATASET_NAME)));
        writeResourceFile(call, Ili2gpkgFileType.DB_FILE, "ili2gpkg/data.gpkg");
        writeResourceFile(call, Ili2gpkgFileType.TRANSFER_FILE, "ili2gpkg/transfer_invalid.xtf");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.DB_FILE, "data_update_invalid.gpkg");
        assertFalse(response.success, "Update should have failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");
        assertFalse(Files.exists(response.outputFilePath), "Failed update should not create a database");
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

    @Test
    public void testValidateFailsWithInvalidData() throws Exception {
        var client = Ili2gpkgServiceGrpc.newBlockingV2Stub(channel);
        var call = client.convert();

        call.write(info(ConvertOperation.OPERATION_VALIDATE, info -> info.addModels(MODEL_NAME)));
        writeResourceFile(call, Ili2gpkgFileType.DB_FILE, "ili2gpkg/data_invalid.gpkg");
        call.halfClose();

        Response response = readResponse(call, Ili2gpkgFileType.XTF_LOG_FILE, "log_failed.xtf");
        assertFalse(response.success, "Validation should have failed. Log:\n" + response.log);
        assertNotEquals("", response.log, "Log is empty");
        assertTrue(Files.exists(response.outputFilePath), "Failed validation should create an xtf log file");
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

        try (InputStream stream = getResourceStream(resourcePath)) {
            call.write(ConvertRequest.newBuilder()
                    .setChunk(ByteString.readFrom(stream, 32 * 1024))
                    .build());
        }
    }

    private static InputStream getResourceStream(String resourcePath) throws IOException {
        ClassLoader classLoader = Ili2gpkgIntegrationTest.class.getClassLoader();
        InputStream stream = classLoader.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        return stream;
    }

    private void assertEqualXtfFiles(InputStream expectedStream, InputStream actualStream) throws IOException {
        String expectedXml = new String(expectedStream.readAllBytes(), StandardCharsets.UTF_8);
        String actualXml = new String(actualStream.readAllBytes(), StandardCharsets.UTF_8);

        DefaultNodeMatcher nodeMatcher = new DefaultNodeMatcher(ElementSelectors.byName);
        Diff diff = DiffBuilder
                .compare(expectedXml)
                .withTest(actualXml)
                .checkForSimilar()
                .withNodeMatcher(nodeMatcher)
                .withAttributeFilter(Ili2gpkgIntegrationTest::filterAttributes)
                .ignoreWhitespace()
                .ignoreComments()
                .build();

        for (Difference difference : diff.getDifferences()) {
            System.out.println(difference);
        }

        assertFalse(diff.hasDifferences(), "Expected and actual XTF files should be equal.");
    }

    private static boolean filterAttributes(Attr attr) {
        // Ignore basket id
        return !(Objects.equals(attr.getLocalName(), "bid") && Objects.equals(attr.getNamespaceURI(), "http://www.interlis.ch/xtf/2.4/INTERLIS"));
    }
}
