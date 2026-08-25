package ch.geowerkstatt.ilitoolswrapper.ilivalidator;

import ch.geowerkstatt.ilitoolswrapper.IlitoolsIntegrationTestBase;
import ch.geowerkstatt.ilitoolswrapper.IntegrationTestSupport;
import ch.geowerkstatt.ilitoolswrapper.files.FilesystemFileManager;
import ch.geowerkstatt.ilitoolswrapper.modeldir.PrivateNetworkPolicy;
import ch.geowerkstatt.ilitoolswrapper.plugins.PluginCatalog;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    public IlivalidatorIntegrationTest() {
        super(PORT, OUTPUT_DIR);
    }

    @Override
    protected BindableService createService() {
        // The repository of the meta config test is served from localhost, so non-public addresses must be allowed.
        // The plugin catalog is the one the build packs the minimal test plugin into, see the testPluginJar task.
        String catalog = Objects.requireNonNull(System.getenv("TEST_PLUGIN_CATALOG"), "The test task must set TEST_PLUGIN_CATALOG.");
        return new IlivalidatorService(new FilesystemFileManager(), new IlitoolsProcessRunner(), PrivateNetworkPolicy.ALLOW, new PluginCatalog(Path.of(catalog)));
    }

    @Test
    public void testValidateSucceedsWithValidData() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        call.write(info(info -> info.addModelDirs("%ITF_DIR/models")));
        writeTransferFileAndModel(call, "ilivalidator/transfer.xtf");
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

        call.write(info(info -> info.addModelDirs("%ITF_DIR/models")));
        writeTransferFileAndModel(call, "ilivalidator/transfer_invalid.xtf");
        call.halfClose();

        ValidationResult result = readResponse(call, "invalid_log.xtf");
        assertFalse(result.success, "Validation should have failed for data violating a constraint. Log:\n" + result.log);
        assertEquals(List.of(IlivalidatorFileType.LOG_FILE, IlivalidatorFileType.XTF_LOG_FILE), result.returnedFiles,
                "Both logs should be returned even when validation fails.");
        assertTrue(result.log.contains("NameMinLength"), "Text log should mention the violated constraint. Log:\n" + result.log);
        assertXtfLog(result.xtfLogPath);
    }

    @Test
    public void testValidateRootDirectoryDoesNotSeeDeliveredModels() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        // Delivered models live in the models subfolder and the tool does not scan a directory recursively, so
        // the root entry %ITF_DIR must not see them. This pins the separation the subfolder layout exists for.
        call.write(info(info -> info.addModelDirs("%ITF_DIR")));
        writeTransferFileAndModel(call, "ilivalidator/transfer.xtf");
        call.halfClose();

        ValidationResult result = readResponse(call, "itf_dir_log.xtf");
        assertFalse(result.success, "The root directory must not resolve models of the models subfolder. Log:\n" + result.log);
        assertTrue(result.log.contains("SimpleModel"), "Text log should name the unresolvable model. Log:\n" + result.log);
    }

    @Test
    public void testValidateResolvesImportBetweenDeliveredModels() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        // A delivered model may import another delivered model: all model files land in the same models subfolder,
        // which the tool scans as one repository, so the import resolves without any remote repository.
        call.write(info(info -> info.addModelDirs("%ITF_DIR/models")));
        writeResourceFile(call, IlivalidatorFileType.TRANSFER_FILE_XTF, "ilivalidator/transfer_imports_base.xtf");
        writeResourceFile(call, IlivalidatorFileType.MODEL_FILE, "ilivalidator/model_imports_base.ili");
        writeResourceFile(call, IlivalidatorFileType.MODEL_FILE, "ilivalidator/model_base.ili");
        call.halfClose();

        ValidationResult result = readResponse(call, "imports_log.xtf");
        assertTrue(result.success, "The import between the delivered models should resolve from the session directory. Log:\n" + result.log);
        assertTrue(result.log.contains("validation done"), "Text log should report a successful validation. Log:\n" + result.log);
    }

    @Test
    public void testValidateFailsWithoutDeliveredModel() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        // The regression scenario of geopilot#683: the models entry is the only source and no model is delivered,
        // so its folder does not even exist. The tool cannot resolve the model, which is a validation result, not
        // an RPC error.
        call.write(info(info -> info.addModelDirs("%ITF_DIR/models")));
        writeResourceFile(call, IlivalidatorFileType.TRANSFER_FILE_XTF, "ilivalidator/transfer.xtf");
        call.halfClose();

        ValidationResult result = readResponse(call, "missing_model_log.xtf");
        assertFalse(result.success, "Validation should fail when the model cannot be resolved. Log:\n" + result.log);
        assertTrue(result.log.contains("SimpleModel"), "Text log should name the unresolvable model. Log:\n" + result.log);
    }

    @Test
    public void testValidatePluginFunctionIsSkippedWithoutThePlugin() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        // Measured against ilivalidator 1.15.0: a mandatory constraint whose function has no implementation is
        // skipped with a warning and the run still reports success. A missing plugin is therefore invisible in the
        // success flag, which is what makes the inverted pair below the only proof that --plugins took effect.
        call.write(info(info -> info.addModelDirs("%ITF_DIR/models")));
        writeResourceFile(call, IlivalidatorFileType.TRANSFER_FILE_XTF, "ilivalidator/transfer_plugin_function.xtf");
        writeResourceFile(call, IlivalidatorFileType.MODEL_FILE, "ilivalidator/model_plugin_function.ili");
        writeResourceFile(call, IlivalidatorFileType.MODEL_FILE, "ilivalidator/TestFunctions.ili");
        call.halfClose();

        ValidationResult result = readResponse(call, "plugin_absent_log.xtf");
        assertTrue(result.success, "Without the plugin the constraint is skipped, so the run reports success. Log:\n" + result.log);
        assertTrue(result.log.contains("is not yet implemented"), "Text log should report the skipped constraint. Log:\n" + result.log);
    }

    @Test
    public void testValidatePluginFunctionFailsWithThePlugin() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        // Same data and models as above, only the plugin selection differs. The plugin function always returns
        // false, so the constraint is evaluated and violated: the inverted success flag proves the plugin ran.
        call.write(info(info -> info.addModelDirs("%ITF_DIR/models").addPlugins("test-functions")));
        writeResourceFile(call, IlivalidatorFileType.TRANSFER_FILE_XTF, "ilivalidator/transfer_plugin_function.xtf");
        writeResourceFile(call, IlivalidatorFileType.MODEL_FILE, "ilivalidator/model_plugin_function.ili");
        writeResourceFile(call, IlivalidatorFileType.MODEL_FILE, "ilivalidator/TestFunctions.ili");
        call.halfClose();

        ValidationResult result = readResponse(call, "plugin_present_log.xtf");
        assertFalse(result.success, "With the plugin the constraint is evaluated and fails. Log:\n" + result.log);
        assertFalse(result.log.contains("is not yet implemented"), "The constraint must be evaluated, not skipped. Log:\n" + result.log);
    }

    @Test
    public void testValidateAppliesProfileFromModelRepository() throws Exception {
        try (LocalRepositoryServer repository = LocalRepositoryServer.start()) {
            var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
            var call = client.validate();

            call.write(info(info -> info
                    .addModelDirs("%ITF_DIR/models")
                    .addModelDirs(repository.baseUrl())
                    .setMetaConfig("ilidata:TEST-PROFILE")));
            writeTransferFileAndModel(call, "ilivalidator/transfer_invalid.xtf");
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

        // The repository travels in the request instead of being fetched, %ITF_DIR/repository points at the
        // subfolder the wrapper extracts it into. This is the parity case for the environments that mount a
        // local repository.
        call.write(info(info -> info
                .addModelDirs("%ITF_DIR/models")
                .addModelDirs("%ITF_DIR/repository")
                .setMetaConfig("ilidata:TEST-PROFILE")));
        writeTransferFileAndModel(call, "ilivalidator/transfer_invalid.xtf");
        writeRepositoryArchive(call, repositoryArchive());
        call.halfClose();

        ValidationResult result = readResponse(call, "inline_profile_log.xtf");
        assertTrue(result.success, "The profile of the inline repository disables constraint validation. Log:\n" + result.log);
        assertFalse(result.log.contains("NameMinLength"), "The disabled constraint should not be reported. Log:\n" + result.log);
        assertXtfLog(result.xtfLogPath);
    }

    @Test
    public void testValidateItfAgainstDeliveredInterlis1Model() throws Exception {
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        // The original use case of geopilot#683: an INTERLIS 1 delivery brings its own model. The ITF transfer file
        // type is what switches the tool to INTERLIS 1 semantics; the fixture reuses a TID across two tables, which
        // is legal in ITF but fails under an xtf name, so this test pins that the type reaches the tool.
        // The model is sent before the transfer file to pin that the file order does not matter.
        call.write(info(info -> info.addModelDirs("%ITF_DIR/models")));
        writeResourceFile(call, IlivalidatorFileType.MODEL_FILE, "ilivalidator/model_interlis1.ili");
        writeResourceFile(call, IlivalidatorFileType.TRANSFER_FILE_ITF, "ilivalidator/transfer_interlis1.itf");
        call.halfClose();

        ValidationResult result = readResponse(call, "interlis1_log.xtf");
        assertTrue(result.success, "The ITF should validate against the delivered INTERLIS 1 model. Log:\n" + result.log);
        assertTrue(result.log.contains("validation done"), "Text log should report a successful validation. Log:\n" + result.log);
    }

    @Test
    public void testValidateModelDirOrderRanksRepositoryAgainstDeliveredModels() throws Exception {
        // The repository archive and the delivered models land in separate subfolders, so their rank is pure
        // configuration: the archive carries the strict SimpleModel, the delivery brings a lax variant without
        // the constraint, and the data violates exactly that constraint.
        byte[] archive = archiveOf(Map.of("model.ili", resourceAsString("ilivalidator/model.ili")));

        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var repositoryFirst = client.validate();
        repositoryFirst.write(info(info -> info
                .addModelDirs("%ITF_DIR/repository")
                .addModelDirs("%ITF_DIR/models")));
        writeResourceFile(repositoryFirst, IlivalidatorFileType.TRANSFER_FILE_XTF, "ilivalidator/transfer_invalid.xtf");
        writeResourceFile(repositoryFirst, IlivalidatorFileType.MODEL_FILE, "ilivalidator/model_lax.ili");
        writeRepositoryArchive(repositoryFirst, archive);
        repositoryFirst.halfClose();

        ValidationResult strictResult = readResponse(repositoryFirst, "precedence_repository_log.xtf");
        assertFalse(strictResult.success, "With the repository first its strict model must win. Log:\n" + strictResult.log);
        assertTrue(strictResult.log.contains("NameMinLength"), "The strict constraint should be reported. Log:\n" + strictResult.log);

        var modelsFirst = client.validate();
        modelsFirst.write(info(info -> info
                .addModelDirs("%ITF_DIR/models")
                .addModelDirs("%ITF_DIR/repository")));
        writeResourceFile(modelsFirst, IlivalidatorFileType.TRANSFER_FILE_XTF, "ilivalidator/transfer_invalid.xtf");
        writeResourceFile(modelsFirst, IlivalidatorFileType.MODEL_FILE, "ilivalidator/model_lax.ili");
        writeRepositoryArchive(modelsFirst, archive);
        modelsFirst.halfClose();

        ValidationResult laxResult = readResponse(modelsFirst, "precedence_models_log.xtf");
        assertTrue(laxResult.success, "With the delivered models first their lax model must win. Log:\n" + laxResult.log);
    }

    @Test
    public void testValidateRejectsArchiveWithPathTraversal() throws Exception {
        Set<String> sessionsBefore = processingSessions();
        var client = IlivalidatorServiceGrpc.newBlockingV2Stub(channel);
        var call = client.validate();

        call.write(info(info -> info.addModelDirs("%ITF_DIR")));
        writeResourceFile(call, IlivalidatorFileType.TRANSFER_FILE_XTF, "ilivalidator/transfer.xtf");
        writeRepositoryArchive(call, archiveOf(Map.of("../escaped.xml", "gotcha")));
        call.halfClose();

        StatusException exception = assertThrows(StatusException.class, () -> readResponse(call, "traversal_log.xtf"));
        assertEquals(Status.Code.INVALID_ARGUMENT, exception.getStatus().getCode(), "A traversing entry must be rejected as an invalid argument.");

        Set<String> leftOver = new HashSet<>(processingSessions());
        leftOver.removeAll(sessionsBefore);
        assertTrue(leftOver.isEmpty(), "The session directory must be removed after a rejected archive, but found " + leftOver);
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
     * Returns the names of the session directories below the processing directory, which defaults to
     * {@code processing} when {@code PROCESSING_DIR} is not set. Compared as two snapshots, so a session of an
     * earlier run does not disturb the assertion. It would disturb it if tests ever ran in parallel, because a
     * session of another test could be alive during the second snapshot; this project runs them sequentially
     * (no junit-platform.properties, no maxParallelForks).
     */
    private static Set<String> processingSessions() throws IOException {
        Path processingDir = Path.of("processing");
        if (!Files.isDirectory(processingDir)) {
            return Set.of();
        }

        try (var sessions = Files.list(processingDir)) {
            return Set.copyOf(sessions.map(path -> path.getFileName().toString()).toList());
        }
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
            IlivalidatorFileType fileType,
            String resourcePath) throws StatusException, InterruptedException, IOException {
        writeResourceFile(
                call,
                ValidateRequest.newBuilder()
                        .setFileStart(IlivalidatorFileStart.newBuilder()
                                .setType(fileType))
                        .build(),
                chunk -> ValidateRequest.newBuilder()
                        .setChunk(chunk)
                        .build(),
                resourcePath);
    }

    /**
     * Sends the given transfer file resource followed by the SimpleModel model file, the pairing every
     * validation of the regular test data needs since the model is resolved from the transfer file's directory.
     */
    private static void writeTransferFileAndModel(
            BlockingClientCall<ValidateRequest, ValidateResponse> call,
            String transferResourcePath) throws StatusException, InterruptedException, IOException {
        writeResourceFile(call, IlivalidatorFileType.TRANSFER_FILE_XTF, transferResourcePath);
        writeResourceFile(call, IlivalidatorFileType.MODEL_FILE, "ilivalidator/model.ili");
    }

    private static void assertXtfLog(Path xtfLogPath) throws IOException {
        assertTrue(Files.exists(xtfLogPath), "An XTF log file should have been written.");
        String content = Files.readString(xtfLogPath, StandardCharsets.UTF_8);
        assertTrue(content.contains("IliVErrors"), "The XTF log should be an INTERLIS error log. Content:\n" + content);
    }
}
