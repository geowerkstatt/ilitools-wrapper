import org.jspecify.annotations.NullMarked;

@NullMarked
module ch.geowerkstatt.ilitoolswrapper {
    requires com.google.protobuf;
    requires io.grpc;
    requires io.grpc.services;
    requires io.grpc.stub;
    requires org.jspecify;
    requires java.logging;
}
