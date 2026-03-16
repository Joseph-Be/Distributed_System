package org.example.rmi;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class RemoteUserStore {

    private static AuthService authService;

    private static AuthService getService() throws Exception {
        if (authService == null) {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            authService = (AuthService) registry.lookup("AuthService");
        }
        return authService;
    }

    public static String login(String username, String password) {
        try {
            return getService().login(username, password);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean validateToken(String username, String token) {
        try {
            return getService().validateToken(username, token);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean logout(String username, String token) {
        try {
            return getService().logout(username, token);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean userExists(String username) {
        try {
            return getService().userExists(username);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}