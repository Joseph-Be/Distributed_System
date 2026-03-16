package org.example.rmi;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

public interface AuthService extends Remote {

    String login(String username, String password) throws RemoteException;

    boolean validateToken(String username, String token) throws RemoteException;

    boolean logout(String username, String token) throws RemoteException;

    boolean userExists(String username) throws RemoteException;

    boolean createUser(String username, String password) throws RemoteException;

    boolean updateUser(String username, String newPassword) throws RemoteException;

    boolean deleteUser(String username) throws RemoteException;

    List<String> getAllUsers() throws RemoteException;
}