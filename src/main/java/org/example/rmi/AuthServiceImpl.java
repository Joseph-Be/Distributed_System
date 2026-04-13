package org.example.rmi;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.example.database.UserDAO;

// ── No more File / Files / shared/users.json ─────────────────────────────────
// All user persistence is now handled by UserDAO → MySQL (mail_system DB).
// Active tokens are still kept in memory: they are session-scoped and do not
// need to survive a server restart (clients simply re-login).

public class AuthServiceImpl extends UnicastRemoteObject implements AuthService {

    // username → token  (in-memory session store, intentionally not persisted)
    private final Map<String, String> activeTokens = new HashMap<>();

    public AuthServiceImpl() throws RemoteException {
        super();
    }

    // ── getAllUsers ───────────────────────────────────────────────────────────
    @Override
    public synchronized List<String> getAllUsers() throws RemoteException {
        return UserDAO.getAllUsers();
    }

    // ── login ─────────────────────────────────────────────────────────────────
    // Authenticates against the DB via UserDAO.authenticate(), then issues a
    // UUID token stored in memory for the lifetime of the session.
    @Override
    public synchronized String login(String username, String password) throws RemoteException {
        if (!UserDAO.authenticate(username, password)) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        activeTokens.put(username, token);
        return token;
    }

    // ── validateToken ─────────────────────────────────────────────────────────
    @Override
    public synchronized boolean validateToken(String username, String token) throws RemoteException {
        String stored = activeTokens.get(username);
        return stored != null && stored.equals(token);
    }

    // ── logout ────────────────────────────────────────────────────────────────
    @Override
    public synchronized boolean logout(String username, String token) throws RemoteException {
        if (!validateToken(username, token)) {
            return false;
        }
        activeTokens.remove(username);
        return true;
    }

    // ── userExists ────────────────────────────────────────────────────────────
    @Override
    public synchronized boolean userExists(String username) throws RemoteException {
        return UserDAO.userExists(username);
    }

    // ── createUser ────────────────────────────────────────────────────────────
    @Override
    public synchronized boolean createUser(String username, String password) throws RemoteException {
        return UserDAO.createUser(username, password);
    }

    // ── updateUser ────────────────────────────────────────────────────────────
    @Override
    public synchronized boolean updateUser(String username, String newPassword) throws RemoteException {
        return UserDAO.updateUserPassword(username, newPassword);
    }

    // ── deleteUser ────────────────────────────────────────────────────────────
    // Delegates to UserDAO which soft-deletes (sets status = 'disabled').
    // Also invalidates any active session token for that user.
    @Override
    public synchronized boolean deleteUser(String username) throws RemoteException {
        boolean deleted = UserDAO.deleteUser(username);
        if (deleted) {
            activeTokens.remove(username);
        }
        return deleted;
    }
}