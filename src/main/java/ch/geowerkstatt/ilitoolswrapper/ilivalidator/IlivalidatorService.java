package ch.geowerkstatt.ilitoolswrapper.ilivalidator;

import ch.geowerkstatt.ilitoolswrapper.files.FileManager;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.healthcheck.ServiceHealthCheck;
import ch.geowerkstatt.ilitoolswrapper.proto.common.StatusUpdate;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.IlivalidatorServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ilivalidator.ValidateResponse;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunner;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.stub.StreamObserver;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class IlivalidatorService extends IlivalidatorServiceGrpc.IlivalidatorServiceImplBase implements ServiceHealthCheck {
    private static final Logger LOGGER = Logger.getLogger(IlivalidatorService.class.getName());
    private final FileManager fileManager;
    private final IlitoolsRunner ilitoolsRunner;

    /**
     * Creates a new {@link IlivalidatorService} with the specified file manager and tool runner.
     *
     * @param fileManager the FileManager to use for managing temporary files
     * @param ilitoolsRunner the IlitoolsRunner to use for running the ilivalidator tool
     */
    public IlivalidatorService(FileManager fileManager, IlitoolsRunner ilitoolsRunner) {
        this.fileManager = fileManager;
        this.ilitoolsRunner = ilitoolsRunner;
    }

    @Override
    public String getServiceName() {
        return IlivalidatorServiceGrpc.SERVICE_NAME;
    }

    @Override
    public HealthCheckResponse.ServingStatus getHealthStatus() {
        try {
            IlitoolsRunner.Timeout timeout = new IlitoolsRunner.Timeout(5, TimeUnit.SECONDS);
            ilitoolsRunner.run(IlitoolsRunner.Tool.ILIVALIDATOR, List.of("--version"), timeout).get();
            return HealthCheckResponse.ServingStatus.SERVING;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HealthCheckResponse.ServingStatus.NOT_SERVING;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Health check failed: ilivalidator is not available.", e);
            return HealthCheckResponse.ServingStatus.NOT_SERVING;
        }
    }

    @Override
    public StreamObserver<ValidateRequest> validate(StreamObserver<ValidateResponse> responseObserver) {
        return new ValidateObserver(responseObserver);
    }

    private final class ValidateObserver implements StreamObserver<ValidateRequest> {
        private final StreamObserver<ValidateResponse> responseObserver;
        private final UUID sessionId = UUID.randomUUID();
        private final Map<IlivalidatorFileType, List<ProcessingFile>> files = new HashMap<>();
        private @Nullable ProcessingFile currentFile;
        private @Nullable ValidateRequestInfo info;

        ValidateObserver(StreamObserver<ValidateResponse> responseObserver) {
            this.responseObserver = responseObserver;
        }

        @Override
        public void onNext(ValidateRequest value) {
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

        private void onInfo(ValidateRequestInfo info) {
            if (this.info != null) {
                LOGGER.warning("Duplicate info message received.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("Duplicate info message sent."));
                return;
            }

            this.info = info;
            LOGGER.fine("Received info: " + info);
        }

        private void onFileStart(IlivalidatorFileStart fileStart) {
            if (info == null) {
                LOGGER.warning("Received file start before info message.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("An info message must be sent before the files."));
                return;
            }
            IlivalidatorFileType type = fileStart.getType();
            String extension = switch (type) {
                case TRANSFER_FILE -> "xtf";
                default -> null;
            };
            if (extension == null) {
                LOGGER.warning("Received invalid file type.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("File has an invalid type."));
                return;
            }

            try {
                int fileNumber = files.size() + 1;
                currentFile = createProcessingFile(type, "file" + fileNumber, extension);
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
            LOGGER.log(Level.WARNING, "Error in validate", t);
            deleteFiles();
        }

        @Override
        public void onCompleted() {
            if (files.isEmpty()) {
                LOGGER.warning("No files were transferred, aborting validation.");
                cancelWithError(Status.ABORTED.withDescription("No files were transferred, aborting validation."));
                return;
            }

            if (!closeFiles()) {
                LOGGER.warning("Failed to close output files, aborting validation.");
                cancelWithError(Status.ABORTED.withDescription("Failed to receive file data."));
                return;
            }

            try {
                LOGGER.fine("Validating data with ilivalidator.");
                Optional<List<String>> parsedArguments = validateRequestToArguments();
                if (parsedArguments.isEmpty()) {
                    LOGGER.warning("Invalid input files for validate request.");
                    cancelWithError(Status.INVALID_ARGUMENT.withDescription("Exactly one transfer file is required for validation."));
                    return;
                }

                var _ = ilitoolsRunner.run(IlitoolsRunner.Tool.ILIVALIDATOR, parsedArguments.get(), null)
                        .handleAsync((_, throwable) -> {
                            if (throwable != null) {
                                LOGGER.warning("Validating data with ilivalidator failed: " + throwable);
                            }

                            boolean success = throwable == null;
                            returnResponse(success);
                            return null;
                        });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to start ilivalidator process.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to start ilivalidator process."));
            }
        }

        private ProcessingFile createProcessingFile(IlivalidatorFileType fileType, String fileName, String extension) {
            ProcessingFile file = fileManager.createProcessingFile(sessionId.toString(), fileName, extension);
            files.computeIfAbsent(fileType, _ -> new ArrayList<>()).add(file);
            return file;
        }

        private void cancelWithError(Status status) {
            responseObserver.onError(status.asRuntimeException());
            deleteFiles();
        }

        private void deleteFiles() {
            closeFiles();
            try {
                fileManager.deleteProcessingFiles(sessionId.toString());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to delete processing files.", e);
            }
            files.clear();
        }

        private boolean closeFiles() {
            boolean success = true;
            for (List<ProcessingFile> files : files.values()) {
                for (ProcessingFile file : files) {
                    try {
                        file.close();
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to close processing file.", e);
                        success = false;
                    }
                }
            }
            return success;
        }

        private Optional<List<String>> validateRequestToArguments() {
            Optional<ProcessingFile> transferFile = getSingleFile(IlivalidatorFileType.TRANSFER_FILE);
            if (transferFile.isEmpty()) {
                return Optional.empty();
            }

            List<String> args = new ArrayList<>();

            ProcessingFile logFile = createProcessingFile(IlivalidatorFileType.LOG_FILE, "log", "txt");
            addArgument(args, "--log", logFile.filePath().toAbsolutePath().toString());

            ProcessingFile xtfLogFile = createProcessingFile(IlivalidatorFileType.XTF_LOG_FILE, "log", "xtf");
            addArgument(args, "--xtflog", xtfLogFile.filePath().toAbsolutePath().toString());

            ValidateRequestInfo requestInfo = Objects.requireNonNull(info);
            addFlag(args, "--forceTypeValidation", requestInfo.getForceTypeValidation());
            addFlag(args, "--disableAreaValidation", requestInfo.getDisableAreaValidation());
            addFlag(args, "--disableConstraintValidation", requestInfo.getDisableConstraintValidation());
            addFlag(args, "--allObjectsAccessible", requestInfo.getAllObjectsAccessible());
            addFlag(args, "--multiplicityOff", requestInfo.getMultiplicityOff());
            addFlag(args, "--skipPolygonBuilding", requestInfo.getSkipPolygonBuilding());

            args.add(transferFile.get().filePath().toAbsolutePath().toString());
            return Optional.of(args);
        }

        private Optional<ProcessingFile> getSingleFile(IlivalidatorFileType fileType) {
            List<ProcessingFile> filesOfType = files.get(fileType);
            if (filesOfType == null || filesOfType.size() != 1) {
                return Optional.empty();
            }
            return Optional.of(filesOfType.getFirst());
        }

        private static void addFlag(List<String> args, String flag, boolean enabled) {
            if (enabled) {
                args.add(flag);
            }
        }

        private static void addArgument(List<String> args, String argument, @Nullable String value) {
            if (value != null && !value.isEmpty()) {
                args.add(argument);
                args.add(value);
            }
        }

        private void returnResponse(boolean success) {
            try {
                responseObserver.onNext(createStatusResponse(success));
                returnFile(responseObserver, IlivalidatorFileType.LOG_FILE);
                returnFile(responseObserver, IlivalidatorFileType.XTF_LOG_FILE);
                responseObserver.onCompleted();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to return file data.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to return file data."));
            } finally {
                deleteFiles();
            }
        }

        private static ValidateResponse createStatusResponse(boolean success) {
            return ValidateResponse.newBuilder()
                    .setStatus(StatusUpdate.newBuilder()
                            .setSuccess(success)
                            .build())
                    .build();
        }

        private void returnFile(StreamObserver<ValidateResponse> responseObserver, IlivalidatorFileType fileType) throws IOException {
            ProcessingFile file = getSingleFile(fileType)
                    .orElseThrow(() -> new IllegalStateException("Expected a single file of type " + fileType + " to return."));
            try (InputStream inputStream = file.inputStream()) {
                responseObserver.onNext(ValidateResponse.newBuilder()
                        .setFileStart(IlivalidatorFileStart.newBuilder()
                                .setType(fileType)
                                .build())
                        .build());

                byte[] buffer = new byte[10 * 1024 * 1024];
                while (true) {
                    int bytesRead = inputStream.read(buffer);
                    if (bytesRead <= 0) {
                        break;
                    }
                    responseObserver.onNext(ValidateResponse.newBuilder()
                            .setChunk(ByteString.copyFrom(buffer, 0, bytesRead))
                            .build());
                }
            }
        }
    }
}
