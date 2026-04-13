package org.example.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logger serveur — chaque message est préfixé par [HH:mm:ss][TYPE].
 * Les messages sont transmis au ServerEventListener pour affichage
 * dans le panneau de supervision (ServerMonitorFrame).
 */
public class ServerLogger {
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ServerEventListener listener;

    public ServerLogger(ServerEventListener listener) {
        this.listener = listener;
    }

    public void info(String msg) {
        log("INFO", msg);
    }

    public void client(String clientId, String cmd) {
        log("CLIENT", clientId + " >> " + cmd);
    }

    public void server(String response) {
        log("SERVER", "<< " + response);
    }

    public void error(String msg) {
        log("ERREUR", msg);
    }

    private void log(String type, String msg) {
        String ts      = LocalDateTime.now().format(FMT);
        String formatted = "[" + ts + "][" + type + "] " + msg;
        listener.onLog(formatted);
    }
}
