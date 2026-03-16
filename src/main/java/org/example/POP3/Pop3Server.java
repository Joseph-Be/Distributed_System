package org.example.POP3;
import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.example.common.ServerEventListener;
import org.example.rmi.RemoteUserStore;



public class Pop3Server {
    private static final int PORT = 10010; // Custom port to avoid conflicts
    private List<File> emails;
    private List<Boolean> deletionFlags = new ArrayList<>();

    /*public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("POP3 Server started on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Connection from " + clientSocket.getInetAddress());
                new Pop3Session(clientSocket).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }*/


}

class Pop3Session extends Thread {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username;
    private File userDir;
    private List<File> emails;
    private boolean authenticated;
    private List<Boolean> deletionFlags;
    private Pop3ServerController controller;
    private ServerEventListener listener;
    private String clientId;

    public Pop3Session(Socket socket,
                       Pop3ServerController controller,
                       ServerEventListener listener,
                       String clientId) {

        this.socket = socket;
        this.controller = controller;
        this.listener = listener;
        this.clientId = clientId;
        this.authenticated = false;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            sendResponse("+OK POP3 server ready");

            String line;
            while ((line = in.readLine()) != null) {
                logCommand(line);
                System.out.println("Received: " + line);

                String[] parts = line.split(" ", 2);
                String command = parts[0].toUpperCase();
                String argument = parts.length > 1 ? parts[1] : "";

                switch (command) {
                    case "USER":
                        handleUser(argument);
                        break;
                    case "PASS":
                        handlePass(argument);
                        break;
                    case "STAT":
                        handleStat();
                        break;
                    case "LIST":
                        handleList();
                        break;
                    case "RETR":
                        handleRetr(argument);
                        break;
                    case "DELE":
                        handleDele(argument);
                        break;
                    case "RSET":
                        handleRset();
                        break;
                    case "QUIT":
                        handleQuit();
                        return;
                    default:
                        sendResponse("-ERR Unknown command");
                        break;
                }
            }

            if (authenticated) {
                String msg = "La connexion a été interrompue sans recevoir QUIT. Les suppressions marquées ne seront pas appliquées.";
                System.err.println(msg);
                if (listener != null) {
                    listener.onLog(clientId + " !! " + msg);
                }
            }

        } catch (IOException e) {
            String msg = "Erreur lors de la lecture de la connexion : " + e.getMessage();
            System.err.println(msg);
            if (listener != null) {
                listener.onLog(clientId + " !! " + msg);
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}

            controller.removeClient(socket, clientId);
        }
    }

    private void logCommand(String message) {
        if (listener != null) {
            listener.onLog(clientId + " -> " + message);
        }
    }

    private void logResponse(String message) {
        if (listener != null) {
            listener.onLog("server -> " + message);
        }
    }

    private void sendResponse(String message) {
        out.println(message);
        logResponse(message);
    }

    private void handleUser(String arg) {
        if (!RemoteUserStore.userExists(arg)) {
            sendResponse("-ERR User not found");
            return;
        }

        username = arg;
        userDir = new File("shared/mailserver/" + username);

        if (!userDir.exists()) {
            if (!userDir.mkdirs()) {
                username = null;
                userDir = null;
                sendResponse("-ERR Cannot create user directory");
                return;
            }
        } else if (!userDir.isDirectory()) {
            username = null;
            userDir = null;
            sendResponse("-ERR User path is not a directory");
            return;
        }

        sendResponse("+OK User accepted");
    }

    private void handlePass(String arg) {
        if (username == null) {
            sendResponse("-ERR USER required first");
            return;
        }

        if (!RemoteUserStore.validateToken(username, arg)) {
            sendResponse("-ERR Invalid token");
            return;
        }

        authenticated = true;

        File[] files = userDir.listFiles();
        if (files == null) {
            emails = new ArrayList<File>();
        } else {
            emails = new ArrayList<File>(Arrays.asList(files));
        }

        deletionFlags = new ArrayList<Boolean>();
        for (int i = 0; i < emails.size(); i++) {
            deletionFlags.add(false);
        }

        sendResponse("+OK Password accepted");
    }

    private void handleStat() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }

        long size = 0;
        for (File email : emails) {
            size += email.length();
        }

        sendResponse("+OK " + emails.size() + " " + size);
    }

    private void handleList() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }

        sendResponse("+OK " + emails.size() + " messages");
        for (int i = 0; i < emails.size(); i++) {
            sendResponse((i + 1) + " " + emails.get(i).length());
        }
        sendResponse(".");
    }

    private void handleRetr(String arg) {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }

        BufferedReader reader = null;
        try {
            int index = Integer.parseInt(arg) - 1;
            if (index < 0 || index >= emails.size()) {
                sendResponse("-ERR No such message");
                return;
            }

            File emailFile = emails.get(index);
            sendResponse("+OK " + emailFile.length() + " octets");

            reader = new BufferedReader(new FileReader(emailFile));
            String line;
            while ((line = reader.readLine()) != null) {
                sendResponse(line);
            }
            sendResponse(".");

        } catch (Exception e) {
            sendResponse("-ERR Invalid message number");
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private void handleDele(String arg) {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }

        try {
            arg = arg.trim();
            int index = Integer.parseInt(arg) - 1;

            if (index < 0 || index >= emails.size()) {
                sendResponse("-ERR No such message");
                return;
            }

            if (deletionFlags.get(index)) {
                sendResponse("-ERR Message already marked for deletion");
                return;
            }

            deletionFlags.set(index, true);
            sendResponse("+OK Message marked for deletion");

        } catch (NumberFormatException nfe) {
            sendResponse("-ERR Invalid message number");
        } catch (Exception e) {
            sendResponse("-ERR Invalid message number");
        }
    }

    private void handleRset() {
        if (!authenticated) {
            sendResponse("-ERR Authentication required");
            return;
        }

        for (int i = 0; i < deletionFlags.size(); i++) {
            deletionFlags.set(i, false);
        }

        sendResponse("+OK Deletion marks reset");
    }

    private void handleQuit() {
        if (deletionFlags != null && emails != null) {
            for (int i = deletionFlags.size() - 1; i >= 0; i--) {
                if (deletionFlags.get(i)) {
                    File emailFile = emails.get(i);
                    if (emailFile.delete()) {
                        String msg = "Deleted email: " + emailFile.getAbsolutePath();
                        System.out.println(msg);
                        if (listener != null) {
                            listener.onLog(clientId + " ** " + msg);
                        }
                        emails.remove(i);
                        deletionFlags.remove(i);
                    } else {
                        String msg = "Failed to delete email: " + emailFile.getAbsolutePath();
                        System.err.println(msg);
                        if (listener != null) {
                            listener.onLog(clientId + " !! " + msg);
                        }
                    }
                }
            }
        }

        sendResponse("+OK POP3 server signing off");
    }
}