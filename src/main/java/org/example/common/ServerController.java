package org.example.common;
public interface ServerController {
    void startServer();
    void stopServer();
    boolean isRunning();
    int getClientCount();
}