package ch.geowerkstatt.ilitoolswrapper.ilivalidator;

import ch.geowerkstatt.ilitoolswrapper.files.FileManager;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFileSet;
import ch.geowerkstatt.ilitoolswrapper.healthcheck.ServiceHealthCheck;
import ch.geowerkstatt.ilitoolswrapper.modeldir.ModelDirValidator;
import ch.geowerkstatt.ilitoolswrapper.modeldir.PrivateNetworkPolicy;
import ch.geowerkstatt.ilitoolswrapper.modeldir.RepositoryArchiveExtractor;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class IlivalidatorService extends IlivalidatorServiceGrpc.IlivalidatorServiceImplBase implements ServiceHealthCheck {
    // %ITF_DIR is the directory of the transfer file, which is the session directory of the request.
    private static final Set<String> MODEL_DIR_PLACEHOLDERS = Set.of("%ITF_DIR");

    // Session subfolder for received MODEL_FILEs, addressed as %ITF_DIR/models in the model dirs.
    private static final String MODEL_FILES_SUBFOLDER = "models";

    private static final Logger LOGGER = Logger.getLogger(IlivalidatorService.class.getName());
    private static final RepositoryArchiveExtractor REPOSITORY_ARCHIVE_EXTRACTOR = new RepositoryArchiveExtractor();
    private final FileManager fileManager;
    private final IlitoolsRunner ilitoolsRunner;
    private final ModelDirValidator modelDirValidator;

    /**
     * Creates a new {@link IlivalidatorService} with the specified file manager and tool runner.
     *
     * @param fileManager the FileManager to use for managing temporary files
     * @param ilitoolsRunner the IlitoolsRunner to use for running the ilivalidator tool
     * @param privateNetworkPolicy whether model repository URLs may resolve into non-public address ranges
     */
    public IlivalidatorService(FileManager fileManager, IlitoolsRunner ilitoolsRunner, PrivateNetworkPolicy privateNetworkPolicy) {
        this.fileManager = fileManager;
        this.ilitoolsRunner = ilitoolsRunner;
        this.modelDirValidator = new ModelDirValidator(MODEL_DIR_PLACEHOLDERS, privateNetworkPolicy);
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
        private final ProcessingFileSet<IlivalidatorFileType> files = new ProcessingFileSet<>(fileManager);
        private @Nullable ProcessingFile currentFile;
        private @Nullable ValidateRequestInfo info;
        private String modelDirArgument = "";

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

            // Rejected here rather than during argument mapping, so that no file is received for a request that cannot run.
            try {
                modelDirArgument = modelDirValidator.validateAndJoin(info.getModelDirsList());
                ModelDirValidator.validateMetaConfig(info.getMetaConfig());
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Rejected model repository options: " + e.getMessage());
                cancelWithError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
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
            String requestedExtension = fileStart.getFileExtension();
            if (!requestedExtension.isEmpty()) {
                // The tool derives its INTERLIS 1 semantics from the extension of the transfer file (measured:
                // per-table TIDs of an ITF are rejected under an .xtf name), so the client may declare it. All
                // other received files keep their wrapper-assigned name, which is what keeps this channel safe.
                if (type != IlivalidatorFileType.TRANSFER_FILE) {
                    LOGGER.warning("Received a file extension on a non transfer file.");
                    cancelWithError(Status.INVALID_ARGUMENT.withDescription("Only the transfer file can declare a file extension."));
                    return;
                }
                if (!requestedExtension.equals("xtf") && !requestedExtension.equals("itf")) {
                    LOGGER.warning("Received invalid transfer file extension.");
                    cancelWithError(Status.INVALID_ARGUMENT.withDescription("The transfer file extension must be \"xtf\" or \"itf\"."));
                    return;
                }
            }
            String extension = switch (type) {
                case TRANSFER_FILE -> requestedExtension.isEmpty() ? "xtf" : requestedExtension;
                case REPOSITORY_ARCHIVE -> "zip";
                case MODEL_FILE -> "ili";
                default -> null;
            };
            if (extension == null) {
                LOGGER.warning("Received invalid file type.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("File has an invalid type."));
                return;
            }

            try {
                int fileNumber = files.size() + 1;
                String fileName = "file" + fileNumber;
                // Model files get their own subfolder, so a model dir entry can address exactly them
                // (%ITF_DIR/models) and rank them against the other sources.
                currentFile = type == IlivalidatorFileType.MODEL_FILE
                        ? files.create(type, MODEL_FILES_SUBFOLDER, fileName, extension)
                        : files.create(type, fileName, extension);
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
            files.deleteAll();
        }

        @Override
        public void onCompleted() {
            if (files.isEmpty()) {
                LOGGER.warning("No files were transferred, aborting validation.");
                cancelWithError(Status.ABORTED.withDescription("No files were transferred, aborting validation."));
                return;
            }

            if (!files.closeAll()) {
                LOGGER.warning("Failed to close output files, aborting validation.");
                cancelWithError(Status.ABORTED.withDescription("Failed to receive file data."));
                return;
            }

            if (!extractRepositoryArchive()) {
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

        private void cancelWithError(Status status) {
            responseObserver.onError(status.asRuntimeException());
            files.deleteAll();
        }

        // Runs after the received files are closed and before the log files exist. The archive lands in its own
        // subfolder, so its entries cannot collide with any other file of the session. Returns false when the
        // request was cancelled.
        private boolean extractRepositoryArchive() {
            try {
                REPOSITORY_ARCHIVE_EXTRACTOR.extractReceived(files.getAll(IlivalidatorFileType.REPOSITORY_ARCHIVE));
                return true;
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Rejected repository archive: " + e.getMessage());
                cancelWithError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
                return false;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to extract the repository archive.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to extract the repository archive."));
                return false;
            }
        }

        // Exactly one transfer file is what makes a validate request runnable, so the optional of the file set carries
        // straight through to the optional of the arguments.
        private Optional<List<String>> validateRequestToArguments() {
            return files.getSingle(IlivalidatorFileType.TRANSFER_FILE).map(this::buildValidateArguments);
        }

        private List<String> buildValidateArguments(ProcessingFile transferFile) {
            List<String> args = new ArrayList<>();

            ProcessingFile logFile = files.create(IlivalidatorFileType.LOG_FILE, "log", "txt");
            addArgument(args, "--log", logFile.filePath().toAbsolutePath().toString());

            ProcessingFile xtfLogFile = files.create(IlivalidatorFileType.XTF_LOG_FILE, "log", "xtf");
            addArgument(args, "--xtflog", xtfLogFile.filePath().toAbsolutePath().toString());

            ValidateRequestInfo requestInfo = Objects.requireNonNull(info);
            addFlag(args, "--forceTypeValidation", requestInfo.getForceTypeValidation());
            addFlag(args, "--disableAreaValidation", requestInfo.getDisableAreaValidation());
            addFlag(args, "--disableConstraintValidation", requestInfo.getDisableConstraintValidation());
            addFlag(args, "--allObjectsAccessible", requestInfo.getAllObjectsAccessible());
            addFlag(args, "--multiplicityOff", requestInfo.getMultiplicityOff());
            addFlag(args, "--skipPolygonBuilding", requestInfo.getSkipPolygonBuilding());

            addArgument(args, "--modeldir", modelDirArgument);
            addArgument(args, "--metaConfig", requestInfo.getMetaConfig());

            args.add(transferFile.filePath().toAbsolutePath().toString());
            return args;
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
                files.deleteAll();
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
            ProcessingFile file = files.getSingle(fileType)
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
