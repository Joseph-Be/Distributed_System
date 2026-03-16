package org.example.rmi;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class AuthServer {
    public static void main(String[] args) {
        try {
            System.setProperty("java.rmi.server.hostname", "localhost");
            AuthService service = new AuthServiceImpl();

            Registry registry = LocateRegistry.createRegistry(1099);
            registry.rebind("AuthService", service);

            System.out.println("RMI Auth Server started on port 1099");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}