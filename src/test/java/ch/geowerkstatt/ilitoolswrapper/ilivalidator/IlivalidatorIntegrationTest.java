package ch.geowerkstatt.ilitoolswrapper.ilivalidator;

import ch.geowerkstatt.ilitoolswrapper.IlitoolsIntegrationTestBase;
import ch.geowerkstatt.ilitoolswrapper.IntegrationTestSupport;
import ch.geowerkstatt.ilitoolswrapper.files.FilesystemFileManager;
import ch.geowerkstatt.ilitoolswrapper.modeldir.PrivateNetworkPolicy;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateResponse;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsProcessRunner;
import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.BlockingClientCall;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public final class IlivalidatorIntegrationTest extends IlitoolsIntegrationTestBase {
    private record ValidationResult(boolean success, String log, Path xtfLogPath, List<IlivalidatorFileType> returnedFiles) { }

    private static final int PORT = 5679;
    private static final Path OUTPUT_DIR = Path.of("test-out", "ilivalidator");
    private static final String MODEL_FILE_NAME = "SimpleModel";

    public IlivalidatorIntegrationTest() {
        super(PORT, OUTPUT_DIR);
    }

    @Override
    protected BindableService createService() throws IOException {
        byte[] model = IntegrationTestSupport.getResourceBytes("ilivalidator/model.ili");
        var fileManager = new ModelSeedingFileManager(new FilesystemFileManager(), MODEL_FILE_NAME, model);
        // The repository of the meta config test is served from localhost, so non-public addresses must be allowed.
        return new IlivalidatorService(fileManager, new IlitoolsProcessRunner(), PrivateNetworkPolicy.ALLOW);
    }

    @Test
    public void testValidateSucceedsWithValidData() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        call.write(info());
        writeResourceFile(call, "ilivalidator/transfer.xtf");
        call.halfClose();

        ValidationResult result = readResponse(call, "valid_log.xtf");
        assertTrue(result.success, "Validation should have succeeded. Log:\n" + result.log);
        assertEquals(List.of(IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE), result.returnedFiles,
                "The text log and the XTF log should be returned, in that order.");
        assertTrue(result.log.contains("validation done"), "Text log should report a successful validation. Log:\n" + result.log);
        assertXtfLog(result.xtfLogPath);
    }

    @Test
    public void testValidateFailsWithInvalidData() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        call.write(info());
        writeResourceFile(call, "ilivalidator/transfer_invalid.xtf");
        call.halfClose();

        ValidationResult result = readResponse(call, "invalid_log.xtf");
        assertFalse(result.success, "Validation should have failed for data violating a constraint. Log:\n" + result.log);
        assertEquals(List.of(IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE), result.returnedFiles,
                "Both logs should be returned even when validation fails.");
        assertTrue(result.log.contains("NameMinLength"), "Text log should mention the violated constraint. Log:\n" + result.log);
        assertXtfLog(result.xtfLogPath);
    }

    @Test
    public void testValidateResolvesModelFromTransferFileDirectory() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        // %ITF_DIR replaces the tool default, so the model next to the transfer file is the only source left.
        call.write(info(info -> info.addModelDirs("%ITF_DIR")));
        writeResourceFile(call, "ilivalidator/transfer.xtf");
        call.halfClose();

        ValidationResult result = readResponse(call, "itf_dir_log.xtf");
        assertTrue(result.success, "Validation with %ITF_DIR as only model dir should have succeeded. Log:\n" + result.log);
        assertTrue(result.log.contains("validation done"), "Text log should report a successful validation. Log:\n" + result.log);
    }

    @Test
    public void testValidateAppliesProfileFromModelRepository() throws Exception {
        try (LocalRepositoryServer repository = LocalRepositoryServer.start()) {
            var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
            var call = client.validate();

            call.write(info(info -> info
                    .addModelDirs("%ITF_DIR")
                    .addModelDirs(repository.baseUrl())
                    .setMetaConfig("ilidata:TEST-PROFILE")));
            writeResourceFile(call, "ilivalidator/transfer_invalid.xtf");
            call.halfClose();

            ValidationResult result = readResponse(call, "profile_log.xtf");
            assertTrue(result.success, "The profile disables constraint validation, so the invalid data should pass. Log:\n" + result.log);
            assertFalse(result.log.contains("NameMinLength"), "The disabled constraint should not be reported. Log:\n" + result.log);
            assertXtfLog(result.xtfLogPath);

            List<String> requestedPaths = repository.requestedPaths();
            assertTrue(requestedPaths.contains("/ilidata.xml"), "The tool should have read the repository index. Requested: " + requestedPaths);
            assertTrue(requestedPaths.contains("/test_profile.toml"), "The tool should have read the profile of the repository. Requested: " + requestedPaths);
        }
    }

    private static ValidationResult readResponse(
            BlockingClientCall<ValidateRequest, ValidateResponse> call,
            String xtfLogFileName) throws StatusException, InterruptedException, IOException {
        boolean success = false;
        StringBuilder logBuilder = new StringBuilder();
        List<IlivalidatorFileType> returnedFiles = new ArrayList<>();
        IlivalidatorFileType currentFileType = null;
        Path xtfLogPath = OUTPUT_DIR.resolve(xtfLogFileName);
        OutputStream xtfLogStream = null;

        try {
            ValidateResponse response;
            while (true) {
                response = call.read();
                if (response == null) {
                    break;
                }

                switch (response.getPayloadCase()) {
                    case STATUS -> success = response.getStatus().getSuccess();
                    case FILESTART -> {
                        currentFileType = response.getFileStart().getType();
                        returnedFiles.add(currentFileType);
                    }
                    case CHUNK -> {
                        ByteString chunkData = response.getChunk();
                        if (currentFileType == IlivalidatorFileType.LOG_FILE) {
                            logBuilder.append(chunkData.toStringUtf8());
                        } else if (currentFileType == IlivalidatorFileType.XTF_LOG_FILE) {
                            if (xtfLogStream == null) {
                                xtfLogStream = Files.newOutputStream(xtfLogPath);
                            }
                            chunkData.writeTo(xtfLogStream);
                        } else {
                            Assertions.fail("Received a chunk before a corresponding file start.");
                        }
                    }
                    default -> Assertions.fail("Unexpected response with no payload.");
                }
            }
        } finally {
            if (xtfLogStream != null) {
                xtfLogStream.close();
            }
        }

        return new ValidationResult(success, logBuilder.toString(), xtfLogPath, returnedFiles);
    }

    @Test
    public void testValidateAppliesProfileFromInlineRepository() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        // The repository travels in the request instead of being fetched, %ITF_DIR points at the session directory
        // the wrapper extracts it into. This is the parity case for the environments that mount a local repository.
        call.write(info(info -> info
                .addModelDirs("%ITF_DIR")
                .setMetaConfig("ilidata:TEST-PROFILE")));
        writeResourceFile(call, "ilivalidator/transfer_invalid.xtf");
        writeRepositoryArchive(call, repositoryArchive());
        call.halfClose();

        ValidationResult result = readResponse(call, "inline_profile_log.xtf");
        assertTrue(result.success, "The profile of the inline repository disables constraint validation. Log:\n" + result.log);
        assertFalse(result.log.contains("NameMinLength"), "The disabled constraint should not be reported. Log:\n" + result.log);
        assertXtfLog(result.xtfLogPath);
    }

    @Test
    public void testValidateRejectsArchiveWithPathTraversal() throws Exception {
        long sessionsBefore = countProcessingSessions();
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        call.write(info(info -> info.addModelDirs("%ITF_DIR")));
        writeResourceFile(call, "ilivalidator/transfer.xtf");
        writeRepositoryArchive(call, archiveOf(Map.of("../escaped.xml", "gotcha")));
        call.halfClose();

        StatusException exception = assertThrows(StatusException.class, () -> readResponse(call, "traversal_log.xtf"));
        assertEquals(Status.Code.INVALID_ARGUMENT, exception.getStatus().getCode(), "A traversing entry must be rejected as an invalid argument.");
        assertEquals(sessionsBefore, countProcessingSessions(), "The session directory must be removed after a rejected archive.");
    }

    private static byte[] repositoryArchive() throws IOException {
        return archiveOf(Map.of(
                "ilidata.xml", resourceAsString("ilivalidator/repository/ilidata.xml"),
                "test_profile.toml", resourceAsString("ilivalidator/repository/test_profile.toml")));
    }

    private static String resourceAsString(String resourcePath) throws IOException {
        return new String(IntegrationTestSupport.getResourceBytes(resourcePath), StandardCharsets.UTF_8);
    }

    private static byte[] archiveOf(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zipStream = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zipStream.putNextEntry(new ZipEntry(entry.getKey()));
                zipStream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zipStream.closeEntry();
            }
        }
        return buffer.toByteArray();
    }

    private static void writeRepositoryArchive(
            BlockingClientCall<ValidateRequest, ValidateResponse> call,
            byte[] archive) throws StatusException, InterruptedException {
        call.write(ValidateRequest.newBuilder()
                .setFileStart(IlivalidatorFileStart.newBuilder()
                        .setType(IlivalidatorFileType.REPOSITORY_ARCHIVE))
                .build());
        call.write(ValidateRequest.newBuilder()
                .setChunk(ByteString.copyFrom(archive))
                .build());
    }

    /**
     * Counts the session directories below the processing directory, which defaults to {@code processing} when
     * {@code PROCESSING_DIR} is not set.
     */
    private static long countProcessingSessions() throws IOException {
        Path processingDir = Path.of("processing");
        if (!Files.isDirectory(processingDir)) {
            return 0;
        }

        try (var sessions = Files.list(processingDir)) {
            return sessions.count();
        }
    }

    private static ValidateRequest info() {
        return ValidateRequest.newBuilder()
                .setInfo(ValidateRequestInfo.newBuilder())
                .build();
    }

    private static ValidateRequest info(Consumer<ValidateRequestInfo.Builder> configureInfo) {
        ValidateRequestInfo.Builder infoBuilder = ValidateRequestInfo.newBuilder();
        configureInfo.accept(infoBuilder);

        return ValidateRequest.newBuilder()
                .setInfo(infoBuilder)
                .build();
    }

    private static void writeResourceFile(
            BlockingClientCall<ValidateRequest, ValidateResponse> call,
            String resourcePath) throws StatusException, InterruptedException, IOException {
        writeResourceFile(
                call,
                ValidateRequest.newBuilder()
                        .setFileStart(IlivalidatorFileStart.newBuilder()
                                .setType(IlivalidatorFileType.TRANSFER_FILE))
                        .build(),
                chunk -> ValidateRequest.newBuilder()
                        .setChunk(chunk)
                        .build(),
                resourcePath);
    }

    private static void assertXtfLog(Path xtfLogPath) throws IOException {
        assertTrue(Files.exists(xtfLogPath), "An XTF log file should have been written.");
        String content = Files.readString(xtfLogPath, StandardCharsets.UTF_8);
        assertTrue(content.contains("IliVErrors"), "The XTF log should be an INTERLIS error log. Content:\n" + content);
    }
}
