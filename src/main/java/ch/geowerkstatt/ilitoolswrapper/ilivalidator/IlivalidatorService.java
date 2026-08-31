package ch.geowerkstatt.ilitoolswrapper.ilivalidator;

import ch.geowerkstatt.ilitoolswrapper.files.FileManager;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFileSet;
import ch.geowerkstatt.ilitoolswrapper.healthcheck.ServiceHealthCheck;
import ch.geowerkstatt.ilitoolswrapper.modeldir.ModelDirValidator;
import ch.geowerkstatt.ilitoolswrapper.modeldir.PrivateNetworkPolicy;
import ch.geowerkstatt.ilitoolswrapper.modeldir.RepositoryArchiveExtractor;
import ch.geowerkstatt.ilitoolswrapper.plugins.PluginCatalog;
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
import java.io.UncheckedIOException;
import java.nio.file.Path;
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

    // Session subfolders that can be configured as model dirs.
    private static final List<String> SESSION_SUBFOLDERS = List.of(MODEL_FILES_SUBFOLDER, RepositoryArchiveExtractor.REPOSITORY_SUBFOLDER);

    // Keeps only the official model repository of the tool default "%ITF_DIR;http://models.interlis.ch/;%JAR_DIR/ilimodels"
    // as the %ITF_DIR does not contain model files and %JAR_DIR/ilimodels does not exist and upgrades the URL to HTTPS.
    private static final List<String> DEFAULT_MODEL_DIRS = List.of("https://models.interlis.ch/");

    private static final Logger LOGGER = Logger.getLogger(IlivalidatorService.class.getName());
    private static final RepositoryArchiveExtractor REPOSITORY_ARCHIVE_EXTRACTOR = new RepositoryArchiveExtractor();
    private final FileManager fileManager;
    private final IlitoolsRunner ilitoolsRunner;
    private final ModelDirValidator modelDirValidator;
    private final PluginCatalog pluginCatalog;

    /**
     * Creates a new {@link IlivalidatorService} with the specified file manager and tool runner.
     *
     * @param fileManager the FileManager to use for managing temporary files
     * @param ilitoolsRunner the IlitoolsRunner to use for running the ilivalidator tool
     * @param privateNetworkPolicy whether model repository URLs may resolve into non-public address ranges
     * @param pluginCatalog the ilivalidator plugins this deployment offers for a request to select
     */
    public IlivalidatorService(FileManager fileManager, IlitoolsRunner ilitoolsRunner, PrivateNetworkPolicy privateNetworkPolicy, PluginCatalog pluginCatalog) {
        this.fileManager = fileManager;
        this.ilitoolsRunner = ilitoolsRunner;
        this.modelDirValidator = new ModelDirValidator(MODEL_DIR_PLACEHOLDERS, privateNetworkPolicy, DEFAULT_MODEL_DIRS);
        this.pluginCatalog = pluginCatalog;
    }

    @Override
    public String getServiceName() {
        return IlivalidatorServiceGrpc.SERVICE_NAME;
    }

    @Override
    public HealthCheckResponse.ServingStatus getHealthStatus() {
        try {
            IlitoolsRunner.Timeout timeout = new IlitoolsRunner.Timeout(5, TimeUnit.SECONDS);
            // The empty string probes the deployment default including its membership in the offered set;
            // every offered version is probed as well, so a defective additional jar surfaces here instead
            // of masquerading as a failed validation of some client's data.
            ilitoolsRunner.run(IlitoolsRunner.Tool.ILIVALIDATOR, "", List.of("--version"), timeout).get();
            for (String version : ilitoolsRunner.availableVersions(IlitoolsRunner.Tool.ILIVALIDATOR)) {
                ilitoolsRunner.run(IlitoolsRunner.Tool.ILIVALIDATOR, version, List.of("--version"), timeout).get();
            }
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
        private Set<String> requestedPlugins = Set.of();
        private String requestedToolVersion = "";

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
            // The plugin ids are resolved against the catalog on every request, so a plugin added to the configured
            // directory is selectable without restarting the service. The tool version is matched against the
            // versions the image ships.
            try {
                modelDirArgument = modelDirValidator.validateAndJoin(info.getModelDirsList());
                ModelDirValidator.validateMetaConfig(info.getMetaConfig());
                requestedPlugins = pluginCatalog.validate(info.getPluginIdsList());
                requestedToolVersion = validateToolVersion(info.getToolVersion());
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Rejected request options: " + e.getMessage());
                cancelWithError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
                return;
            } catch (IllegalStateException e) {
                LOGGER.log(Level.SEVERE, "Cannot serve the request.", e);
                cancelWithError(Status.ABORTED.withDescription(e.getMessage()));
                return;
            }

            try {
                files.setupSessionDirectory(SESSION_SUBFOLDERS);
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to set up session directory.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to set up session directory."));
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
            // The transfer file type carries the format, because the tool switches to its INTERLIS 1 semantics
            // only for an .itf name (measured: per-table TIDs of an ITF are rejected under an .xtf name).
            String extension = switch (type) {
                case TRANSFER_FILE_XTF -> "xtf";
                case TRANSFER_FILE_ITF -> "itf";
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

                var _ = ilitoolsRunner.run(IlitoolsRunner.Tool.ILIVALIDATOR, requestedToolVersion, parsedArguments.get(), null)
                        .handleAsync((_, throwable) -> {
                            if (throwable != null) {
                                LOGGER.warning("Validating data with ilivalidator failed: " + throwable);
                            }

                            boolean success = throwable == null;
                            returnResponse(success);
                            return null;
                        });
            } catch (IllegalArgumentException e) {
                // Reaches here from the plugin materialization, and from the runner's version backstop in case
                // the offered set ever diverged between the onInfo validation and the start of the tool.
                LOGGER.warning("Rejected during argument mapping: " + e.getMessage());
                cancelWithError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to start ilivalidator process.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to start ilivalidator process."));
            }
        }

        private void cancelWithError(Status status) {
            // Deleting first makes the observable contract deterministic: when the client sees the error, the
            // session directory is gone. onError is an async handoff to the transport, so cleanup after it
            // races the client's next assertion.
            files.deleteAll();
            responseObserver.onError(status.asRuntimeException());
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
            List<ProcessingFile> transferFiles = new ArrayList<>(files.getAll(IlivalidatorFileType.TRANSFER_FILE_XTF));
            transferFiles.addAll(files.getAll(IlivalidatorFileType.TRANSFER_FILE_ITF));
            if (transferFiles.size() != 1) {
                return Optional.empty();
            }
            return Optional.of(buildValidateArguments(transferFiles.getFirst()));
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
            addArgument(args, "--plugins", materializePlugins(transferFile).map(Path::toString).orElse(""));

            args.add(transferFile.filePath().toAbsolutePath().toString());
            return args;
        }

        /**
         * Copies the jars of the selected plugins into the plugin subfolder of the session directory. Without a
         * selection nothing is created and {@code --plugins} stays unset, which leaves the tool with its own
         * default; that default points into the tool installation, which carries no plugins.
         */
        private Optional<Path> materializePlugins(ProcessingFile transferFile) {
            if (requestedPlugins.isEmpty()) {
                return Optional.empty();
            }

            Path sessionDirectory = transferFile.filePath().toAbsolutePath().getParent();
            if (sessionDirectory == null) {
                throw new UncheckedIOException(new IOException("The transfer file " + transferFile.filePath() + " is not inside a session directory."));
            }

            try {
                return pluginCatalog.materialize(requestedPlugins, sessionDirectory);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Validates the requested tool version against the offered set. Rejected in onInfo so that no file is
         * received for a request that cannot run; the match against the offered set is also what keeps the
         * request value from ever becoming a path.
         */
        private String validateToolVersion(String toolVersion) {
            if (toolVersion.isEmpty()) {
                return toolVersion;
            }

            Set<String> availableVersions = ilitoolsRunner.availableVersions(IlitoolsRunner.Tool.ILIVALIDATOR);
            if (availableVersions.isEmpty()) {
                // An empty set means the tool home itself is missing or wrong: a deployment fault, not a
                // request fault, so it must not surface as INVALID_ARGUMENT.
                throw new IllegalStateException("No " + IlitoolsRunner.Tool.ILIVALIDATOR + " versions are offered; the deployment is misconfigured.");
            }
            if (!availableVersions.contains(toolVersion)) {
                throw new IllegalArgumentException("Tool version \"" + toolVersion + "\" is not available, expected one of " + availableVersions + ".");
            }
            return toolVersion;
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
