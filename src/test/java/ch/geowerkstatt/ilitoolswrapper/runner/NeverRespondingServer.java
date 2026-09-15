package ch.geowerkstatt.ilitoolswrapper.runner;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;

/**
 * A loopback TCP server that accepts a single connection and never answers, so a client keeps waiting for a response.
 *
 * <p>Provides observations for {@link #clientConnected()} and {@link #clientDisconnected()}.
 */
final class NeverRespondingServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final CompletableFuture<Void> clientConnected = new CompletableFuture<>();
    private final CompletableFuture<Void> clientDisconnected = new CompletableFuture<>();
    private volatile @Nullable Socket clientSocket;

    private NeverRespondingServer(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        this.acceptThread = new Thread(this::acceptAndBlock, "never-responding-server");
        this.acceptThread.setDaemon(true);
    }

    static NeverRespondingServer start() throws IOException {
        NeverRespondingServer server = new NeverRespondingServer(new ServerSocket(0, 1, InetAddress.getLoopbackAddress()));
        server.acceptThread.start();
        return server;
    }

    /** The base URL a client can point at; requests to it are accepted but never answered. */
    String baseUrl() {
        return "http://localhost:" + serverSocket.getLocalPort() + "/";
    }

    /** Completes once a client has connected. */
    CompletableFuture<Void> clientConnected() {
        return clientConnected;
    }

    /** Completes once the connected client has disconnected. */
    CompletableFuture<Void> clientDisconnected() {
        return clientDisconnected;
    }

    private void acceptAndBlock() {
        try {
            Socket client = serverSocket.accept();
            clientSocket = client;
            clientConnected.complete(null);

            InputStream in = client.getInputStream();
            byte[] buffer = new byte[256];
            try {
                // Read the complete request and block until the client disconnects.
                int read;
                do {
                    read = in.read(buffer);
                } while (read != -1);
            } catch (IOException e) {
                // Abrupt client disconnect
            }
            clientDisconnected.complete(null);
        } catch (IOException e) {
            clientConnected.completeExceptionally(e);
            clientDisconnected.completeExceptionally(e);
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        // Closing the accepted socket unblocks the read in acceptAndBlock if the client is still connected.
        Socket client = clientSocket;
        if (client != null) {
            client.close();
        }
    }
}
