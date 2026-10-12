package ch.geowerkstatt.ilitoolswrapper.xtfdiff;

import ch.geowerkstatt.ilitoolswrapper.IlitoolsWrapperServer;
import ch.geowerkstatt.ilitoolswrapper.files.FileManager;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFileSet;
import ch.geowerkstatt.ilitoolswrapper.healthcheck.ServiceHealthCheck;
import ch.geowerkstatt.ilitoolswrapper.modeldir.ModelDirValidator;
import ch.geowerkstatt.ilitoolswrapper.modeldir.PrivateNetworkPolicy;
import ch.geowerkstatt.ilitoolswrapper.modeldir.RepositoryCatalog;
import ch.geowerkstatt.ilitoolswrapper.proto.common.StatusUpdate;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.DiffRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.DiffRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.DiffResponse;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.XtfDiffFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.XtfDiffFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.xtfdiff.XtfDiffServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunner;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunner.Timeout;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class XtfDiffService extends XtfDiffServiceGrpc.XtfDiffServiceImplBase implements ServiceHealthCheck {
    // The tool hands --modeldir to the model manager as it is and expands no placeholder, so a request names
    // repositories only as URLs or offered repositories.
    private static final Set<String> MODEL_DIR_PLACEHOLDERS = Set.of();

    // The default of the tool itself.
    private static final List<String> DEFAULT_MODEL_DIRS = List.of("https://models.interlis.ch/");

    private static final Logger LOGGER = Logger.getLogger(XtfDiffService.class.getName());
    private final FileManager fileManager;
    private final IlitoolsRunner ilitoolsRunner;
    private final ModelDirValidator modelDirValidator;
    private final @Nullable Timeout toolTimeout;

    /**
     * Creates a new {@link XtfDiffService} with the specified file manager and tool runner.
     *
     * @param fileManager the FileManager to use for managing temporary files
     * @param ilitoolsRunner the IlitoolsRunner to use for running the XTF-Diff-Tool
     * @param privateNetworkPolicy whether model repository URLs may resolve into non-public address ranges
     * @param repositoryCatalog the model repositories this deployment offers as {@code %REPOSITORIES/<id>} entries
     * @param toolTimeout the timeout for the XTF-Diff-Tool process, or {@code null} to disable the timeout
     */
    public XtfDiffService(
            FileManager fileManager,
            IlitoolsRunner ilitoolsRunner,
            PrivateNetworkPolicy privateNetworkPolicy,
            RepositoryCatalog repositoryCatalog,
            @Nullable Timeout toolTimeout) {
        this.fileManager = fileManager;
        this.ilitoolsRunner = ilitoolsRunner;
        this.modelDirValidator = new ModelDirValidator(MODEL_DIR_PLACEHOLDERS, repositoryCatalog, privateNetworkPolicy, DEFAULT_MODEL_DIRS);
        this.toolTimeout = toolTimeout;
    }

    @Override
    public String getServiceName() {
        return XtfDiffServiceGrpc.SERVICE_NAME;
    }

    @Override
    public HealthCheckResponse.ServingStatus getHealthStatus() {
        try {
            Timeout timeout = new Timeout(5, TimeUnit.SECONDS);
            // The empty string probes the deployment default including its membership in the offered set, and every
            // offered version is probed as well, like the other services do.
            ilitoolsRunner.run(IlitoolsRunner.Tool.XTF_DIFF, "", List.of("--version"), timeout, false).get();
            for (String version : ilitoolsRunner.availableVersions(IlitoolsRunner.Tool.XTF_DIFF)) {
                ilitoolsRunner.run(IlitoolsRunner.Tool.XTF_DIFF, version, List.of("--version"), timeout, false).get();
            }
            return HealthCheckResponse.ServingStatus.SERVING;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HealthCheckResponse.ServingStatus.NOT_SERVING;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Health check failed: the XTF-Diff-Tool is not available.", e);
            return HealthCheckResponse.ServingStatus.NOT_SERVING;
        }
    }

    @Override
    public StreamObserver<DiffRequest> diff(StreamObserver<DiffResponse> responseObserver) {
        return new DiffObserver(responseObserver);
    }

    private final class DiffObserver implements StreamObserver<DiffRequest> {
        private final StreamObserver<DiffResponse> responseObserver;
        private final ProcessingFileSet<XtfDiffFileType> files = new ProcessingFileSet<>(fileManager);
        private @Nullable ProcessingFile currentFile;
        private @Nullable DiffRequestInfo info;
        private @Nullable CompletableFuture<Void> runFuture;
        private boolean cancelled;
        private String modelDirArgument = "";

        DiffObserver(StreamObserver<DiffResponse> responseObserver) {
            this.responseObserver = responseObserver;
            if (responseObserver instanceof ServerCallStreamObserver<DiffResponse> serverObserver) {
                serverObserver.setOnCancelHandler(this::onCancel);
            }
        }

        @Override
        public void onNext(DiffRequest value) {
            switch (value.getPayloadCase()) {
                case INFO -> onInfo(value.getInfo());
                case FILESTART -> onFileStart(value.getFileStart());
                case CHUNK -> onChunk(value.getChunk());
                default -> {
                    LOGGER.warning("Received request with no payload set.");
                    cancelWithError(Status.INVALID_ARGUMENT.withDescription("Invalid message type."));
                }
            }
        }

        private void onInfo(DiffRequestInfo info) {
            if (this.info != null) {
                LOGGER.warning("Duplicate info message received.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("Duplicate info message sent."));
                return;
            }

            // Rejected here rather than during argument mapping, so that no file is received for a request that cannot run.
            try {
                modelDirArgument = modelDirValidator.validateAndJoin(info.getModelDirsList());
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Rejected request options: " + e.getMessage());
                cancelWithError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
                return;
            }

            try {
                // The tool reads its models only from the model dirs, so the session needs no subfolders for them.
                files.setupSessionDirectory(List.of());
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to set up session directory.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to set up session directory."));
                return;
            }

            this.info = info;
            LOGGER.fine("Received info: " + info);
        }

        private void onFileStart(XtfDiffFileStart fileStart) {
            if (info == null) {
                LOGGER.warning("Received file start before info message.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("An info message must be sent before the files."));
                return;
            }
            XtfDiffFileType type = fileStart.getType();
            if (type != XtfDiffFileType.OLD_TRANSFER_FILE && type != XtfDiffFileType.NEW_TRANSFER_FILE) {
                LOGGER.warning("Received invalid file type.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("File has an invalid type."));
                return;
            }

            try {
                currentFile = files.create(type, "file" + (files.size() + 1), "xtf");
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Invalid argument: " + e.getMessage());
                cancelWithError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
            } catch (Exception e) {
                LOGGER.severe("Failed to open output file: " + e);
                cancelWithError(Status.ABORTED.withDescription("Failed to receive file data."));
            }
        }

        private void onChunk(ByteString chunk) {
            if (info == null) {
                LOGGER.warning("Received chunk before info message.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("An info message must be sent before the file content."));
                return;
            }
            if (currentFile == null) {
                LOGGER.warning("Received chunk before file start message.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("A file start message must be sent before the content."));
                return;
            }

            try {
                LOGGER.fine("Received chunk of size: " + chunk.size());
                chunk.writeTo(currentFile.outputStream());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to write chunk to file.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to receive file data."));
            }
        }

        @Override
        public void onError(Throwable t) {
            LOGGER.log(Level.WARNING, "Error in diff", t);
            files.deleteAll();
        }

        @Override
        public void onCompleted() {
            if (files.isEmpty()) {
                LOGGER.warning("No files were transferred, aborting diff.");
                cancelWithError(Status.ABORTED.withDescription("No files were transferred, aborting diff."));
                return;
            }

            if (!files.closeAll()) {
                LOGGER.warning("Failed to close output files, aborting diff.");
                cancelWithError(Status.ABORTED.withDescription("Failed to receive file data."));
                return;
            }

            try {
                LOGGER.fine("Comparing the transfer files with the XTF-Diff-Tool.");
                Optional<List<String>> parsedArguments = diffRequestToArguments();
                if (parsedArguments.isEmpty()) {
                    LOGGER.warning("Invalid input files for diff request.");
                    cancelWithError(Status.INVALID_ARGUMENT.withDescription("Exactly one old and one new transfer file are required for a diff."));
                    return;
                }

                if (cancelled) {
                    LOGGER.info("Diff was cancelled before the tool started, cleaning up session files.");
                    files.deleteAll();
                    return;
                }

                var runFuture = ilitoolsRunner.run(IlitoolsRunner.Tool.XTF_DIFF, "", parsedArguments.get(), toolTimeout, true);
                this.runFuture = runFuture;
                var _ = runFuture.handleAsync((_, throwable) -> {
                    if (runFuture.isCancelled()) {
                        LOGGER.info("Diff was cancelled by the client, cleaning up session files.");
                        files.deleteAll();
                        return null;
                    }
                    if (throwable != null) {
                        LOGGER.warning("Comparing the transfer files with the XTF-Diff-Tool failed: " + throwable);
                    }

                    boolean success = throwable == null;
                    returnResponse(success);
                    return null;
                });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to start the XTF-Diff-Tool process.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to start the XTF-Diff-Tool process."));
            }
        }

        private void onCancel() {
            cancelled = true;
            if (runFuture != null) {
                LOGGER.info("Client cancelled the diff, terminating the XTF-Diff-Tool process.");
                runFuture.cancel(true);
            }
        }

        private void cancelWithError(Status status) {
            // Deleting first makes the observable contract deterministic: when the client sees the error, the
            // session directory is gone. onError is an async handoff to the transport, so cleanup after it
            // races the client's next assertion.
            files.deleteAll();
            responseObserver.onError(status.asRuntimeException());
        }

        // One old and one new state are what make a diff request runnable. The tool takes them in that order, followed
        // by the file it writes the changes to.
        private Optional<List<String>> diffRequestToArguments() {
            Optional<ProcessingFile> oldFile = files.getSingle(XtfDiffFileType.OLD_TRANSFER_FILE);
            Optional<ProcessingFile> newFile = files.getSingle(XtfDiffFileType.NEW_TRANSFER_FILE);
            if (oldFile.isEmpty() || newFile.isEmpty()) {
                return Optional.empty();
            }

            ProcessingFile logFile = files.create(XtfDiffFileType.LOG_FILE, "log", "txt");
            ProcessingFile diffFile = files.create(XtfDiffFileType.DIFF_FILE, "diff", "json");
            return Optional.of(List.of(
                    "--logfile", absolutePath(logFile),
                    "--modeldir", modelDirArgument,
                    absolutePath(oldFile.get()),
                    absolutePath(newFile.get()),
                    absolutePath(diffFile)));
        }

        private static String absolutePath(ProcessingFile file) {
            return file.filePath().toAbsolutePath().toString();
        }

        private void returnResponse(boolean success) {
            try {
                responseObserver.onNext(createStatusResponse(success));
                returnFile(responseObserver, XtfDiffFileType.LOG_FILE);
                if (success) {
                    returnFile(responseObserver, XtfDiffFileType.DIFF_FILE);
                }
                responseObserver.onCompleted();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to return file data.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to return file data."));
            } finally {
                files.deleteAll();
            }
        }

        private static DiffResponse createStatusResponse(boolean success) {
            return DiffResponse.newBuilder()
                    .setStatus(StatusUpdate.newBuilder()
                            .setSuccess(success)
                            .build())
                    .build();
        }

        private void returnFile(StreamObserver<DiffResponse> responseObserver, XtfDiffFileType fileType) throws IOException {
            ProcessingFile file = files.getSingle(fileType)
                    .orElseThrow(() -> new IllegalStateException("Expected a single file of type " + fileType + " to return."));
            try (InputStream inputStream = file.inputStream()) {
                responseObserver.onNext(DiffResponse.newBuilder()
                        .setFileStart(XtfDiffFileStart.newBuilder()
                                .setType(fileType)
                                .build())
                        .build());

                byte[] buffer = new byte[IlitoolsWrapperServer.RESPONSE_CHUNK_SIZE];
                while (true) {
                    int bytesRead = inputStream.read(buffer);
                    if (bytesRead <= 0) {
                        break;
                    }
                    responseObserver.onNext(DiffResponse.newBuilder()
                            .setChunk(ByteString.copyFrom(buffer, 0, bytesRead))
                            .build());
                }
            }
        }
    }
}
