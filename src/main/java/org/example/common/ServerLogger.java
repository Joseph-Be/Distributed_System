package org.example.common;

import java.text.SimpleDateFormat;
import java.util.Date;

public class ServerLogger {
    private final ServerEventListener listener;

    public ServerLogger(ServerEventListener listener) {
        this.listener = listener;
    }

    public void info(String msg) {
        log("INFO", msg);
    }

    public void client(String clientId, String cmd) {
        log("CLIENT", clientId + " -> " + cmd);
    }

    public void server(String response) {
        log("SERVEUR", "-> " + response);
    }

    public void error(String msg) {
        log("ERREUR", msg);
    }

    private void log(String type, String msg) {
        listener.onLog(msg);
    }
}