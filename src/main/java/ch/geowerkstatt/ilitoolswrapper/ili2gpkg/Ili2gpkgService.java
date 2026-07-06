package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import ch.geowerkstatt.ilitoolswrapper.files.FileManager;
import ch.geowerkstatt.ilitoolswrapper.files.ProcessingFile;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgFileType;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.StatusUpdate;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class Ili2gpkgService extends Ili2gpkgServiceGrpc.Ili2gpkgServiceImplBase {
    private static final Logger LOGGER = Logger.getLogger(Ili2gpkgService.class.getName());
    private final FileManager fileManager;

    /**
     * Creates a new {@link Ili2gpkgService} with the specified file manager.
     *
     * @param fileManager the FileManager to use for managing temporary files
     */
    public Ili2gpkgService(FileManager fileManager) {
        this.fileManager = fileManager;
    }

    @Override
    public StreamObserver<ConvertRequest> convert(StreamObserver<StatusUpdate> responseObserver) {
        return new ConvertObserver(responseObserver);
    }

    private final class ConvertObserver implements StreamObserver<ConvertRequest> {
        private final StreamObserver<StatusUpdate> responseObserver;
        private final UUID sessionId = UUID.randomUUID();
        private final Map<Ili2gpkgFileType, ProcessingFile> files = new HashMap<>();
        private ProcessingFile currentFile;
        private ConvertRequestInfo info;

        ConvertObserver(StreamObserver<StatusUpdate> responseObserver) {
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
                currentFile = fileManager.createProcessingFile(sessionId.toString(), "file" + fileNumber, extension);
                files.put(type, currentFile);
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

            LOGGER.fine("Received chunk of size: " + chunk.size());

            try {
                chunk.writeTo(currentFile.outputStream());
            } catch (Exception e) {
                LOGGER.warning("Failed to write chunk to file: " + e);
                cancelWithError(Status.ABORTED.withDescription("Failed to receive file data."));
            }
        }

        @Override
        public void onError(Throwable t) {
            deleteFiles();
            files.clear();
            LOGGER.warning("Error in convert: " + t);
            cancelWithError(Status.CANCELLED);
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
                    file.closeOutputStream();
                } catch (Exception e) {
                    LOGGER.warning("Failed to close output file: " + e);
                    cancelWithError(Status.ABORTED.withDescription("Failed to receive file data."));
                    return;
                }
            }

            processData();
            deleteFiles();
            responseObserver.onNext(StatusUpdate.newBuilder().setMessage("Conversion completed.").build());
            responseObserver.onCompleted();
        }

        private void cancelWithError(Status status) {
            responseObserver.onError(status.asRuntimeException());
            deleteFiles();
        }

        private void deleteFiles() {
            try {
                fileManager.deleteProcessingFiles(sessionId.toString());
            } catch (Exception e) {
                LOGGER.warning("Failed to delete processing files: " + e.getMessage());
            }
        }

        private void processData() {
            LOGGER.fine("Processing data with ili2gpkg.");
        }
    }
}
