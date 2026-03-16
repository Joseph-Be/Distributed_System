package org.example.common;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.nio.file.Files;

public class UserStore {

    private static final String USERS_FILE = "shared/mailserver/users.json";
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
