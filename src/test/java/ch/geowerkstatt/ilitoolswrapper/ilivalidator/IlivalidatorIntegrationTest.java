package ch.geowerkstatt.ilitoolswrapper.ilivalidator;

import ch.geowerkstatt.ilitoolswrapper.IlitoolsIntegrationTestBase;
import ch.geowerkstatt.ilitoolswrapper.IntegrationTestSupport;
import ch.geowerkstatt.ilitoolswrapper.files.FilesystemFileManager;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateResponse;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsProcessRunner;
import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.StatusException;
import io.grpc.stub.BlockingClientCall;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        return new IlivalidatorService(fileManager, new IlitoolsProcessRunner());
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

    private static ValidateRequest info() {
        return ValidateRequest.newBuilder()
                .setInfo(ValidateRequestInfo.newBuilder())
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
