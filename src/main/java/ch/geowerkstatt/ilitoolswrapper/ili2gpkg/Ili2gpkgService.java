package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import ch.geowerkstatt.ilitoolswrapper.files.FileManager;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertResponse;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.StatusUpdate;
import ch.geowerkstatt.ilitoolswrapper.runner.IlitoolsRunner;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Ili2gpkgService extends Ili2gpkgServiceGrpc.Ili2gpkgServiceImplBase {
    private record ProcessingArguments(Ili2gpkgFileType outputFileType, List<String> arguments) { }

    private static final Logger LOGGER = Logger.getLogger(Ili2gpkgService.class.getName());
    private final FileManager fileManager;
    private final IlitoolsRunner ilitoolsRunner;

    /**
     * Creates a new {@link Ili2gpkgService} with the specified file manager.
     *
     * @param fileManager the FileManager to use for managing temporary files
     * @param ilitoolsRunner the IlitoolsRunner to use for running the ili2gpkg tool
     */
    public Ili2gpkgService(FileManager fileManager, IlitoolsRunner ilitoolsRunner) {
        this.fileManager = fileManager;
        this.ilitoolsRunner = ilitoolsRunner;
    }

    @Override
    public StreamObserver<ConvertRequest> convert(StreamObserver<ConvertResponse> responseObserver) {
        return new ConvertObserver(responseObserver);
    }

    private final class ConvertObserver implements StreamObserver<ConvertRequest> {
        private final StreamObserver<ConvertResponse> responseObserver;
        private final UUID sessionId = UUID.randomUUID();
        private final Map<Ili2gpkgFileType, ProcessingFile> files = new HashMap<>();
        private ProcessingFile currentFile;
        private ConvertRequestInfo info;

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
            if (files.containsKey(type)) {
                LOGGER.warning("Received two files with the same type.");
                cancelWithError(Status.INVALID_ARGUMENT.withDescription("A file of this type is already uploaded."));
                return;
            }
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
            LOGGER.log(Level.WARNING, "Error in convert", t);
            deleteFiles();
        }

        @Override
        public void onCompleted() {
            if (files.isEmpty()) {
                LOGGER.warning("No files were transferred, aborting conversion.");
                cancelWithError(Status.ABORTED.withDescription("No files were transferred, aborting conversion."));
                return;
            }

            for (ProcessingFile file : files.values()) {
                try {
                    file.outputStream().close();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to close output file.", e);
                    cancelWithError(Status.ABORTED.withDescription("Failed to receive file data."));
                    return;
                }
            }

            try {
                ProcessingFile logFile = createProcessingFile(Ili2gpkgFileType.LOG_FILE, "log", "txt");
                LOGGER.fine("Processing data with ili2gpkg.");
                Optional<ProcessingArguments> parsedArguments = convertRequestToArguments();
                if (parsedArguments.isEmpty()) {
                    LOGGER.warning("Missing input files for convert request.");
                    cancelWithError(Status.INVALID_ARGUMENT.withDescription("Missing input files for convert request."));
                    return;
                }

                ProcessingArguments processingArguments = parsedArguments.get();
                ilitoolsRunner.run(IlitoolsRunner.Tool.ILI2GPKG, processingArguments.arguments(), logFile)
                        .thenAcceptAsync(_ -> returnResponse(true, processingArguments.outputFileType()))
                        .exceptionallyAsync(t -> {
                            LOGGER.warning("Processing data with ili2gpkg failed: " + t);
                            returnResponse(false, processingArguments.outputFileType());
                            return null;
                        });
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to start ili2gpkg process.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to start ili2gpkg process."));
            }
        }

        private ProcessingFile createProcessingFile(Ili2gpkgFileType fileType, String prefix, String extension) {
            ProcessingFile file = fileManager.createProcessingFile(sessionId.toString(), prefix, extension);
            files.put(fileType, file);
            return file;
        }

        private void cancelWithError(Status status) {
            responseObserver.onError(status.asRuntimeException());
            deleteFiles();
        }

        private void deleteFiles() {
            try {
                fileManager.deleteProcessingFiles(sessionId.toString());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to delete processing files.", e);
            }
            files.clear();
        }

        private Optional<ProcessingArguments> convertRequestToArguments() {
            ProcessingFile subject;
            ProcessingFile dbFile;
            Ili2gpkgFileType outputFileType;
            List<String> args = new ArrayList<>();
            switch (info.getOperation()) {
                case OPERATION_SCHEMA_IMPORT -> {
                    outputFileType = Ili2gpkgFileType.DB_FILE;
                    subject = files.get(Ili2gpkgFileType.MODEL_FILE);
                    dbFile = createProcessingFile(Ili2gpkgFileType.DB_FILE, "output", "gpkg");
                    args.add("--schemaimport");
                }
                case OPERATION_IMPORT -> {
                    outputFileType = Ili2gpkgFileType.DB_FILE;
                    subject = files.get(Ili2gpkgFileType.TRANSFER_FILE);
                    dbFile = files.get(Ili2gpkgFileType.DB_FILE);
                    args.add("--import");
                }
                case OPERATION_EXPORT -> {
                    outputFileType = Ili2gpkgFileType.TRANSFER_FILE;
                    subject = createProcessingFile(Ili2gpkgFileType.TRANSFER_FILE, "output", "xtf");
                    dbFile = files.get(Ili2gpkgFileType.DB_FILE);
                    args.add("--export");
                }
                default -> throw new IllegalArgumentException("Unsupported operation: " + info.getOperation());
            }

            if (subject == null || dbFile == null) {
                return Optional.empty();
            }

            args.add("--dbfile");
            args.add(dbFile.filePath().toAbsolutePath().toString());

            if (info.getModelsCount() > 0) {
                args.add("--models");
                args.add(String.join(";", info.getModelsList()));
            }

            if (info.getDefaultSrsCode() > 0) {
                args.add("--defaultSrsCode");
                args.add(Integer.toString(info.getDefaultSrsCode()));
            }

            addFlag(args, "--disableValidation", info.getDisableValidation());
            addFlag(args, "--createBasketCol", info.getCreateBasketCol());
            addFlag(args, "--sqlEnableNull", info.getSqlEnableNull());
            addFlag(args, "--skipReferenceErrors", info.getSkipReferenceErrors());
            addFlag(args, "--skipGeometryErrors", info.getSkipGeometryErrors());
            addFlag(args, "--importTid", info.getImportTid());
            addFlag(args, "--strokeArcs", info.getStrokeArcs());

            args.add(subject.filePath().toAbsolutePath().toString());
            return Optional.of(new ProcessingArguments(outputFileType, args));
        }

        private static void addFlag(List<String> args, String flag, boolean enabled) {
            if (enabled) {
                args.add(flag);
            }
        }

        private void returnResponse(boolean success, Ili2gpkgFileType outputFileType) {
            try {
                responseObserver.onNext(createStatusResponse(success));
                returnFile(responseObserver, Ili2gpkgFileType.LOG_FILE);
                if (success) {
                    returnFile(responseObserver, outputFileType);
                }
                responseObserver.onCompleted();
                deleteFiles();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to return file data.", e);
                cancelWithError(Status.ABORTED.withDescription("Failed to return file data."));
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
            ProcessingFile file = files.get(fileType);
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
