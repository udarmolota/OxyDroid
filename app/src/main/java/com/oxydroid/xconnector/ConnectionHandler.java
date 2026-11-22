package com.oxydroid.xconnector;

public interface ConnectionHandler {
    default ConnectedClient newConnectedClient(long clientPtr, int fd) {
        return new ConnectedClient(clientPtr, fd);
    }

    void handleConnectionShutdown(ConnectedClient client);

    void handleNewConnection(ConnectedClient client);
}