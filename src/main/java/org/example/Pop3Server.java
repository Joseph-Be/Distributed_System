package org.example;
import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;



public class Pop3Server {
    private static final int PORT = 110; // Custom port to avoid conflicts
    private List<File> emails;
    private List<Boolean> deletionFlags = new ArrayList<>();

    public static void main(String[] args) {
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
    }


}

class Pop3Session extends Thread {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username;
    private File userDir;
    private List<File> emails;
    private boolean authenticated;
    private List<Boolean> deletionFlags; // Déclaration correcte

    

    public Pop3Session(Socket socket) {
        this.socket = socket;
        this.authenticated = false;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            out.println("+OK POP3 server ready");

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("Received: " + line);
                String[] parts = line.split(" ", 2);
                String command = parts[0].toUpperCase();
                String argument = parts.length > 1 ? parts[1] : "";

                switch (command.toUpperCase()) {
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
                        return; // Terminer la session
                    default:
                        out.println("-ERR Unknown command");
                        break;
                }

            }
            // Si la boucle se termine, cela signifie que la connexion a été interrompue sans QUIT.
            if (authenticated) {
                System.err.println("La connexion a été interrompue sans recevoir QUIT. Les suppressions marquées ne seront pas appliquées.");
            }
        } catch (IOException e) {
            System.err.println("Erreur lors de la lecture de la connexion : " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException e) { /* Ignore */ }
        }
    }

    private void handleUser(String arg) {
    if (!UserStore.userExists(arg)) {
        out.println("-ERR User not found");
        return;
    }

    username = arg;
    userDir = new File("mailserver/" + username);

    // Vérifier ou créer le dossier utilisateur
    if (!userDir.exists()) {
        if (!userDir.mkdirs()) {
            username = null;
            userDir = null;
            out.println("-ERR Cannot create user directory");
            return;
        }
    } else if (!userDir.isDirectory()) {
        username = null;
        userDir = null;
        out.println("-ERR User path is not a directory");
        return;
    }

    out.println("+OK User accepted");
}

private void handlePass(String arg) {
    if (username == null) {
        out.println("-ERR USER required first");
        return;
    }

    if (!UserStore.authenticate(username, arg)) {
        out.println("-ERR Invalid password");
        return;
    }

    authenticated = true;

    File[] files = userDir.listFiles();
    if (files == null) {
        emails = new ArrayList<>();
    } else {
        emails = new ArrayList<>(Arrays.asList(files));
    }

    deletionFlags = new ArrayList<>();
    for (int i = 0; i < emails.size(); i++) {
        deletionFlags.add(false);
    }

    out.println("+OK Password accepted");
}



    private void handleStat() {
        if (!authenticated) {
            out.println("-ERR Authentication required");
            return;
        }
        long size = emails.stream().mapToLong(File::length).sum();
        out.println("+OK " + emails.size() + " " + size);
    }

    private void handleList() {
        if (!authenticated) {
            out.println("-ERR Authentication required");
            return;
        }
        out.println("+OK " + emails.size() + " messages");
        for (int i = 0; i < emails.size(); i++) {
            out.println((i + 1) + " " + emails.get(i).length());
        }
        out.println(".");
    }

    private void handleRetr(String arg) {
        if (!authenticated) {
            out.println("-ERR Authentication required");
            return;
        }
        try {
            int index = Integer.parseInt(arg) - 1;
            if (index < 0 || index >= emails.size()) {
                out.println("-ERR No such message");
                return;
            }
            File emailFile = emails.get(index);
            out.println("+OK " + emailFile.length() + " octets");
            BufferedReader reader = new BufferedReader(new FileReader(emailFile));
            String line;
            while ((line = reader.readLine()) != null) {
                out.println(line);
            }
            out.println(".");
            reader.close();
        } catch (Exception e) {
            out.println("-ERR Invalid message number");
        }
    }

    private void handleDele(String arg) {
        if (!authenticated) {
            out.println("-ERR Authentication required");
            return;
        }
        try {
            arg = arg.trim();
            int index = Integer.parseInt(arg) - 1; // Les messages sont numérotés à partir de 1
            if (index < 0 || index >= emails.size()) {
                out.println("-ERR No such message");
                return;
            }
            // Vérifier si le message est déjà marqué pour suppression
            if (deletionFlags.get(index)) {
                out.println("-ERR Message already marked for deletion");
                return;
            }
            // Marquer le message pour suppression (ne pas le supprimer tout de suite)
            deletionFlags.set(index, true);
            out.println("+OK Message marked for deletion");
        } catch (NumberFormatException nfe) {
            out.println("-ERR Invalid message number");
        } catch (Exception e) {
            out.println("-ERR Invalid message number");
        }
    }
    private void handleRset() {
        if (!authenticated) {
            out.println("-ERR Authentication required");
            return;
        }
        // Remise à zéro de tous les flags de suppression
        for (int i = 0; i < deletionFlags.size(); i++) {
            deletionFlags.set(i, false);
        }
        out.println("+OK Deletion marks reset");
    }




    private void handleQuit() {
        // Pour chaque email marqué pour suppression, supprimez le fichier
        for (int i = deletionFlags.size() - 1; i >= 0; i--) {
            if (deletionFlags.get(i)) {
                File emailFile = emails.get(i);
                if (emailFile.delete()) {
                    System.out.println("Deleted email: " + emailFile.getAbsolutePath());
                    // Optionnel : vous pouvez retirer l'email de la liste
                    emails.remove(i);
                    deletionFlags.remove(i);
                } else {
                    System.err.println("Failed to delete email: " + emailFile.getAbsolutePath());
                }
            }
        }
        out.println("+OK POP3 server signing off");
    }

}


class UserStore {

    private static final String USERS_FILE = "mailserver/users.json";
    private static final Map<String, String> users = new HashMap<>();

    static {
        loadUsers();
    }

    private static void loadUsers() {
        try {
            File file = new File(USERS_FILE);
            if (!file.exists()) {
                System.err.println("users.json not found");
                return;
            }

            String content = new String(
                    Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8
            );

            // Nettoyage basique du JSON
            content = content.trim();
            content = content.substring(1, content.length() - 1); // retire { }

            String[] entries = content.split(",");

            for (String entry : entries) {
                String[] pair = entry.split(":");
                if (pair.length == 2) {
                    String key = clean(pair[0]);
                    String value = clean(pair[1]);
                    users.put(key, value);
                }
            }

            System.out.println("Loaded users: " + users.keySet());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String clean(String s) {
        return s.trim()
                .replace("\"", "")
                .replace("{", "")
                .replace("}", "");
    }
    public static boolean userExists(String username) {
        return users.containsKey(username);
    }
    public static boolean authenticate(String username, String password) {
        return users.containsKey(username)
                && users.get(username).equals(password);
    }
}
