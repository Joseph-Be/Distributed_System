package org.example.rmi;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class AuthLoginTest {

    public static void main(String[] args) {

        try {

            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            AuthService auth = (AuthService) registry.lookup("AuthService");

            String token = auth.login("youcef", "youcef123");

            if (token != null) {
                System.out.println("Login success");
                System.out.println("Token: " + token);
            } else {
                System.out.println("Login failed");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}