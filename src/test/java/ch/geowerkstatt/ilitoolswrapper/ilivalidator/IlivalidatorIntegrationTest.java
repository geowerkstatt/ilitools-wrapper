package ch.geowerkstatt.ilitoolswrapper.ilivalidator;

import ch.geowerkstatt.ilitoolswrapper.IlitoolsWrapperServer;
import ch.geowerkstatt.ilitoolswrapper.files.FilesystemFileManager;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateResponse;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public final class IlivalidatorIntegrationTest {
    private record ValidationResult(boolean success, String log, Path xtfLogPath, List<IlivalidatorFileType> returnedFiles) { }

    private static final int PORT = 5679;
    private static final Path OUTPUT_DIR = Path.of("test-out", "ilivalidator");
    private static final String MODEL_FILE_NAME = "SimpleModel";
    private static IlitoolsWrapperServer server;
    private static ManagedChannel channel;

    @BeforeAll
    static void setup() throws IOException {
        byte[] model = getResourceBytes("ilivalidator/model.ili");
        var fileManager = new ModelSeedingFileManager(new FilesystemFileManager(), MODEL_FILE_NAME, model);
        var ilitoolsRunner = new IlitoolsProcessRunner();
        var ilivalidatorService = new IlivalidatorService(fileManager, ilitoolsRunner);
        server = new IlitoolsWrapperServer(PORT, ilivalidatorService);
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
        call.write(ValidateRequest.newBuilder()
                .setFileStart(IlivalidatorFileStart.newBuilder()
                        .setType(IlivalidatorFileType.TRANSFER_FILE))
                .build());

        try (InputStream stream = getResourceStream(resourcePath)) {
            // send in small chunks to test file streaming
            byte[] buffer = new byte[32 * 1024];
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) > 0) {
                call.write(ValidateRequest.newBuilder()
                        .setChunk(ByteString.copyFrom(buffer, 0, bytesRead))
                        .build());
            }
        }
    }

    private static void assertXtfLog(Path xtfLogPath) throws IOException {
        assertTrue(Files.exists(xtfLogPath), "An XTF log file should have been written.");
        String content = Files.readString(xtfLogPath, StandardCharsets.UTF_8);
        assertTrue(content.contains("IliVErrors"), "The XTF log should be an INTERLIS error log. Content:\n" + content);
    }

    private static byte[] getResourceBytes(String resourcePath) throws IOException {
        try (InputStream stream = getResourceStream(resourcePath)) {
            return stream.readAllBytes();
        }
    }

    private static InputStream getResourceStream(String resourcePath) throws IOException {
        ClassLoader classLoader = IlivalidatorIntegrationTest.class.getClassLoader();
        InputStream stream = classLoader.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        return stream;
    }
}
