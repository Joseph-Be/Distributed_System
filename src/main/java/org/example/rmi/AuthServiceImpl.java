package org.example.rmi;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {
    
    private final File usersFile;
    private Map<String, String> users = new HashMap<String, String>();
    private Map<String, String> activeTokens = new HashMap<String, String>();

    public AuthServiceImpl() throws RemoteException {
        super();
        usersFile = new File("shared/users.json");
        users = loadUsers();
    }

    @Override
    public synchronized List<String> getAllUsers() throws RemoteException {
        return new ArrayList<>(users.keySet());
    }

    public synchronized boolean authenticate(String username, String password) {
        return users.containsKey(username) && users.get(username).equals(password);
    }

    public synchronized boolean userExists(String username) {
        return users.containsKey(username);
    }

    public synchronized boolean createUser(String username, String password) {
        if (users.containsKey(username)) return false;
        users.put(username, password);
        saveUsers();
        return true;
    }

    public synchronized boolean updateUser(String username, String newPassword) {
        if (!users.containsKey(username)) return false;
        users.put(username, newPassword);
        saveUsers();
        return true;
    }

    public synchronized boolean deleteUser(String username) {
        if (!users.containsKey(username)) return false;
        users.remove(username);
        saveUsers();
        return true;
    }

    @Override
    public synchronized String login(String username, String password) throws RemoteException {
        if (!users.containsKey(username)) {
            return null;
        }

        if (!users.get(username).equals(password)) {
            return null;
        }

        String token = java.util.UUID.randomUUID().toString();
        activeTokens.put(username, token);
        return token;
    }

    @Override
    public synchronized boolean validateToken(String username, String token) throws RemoteException {
        if (!activeTokens.containsKey(username)) {
            return false;
        }

        return activeTokens.get(username).equals(token);
    }

    @Override
    public synchronized boolean logout(String username, String token) throws RemoteException {
        if (!validateToken(username, token)) {
            return false;
        }

        activeTokens.remove(username);
        return true;
    }

    private Map<String, String> loadUsers() {
        Map<String, String> map = new HashMap<>();

        try {
            if (!usersFile.exists()) return map;

            String content = new String(
                    Files.readAllBytes(usersFile.toPath()),
                    StandardCharsets.UTF_8
            );

            content = content.trim();

            if (content.startsWith("{") && content.endsWith("}")) {
                content = content.substring(1, content.length() - 1);
            }

            if (!content.trim().isEmpty()) {

                String[] entries = content.split(",");

                for (String entry : entries) {
                    String[] pair = entry.split(":");

                    if (pair.length == 2) {
                        String key = clean(pair[0]);
                        String value = clean(pair[1]);
                        map.put(key, value);
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return map;
    }

    private void saveUsers() {
        try {

            StringBuilder sb = new StringBuilder();
            sb.append("{\n");

            int i = 0;
            for (Map.Entry<String, String> entry : users.entrySet()) {

                sb.append("  \"")
                  .append(entry.getKey())
                  .append("\": \"")
                  .append(entry.getValue())
                  .append("\"");

                if (i < users.size() - 1) sb.append(",");

                sb.append("\n");
                i++;
            }

            sb.append("}");

            Files.write(usersFile.toPath(),
                        sb.toString().getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String clean(String s) {
        return s.trim().replace("\"", "");
    }
}