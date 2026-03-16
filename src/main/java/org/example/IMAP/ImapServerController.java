package org.example.IMAP;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.example.common.ServerController;
import org.example.common.ServerEventListener;

public class ImapServerController implements ServerController {

    private final int port;
    private final ServerEventListener listener;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private int clientCounter = 0;

    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();

    public ImapServerController(int port, ServerEventListener listener) {
        this.port = port;
        this.listener = listener;
    }

    @Override
    public void startServer() {
        if (running) return;

        running = true;
        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                log("IMAP démarré sur le port " + port);

                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        clients.add(client);
                        updateClients();

                        clientCounter++;
                        String clientId = "Client" + clientCounter;

                        log(clientId + " connecté depuis " + client.getInetAddress());

                        new ImapSession(client, this, listener, clientId).start();

                    } catch (IOException e) {
                        if (running) {
                            log("Erreur accept() : " + e.getMessage());
                        }
                    }
                }

            } catch (IOException e) {
                log("Impossible de démarrer IMAP : " + e.getMessage());
            } finally {
                stopServer();
            }
        });

        acceptThread.start();
    }

    @Override
    public void stopServer() {
        running = false;

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }

        for (Socket socket : clients) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        clients.clear();
        updateClients();
        log("IMAP arrêté.");
    }

    public void removeClient(Socket socket, String clientId) {
        clients.remove(socket);
        updateClients();
        log(clientId + " déconnecté");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getClientCount() {
        return clients.size();
    }

    private void updateClients() {
        listener.onClientCountChanged(clients.size());
    }

    private void log(String msg) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        listener.onLog("[" + ts + "] " + msg);
    }
}