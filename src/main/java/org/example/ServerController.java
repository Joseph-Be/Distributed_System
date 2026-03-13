package org.example;
public interface ServerController {
    void startServer();
    void stopServer();
    boolean isRunning();
    int getClientCount();
}