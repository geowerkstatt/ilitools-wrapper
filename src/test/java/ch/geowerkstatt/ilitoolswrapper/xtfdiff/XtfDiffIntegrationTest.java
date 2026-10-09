package ch.geowerkstatt.ilitoolswrapper.xtfdiff;

import ch.geowerkstatt.ilitoolswrapper.IlitoolsIntegrationTestBase;
import ch.geowerkstatt.ilitoolswrapper.files.FilesystemFileManager;
import ch.geowerkstatt.ilitoolswrapper.modeldir.PrivateNetworkPolicy;
import ch.geowerkstatt.ilitoolswrapper.modeldir.RepositoryCatalog;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.DiffRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.DiffRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.DiffResponse;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.XtfDiffFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.XtfDiffFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.XtfDiffServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsProcessRunner;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunner;
import io.grpc.BindableService;
import io.grpc.StatusException;
import io.grpc.stub.BlockingClientCall;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real XTF-Diff-Tool. The transfer files and their model come from the integration test data of the
 * XTF-Diff-Tool; the model lies in the folder {@code repository}, which the service offers as {@code %REPOSITORIES/repository}.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public final class XtfDiffIntegrationTest extends IlitoolsIntegrationTestBase {
    private record DiffResult(boolean success, String log, String diff, List<XtfDiffFileType> returnedFiles) { }

    private static final int PORT = 5680;
    private static final Path OUTPUT_DIR = Path.of("test-out", "xtfdiff");
    private static final Path REPOSITORY_ROOT = Path.of("src", "test", "resources", "xtfdiff");

    private static final String FIRST_STATE = "xtfdiff/DiffToolTest1_2.4.xtf";
    private static final String SECOND_STATE = "xtfdiff/DiffToolTest2_2.4.xtf";
    private static final String FIRST_STATE_INTERLIS_2_3 = "xtfdiff/DiffToolTest1_2.3.xtf";

    // An object of class B that only the first state contains.
    private static final String OBJECT_ONLY_IN_FIRST_STATE = "978edfe5-0d40-4985-8ecb-f11154b8d2e6";

    public XtfDiffIntegrationTest() {
        super(PORT, OUTPUT_DIR);
    }

    @Override
    protected BindableService createService() {
        return new XtfDiffService(
                new FilesystemFileManager(),
                new IlitoolsProcessRunner(),
                PrivateNetworkPolicy.ALLOW,
                new RepositoryCatalog(REPOSITORY_ROOT),
                new IlitoolsRunner.Timeout(60, TimeUnit.SECONDS));
    }

    @Test
    public void testDiffReportsTheChangesFromTheOldToTheNewState() throws Exception {
        DiffResult result = diff(FIRST_STATE, SECOND_STATE, "old_to_new.json");

        assertTrue(result.success, "The diff should have succeeded. Log:\n" + result.log);
        assertEquals(List.of(XtfDiffFileType.LOG_FILE, XtfDiffFileType.DIFF_FILE), result.returnedFiles,
                "The log and the diff should be returned, in that order.");
        assertObjectChange(result, OBJECT_ONLY_IN_FIRST_STATE, "deleted");
    }

    @Test
    public void testDiffInTheOtherDirectionReportsTheObjectAsAdded() throws Exception {
        DiffResult result = diff(SECOND_STATE, FIRST_STATE, "new_to_old.json");

        assertTrue(result.success, "The diff should have succeeded. Log:\n" + result.log);
        assertObjectChange(result, OBJECT_ONLY_IN_FIRST_STATE, "added");
    }

    @Test
    public void testDiffOfDifferentInterlisVersionsReturnsTheLogAlone() throws Exception {
        DiffResult result = diff(FIRST_STATE_INTERLIS_2_3, SECOND_STATE, "different_versions.json");

        assertFalse(result.success, "Two states of different INTERLIS versions cannot be compared. Log:\n" + result.log);
        assertEquals(List.of(XtfDiffFileType.LOG_FILE), result.returnedFiles, "Without a diff only the log should be returned.");
        assertTrue(result.log.contains("different INTERLIS versions"), "The log should say why the tool failed. Log:\n" + result.log);
    }

    private DiffResult diff(String oldState, String newState, String diffFileName) throws StatusException, InterruptedException, IOException {
        var client = XtfDiffServiceGrpc.newBlockingV2Stub(channel);
        var call = client.diff();

        call.write(DiffRequest.newBuilder()
                .setInfo(DiffRequestInfo.newBuilder()
                        .addModelDirs("%REPOSITORIES/repository"))
                .build());
        writeResourceFile(call, XtfDiffFileType.OLD_TRANSFER_FILE, oldState);
        writeResourceFile(call, XtfDiffFileType.NEW_TRANSFER_FILE, newState);
        call.halfClose();

        return readResponse(call, diffFileName);
    }

    private static void writeResourceFile(
            BlockingClientCall<DiffRequest, DiffResponse> call,
            XtfDiffFileType fileType,
            String resourcePath) throws StatusException, InterruptedException, IOException {
        writeResourceFile(
                call,
                DiffRequest.newBuilder()
                        .setFileStart(XtfDiffFileStart.newBuilder()
                                .setType(fileType))
                        .build(),
                chunk -> DiffRequest.newBuilder()
                        .setChunk(chunk)
                        .build(),
                resourcePath);
    }

    private static DiffResult readResponse(
            BlockingClientCall<DiffRequest, DiffResponse> call,
            String diffFileName) throws StatusException, InterruptedException, IOException {
        boolean success = false;
        StringBuilder logBuilder = new StringBuilder();
        StringBuilder diffBuilder = new StringBuilder();
        List<XtfDiffFileType> returnedFiles = new ArrayList<>();
        XtfDiffFileType currentFileType = null;

        while (true) {
            DiffResponse response = call.read();
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
                    if (currentFileType == XtfDiffFileType.LOG_FILE) {
                        logBuilder.append(response.getChunk().toStringUtf8());
                    } else if (currentFileType == XtfDiffFileType.DIFF_FILE) {
                        diffBuilder.append(response.getChunk().toStringUtf8());
                    } else {
                        Assertions.fail("Received a chunk before a corresponding file start.");
                    }
                }
                default -> Assertions.fail("Unexpected response with no payload.");
            }
        }

        // Kept for inspection when a test fails.
        Files.writeString(OUTPUT_DIR.resolve(diffFileName), diffBuilder);
        return new DiffResult(success, logBuilder.toString(), diffBuilder.toString(), returnedFiles);
    }

    /**
     * Asserts that the diff reports the object itself, not one of its attributes, with the given change type. The tool
     * writes the properties of a change in a fixed order, so the three of them follow each other.
     */
    private static void assertObjectChange(DiffResult result, String oid, String changeType) {
        Pattern change = Pattern.compile("\"oid\"\\s*:\\s*\"" + Pattern.quote(oid) + "\"\\s*,\\s*"
                + "\"changeType\"\\s*:\\s*\"" + changeType + "\"\\s*,\\s*"
                + "\"valueType\"\\s*:\\s*\"object\"");
        assertTrue(change.matcher(result.diff).find(), "The diff should report object " + oid + " as " + changeType + ". Diff:\n" + result.diff);
    }
}
