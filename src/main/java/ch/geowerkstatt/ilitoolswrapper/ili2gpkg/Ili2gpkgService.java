package ch.geowerkstatt.ilitoolswrapper.ili2gpkg;

import ch.geowerkstatt.ilitoolswrapper.files.ChunkedFile;
import ch.geowerkstatt.ilitoolswrapper.files.FileManager;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequest;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.ConvertRequestInfo;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.FileStart;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.Ili2gpkgServiceGrpc;
import ch.geowerkstatt.ilitoolswrapper.proto.ili2gpkg.StatusUpdate;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.ArrayList;
import java.util.List;
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
        private final List<ChunkedFile> files = new ArrayList<>();
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
                    responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid message type.").asRuntimeException());
                }
            }
        }

        private void onInfo(ConvertRequestInfo info) {
            if (this.info != null) {
                LOGGER.warning("Duplicate info message received.");
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Duplicate info message sent.").asRuntimeException());
                return;
            }

            this.info = info;
            LOGGER.fine("Received info: " + info);
        }

        private void onFileStart(FileStart fileStart) {
            if (info == null) {
                LOGGER.warning("Received file start before info message.");
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("An info message must be sent before the files.").asRuntimeException());
                return;
            }

            try {
                int fileNumber = files.size() + 1;
                ChunkedFile file = fileManager.createChunkedFile(sessionId.toString(), "file" + fileNumber, fileStart.getFileExtension());
                files.add(file);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("Invalid argument: " + e.getMessage());
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
            } catch (Exception e) {
                LOGGER.severe("Failed to open output file: " + e.getMessage());
                responseObserver.onError(e);
            }
        }

        private void onChunk(ByteString chunk) {
            if (info == null) {
                LOGGER.warning("Received chunk before info message.");
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("An info message must be sent before the file content.").asRuntimeException());
                return;
            }
            if (files.isEmpty()) {
                LOGGER.warning("Received chunk before file start message.");
                responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("A file start message must be sent before the content.").asRuntimeException());
                return;
            }

            LOGGER.fine("Received chunk of size: " + chunk.size());

            try {
                ChunkedFile file = files.getLast();
                file.writeChunk(chunk);
            } catch (Exception e) {
                LOGGER.warning("Failed to write chunk to file: " + e.getMessage());
                responseObserver.onError(e);
            }
        }

        @Override
        public void onError(Throwable t) {
            deleteFiles();
            files.clear();
            LOGGER.warning("Error in convert: " + t.getMessage());
            responseObserver.onError(t);
        }

        @Override
        public void onCompleted() {
            if (files.isEmpty()) {
                LOGGER.warning("No files were transferred, aborting conversion.");
                responseObserver.onError(new IllegalStateException("No files were transferred, aborting conversion."));
                return;
            }

            for (ChunkedFile file : files) {
                try {
                    file.close();
                } catch (Exception e) {
                    LOGGER.warning("Failed to close output file: " + e.getMessage());
                    responseObserver.onError(e);
                    return;
                }
            }

            processData();
            deleteFiles();
            responseObserver.onNext(StatusUpdate.newBuilder().setMessage("Conversion completed.").build());
            responseObserver.onCompleted();
        }

        private void deleteFiles() {
            for (ChunkedFile file : files) {
                try {
                    file.delete();
                } catch (Exception e) {
                    LOGGER.warning("Failed to delete output file: " + e.getMessage());
                }
            }
        }

        private void processData() {
            LOGGER.fine("Processing data with ili2gpkg.");
        }
    }
}
