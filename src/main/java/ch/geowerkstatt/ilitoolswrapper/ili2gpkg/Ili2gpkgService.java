package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import ch.geowerkstatt.ilitoolswrapper.files.FileManager;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFileSet;
import ch.geowerkstatt.ilitoolswrapper.healthcheck.ServiceHealthCheck;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertResponse;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.proto.common.StatusUpdate;
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
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class Ili2gpkgService extends Ili2gpkgServiceGrpc.Ili2gpkgServiceImplBase implements ServiceHealthCheck {
    private record ProcessingArguments(Ili2gpkgFileType outputFileType, boolean returnOutputOnError, List<String> arguments) { }

    private static final Logger LOGGER = Logger.getLogger(Ili2gpkgService.class.getName());
    private final FileManager fileManager;
    private final IlitoolsRunner ilitoolsRunner;

    /**
     * Creates a new {@link Ili2gpkgService} with the specified file manager and tool runner.
     *
     * @param fileManager the FileManager to use for managing temporary files
     * @param ilitoolsRunner the IlitoolsRunner to use for running the ili2gpkg tool
     */
    public Ili2gpkgService(FileManager fileManager, IlitoolsRunner ilitoolsRunner) {
        this.fileManager = fileManager;
        this.ilitoolsRunner = ilitoolsRunner;
    }

    @Override
    public String getServiceName() {
        return Ili2gpkgServiceGrpc.SERVICE_NAME;
    }

    @Override
    public HealthCheckResponse.ServingStatus getHealthStatus() {
        try {
            IlitoolsRunner.Timeout timeout = new IlitoolsRunner.Timeout(5, TimeUnit.SECONDS);
            ilitoolsRunner.run(IlitoolsRunner.Tool.ILI2GPKG, List.of("--version"), timeout).get();
            return HealthCheckResponse.ServingStatus.SERVING;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HealthCheckResponse.ServingStatus.NOT_SERVING;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Health check failed: ili2gpkg is not available.", e);
            return HealthCheckResponse.ServingStatus.NOT_SERVING;
        }
    }

    @Override
    public StreamObserver<ConvertRequest> convert(StreamObserver<ConvertResponse> responseObserver) {
        return new ConvertObserver(responseObserver);
    }

    private final class ConvertObserver implements StreamObserver<ConvertRequest> {
        private final StreamObserver<ConvertResponse> responseObserver;
        private final ProcessingFileSet<Ili2gpkgFileType> files = new ProcessingFileSet<>(fileManager);
        private @Nullable ProcessingFile currentFile;
        private @Nullable ConvertRequestInfo info;

        ConvertObserver(StreamObserver<ConvertResponse> responseObserver) {
            this.responseObserver = responseObserver;
        }

        @Override
        public void onNext(ConvertRequest value) {
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

        private void onInfo(ConvertRequestInfo info) {
            if (this.info != null) {
                LOGGER.warning("Duplicate info message received.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("Duplicate info message sent."));
                return;
            }

            this.info = info;
            LOGGER.fine("Received info: " + info);
        }

        private void onFileStart(Ili2gpkgFileStart fileStart) {
            if (info == null) {
                LOGGER.warning("Received file start before info message.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("An info message must be sent before the files."));
                return;
            }
            Ili2gpkgFileType type = fileStart.getType();
            String extension = switch (type) {
                case DB_FILE -> "gpkg";
                case MODEL_FILE -> "ili";
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
                currentFile = files.create(type, "file" + fileNumber, extension);
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
            LOGGER.log(Level.WARNING, "Error in convert", t);
            files.deleteAll();
        }

        @Override
        public void onCompleted() {
            if (files.isEmpty()) {
                LOGGER.warning("No files were transferred, aborting conversion.");
                cancelWithError(Status.ABORTED.withDescription("No files were transferred, aborting conversion."));
                return;
            }

            if (!files.closeAll()) {
                LOGGER.warning("Failed to close output files, aborting conversion.");
                cancelWithError(Status.ABORTED.withDescription("Failed to receive file data."));
                return;
            }

            try {
                LOGGER.fine("Processing data with ili2gpkg.");
                Optional<ProcessingArguments> parsedArguments = convertRequestToArguments();
                if (parsedArguments.isEmpty()) {
                    LOGGER.warning("Invalid input files for convert request.");
                    cancelWithError(Status.INVALID_ARGUMENT.withDescription("Invalid input files for convert request."));
                    return;
                }

                ProcessingArguments processingArguments = parsedArguments.get();
                var _ = ilitoolsRunner.run(IlitoolsRunner.Tool.ILI2GPKG, processingArguments.arguments(), null)
                        .handleAsync((_, throwable) -> {
                            if (throwable != null) {
                                LOGGER.warning("Processing data with ili2gpkg failed: " + throwable);
                            }

                            boolean success = throwable == null;
                            returnResponse(success, processingArguments);
                            return null;
                        });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to start ili2gpkg process.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to start ili2gpkg process."));
            }
        }

        private void cancelWithError(Status status) {
            responseObserver.onError(status.asRuntimeException());
            files.deleteAll();
        }

        private Optional<ProcessingArguments> convertRequestToArguments() {
            List<ProcessingFile> subjects;
            ProcessingFile dbFile;
            Ili2gpkgFileType outputFileType;
            boolean returnOutputOnError = false;
            List<String> args = new ArrayList<>();
            switch (Objects.requireNonNull(info).getOperation()) {
                case OPERATION_SCHEMA_IMPORT -> {
                    outputFileType = Ili2gpkgFileType.DB_FILE;
                    subjects = files.getSingle(Ili2gpkgFileType.MODEL_FILE).map(List::of).orElse(null);
                    dbFile = files.create(Ili2gpkgFileType.DB_FILE, "output", "gpkg");
                    args.add("--schemaimport");
                }
                case OPERATION_IMPORT -> {
                    outputFileType = Ili2gpkgFileType.DB_FILE;
                    subjects = files.getAll(Ili2gpkgFileType.TRANSFER_FILE).orElse(null);
                    dbFile = files.getSingle(Ili2gpkgFileType.DB_FILE).orElse(null);
                    args.add("--import");
                }
                case OPERATION_EXPORT -> {
                    outputFileType = Ili2gpkgFileType.TRANSFER_FILE;
                    subjects = List.of(files.create(Ili2gpkgFileType.TRANSFER_FILE, "output", "xtf"));
                    dbFile = files.getSingle(Ili2gpkgFileType.DB_FILE).orElse(null);
                    args.add("--export");
                }
                case OPERATION_UPDATE -> {
                    outputFileType = Ili2gpkgFileType.DB_FILE;
                    subjects = files.getAll(Ili2gpkgFileType.TRANSFER_FILE).orElse(null);
                    dbFile = files.getSingle(Ili2gpkgFileType.DB_FILE).orElse(null);
                    args.add("--update");
                }
                case OPERATION_VALIDATE -> {
                    outputFileType = Ili2gpkgFileType.XTF_LOG_FILE;
                    // The xtf log holds the reported errors, so it is returned even when the validation fails.
                    returnOutputOnError = true;
                    subjects = List.of();
                    dbFile = files.getSingle(Ili2gpkgFileType.DB_FILE).orElse(null);
                    ProcessingFile xtfLogFile = files.create(Ili2gpkgFileType.XTF_LOG_FILE, "log", "xtf");
                    args.add("--validate");
                    args.add("--verbose");
                    addArgument(args, "--xtflog", xtfLogFile.filePath().toAbsolutePath().toString());
                    info = info.toBuilder()
                            .setDisableValidation(false)
                            .build();
                }
                default -> throw new IllegalArgumentException("Unsupported operation: " + info.getOperation());
            }

            if (subjects == null || dbFile == null) {
                return Optional.empty();
            }

            addArgument(args, "--dbfile", dbFile.filePath().toAbsolutePath().toString());
            addArgument(args, "--models", String.join(";", info.getModelsList()));
            addArgument(args, "--defaultSrsCode", info.getDefaultSrsCode() > 0 ? Integer.toString(info.getDefaultSrsCode()) : null);
            addArgument(args, "--dataset", info.getDataset());

            ProcessingFile logFile = files.create(Ili2gpkgFileType.LOG_FILE, "log", "txt");
            addArgument(args, "--log", logFile.filePath().toAbsolutePath().toString());

            addFlag(args, "--disableValidation", info.getDisableValidation());
            addFlag(args, "--createBasketCol", info.getCreateBasketCol());
            addFlag(args, "--sqlEnableNull", info.getSqlEnableNull());
            addFlag(args, "--skipReferenceErrors", info.getSkipReferenceErrors());
            addFlag(args, "--skipGeometryErrors", info.getSkipGeometryErrors());
            addFlag(args, "--importTid", info.getImportTid());
            addFlag(args, "--strokeArcs", info.getStrokeArcs());

            if (!subjects.isEmpty()) {
                String subject = subjects.stream()
                        .map(file -> file.filePath().toAbsolutePath().toString())
                        .collect(Collectors.joining(";"));
                args.add(subject);
            }
            return Optional.of(new ProcessingArguments(outputFileType, returnOutputOnError, args));
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

        private void returnResponse(boolean success, ProcessingArguments processingArguments) {
            try {
                responseObserver.onNext(createStatusResponse(success));
                returnFile(responseObserver, Ili2gpkgFileType.LOG_FILE);
                if (success || processingArguments.returnOutputOnError()) {
                    returnFile(responseObserver, processingArguments.outputFileType());
                }
                responseObserver.onCompleted();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to return file data.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to return file data."));
            } finally {
                files.deleteAll();
            }
        }

        private static ConvertResponse createStatusResponse(boolean success) {
            return ConvertResponse.newBuilder()
                    .setStatus(StatusUpdate.newBuilder()
                            .setSuccess(success)
                            .build())
                    .build();
        }

        private void returnFile(StreamObserver<ConvertResponse> responseObserver, Ili2gpkgFileType fileType) throws IOException {
            ProcessingFile file = files.getSingle(fileType)
                    .orElseThrow(() -> new IllegalStateException("Expected a single file of type " + fileType + " to return."));
            try (InputStream inputStream = file.inputStream()) {
                responseObserver.onNext(ConvertResponse.newBuilder()
                        .setFileStart(Ili2gpkgFileStart.newBuilder()
                                .setType(fileType)
                                .build())
                        .build());

                byte[] buffer = new byte[10 * 1024 * 1024];
                while (true) {
                    int bytesRead = inputStream.read(buffer);
                    if (bytesRead <= 0) {
                        break;
                    }
                    responseObserver.onNext(ConvertResponse.newBuilder()
                            .setChunk(ByteString.copyFrom(buffer, 0, bytesRead))
                            .build());
                }
            }
        }
    }
}
