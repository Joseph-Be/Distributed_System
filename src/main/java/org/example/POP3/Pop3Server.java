package org.example.POP3;

import java.io.*;
import java.net.*;
import java.util.*;

import org.example.common.MailMessage;
import org.example.common.MailStore;
import org.example.common.ServerEventListener;
import org.example.rmi.RemoteUserStore;

// ── No more imports from org.example.database ────────────────────────────────
// Authentication is now done via the RMI token (same as IMAP).
// Emails are read from the file-store via MailStore.loadInbox(), and
// deletion is done by removing the file — no direct DB calls.

public class Pop3Server {
    // Le serveur utilise maintenant le port via le contrôleur
}

class Pop3Session extends Thread {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private String username;
    private String sessionToken;     // RMI token received as PASS argument
    private boolean authenticated;
    private Pop3ServerController controller;
    private ServerEventListener listener;
    private String clientId;

    // In-session email cache (MailMessage from MailStore / file-store)
    private List<MailMessage> emails;
    // Tracks file names of messages the client has marked for deletion
    private Set<String> markedForDeletion;

    public Pop3Session(Socket socket,
                       Pop3ServerController controller,
                       ServerEventListener listener,
                       String clientId) {
        this.socket            = socket;
        this.controller        = controller;
        this.listener          = listener;
        this.clientId          = clientId;
        this.authenticated     = false;
        this.markedForDeletion = new HashSet<>();
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            sendResponse("+OK POP3 server ready");

            String line;
            while ((line = in.readLine()) != null) {
                logCommand(line);

                String[] parts   = line.split(" ", 2);
                String command   = parts[0].toUpperCase();
                String argument  = parts.length > 1 ? parts[1] : "";

                switch (command) {
                    case "USER": handleUser(argument); break;
                    case "PASS": handlePass(argument); break;
                    case "STAT": handleStat();         break;
                    case "LIST": handleList(argument); break;
                    case "RETR": handleRetr(argument); break;
                    case "DELE": handleDele(argument); break;
                    case "RSET": handleRset();         break;
                    case "NOOP": handleNoop();         break;
                    case "QUIT": handleQuit(); return;
                    case "UIDL": handleUidl(argument); break;
                    case "TOP":  handleTop(argument);  break;
                    default:     sendResponse("-ERR Unknown command"); break;
                }
            }

            if (authenticated) {
                logger("Connection interrupted without QUIT. Deletions not applied.");
            }

        } catch (IOException e) {
            logger("Connection error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            controller.removeClient(socket, clientId);
        }
    }

    // ── USER username ────────────────────────────────────────────────────────
    // We only store the username here; we do NOT query the DB.
    // Existence is implicitly verified in PASS via validateToken().
    private void handleUser(String arg) {
        if (authenticated) {
            sendResponse("-ERR Already authenticated");
            return;
        }
        if (arg.isEmpty()) {
            sendResponse("-ERR Username required");
            return;
        }
        username = arg;
        sendResponse("+OK User accepted, send token as password");
    }

    // ── PASS <rmi-token> ─────────────────────────────────────────────────────
    // The client must pass the RMI token (returned by AuthService.login())
    // as the password — exactly the same contract IMAP uses.
    private void handlePass(String arg) {
        if (username == null) {
            sendResponse("-ERR USER required first");
            return;
        }
        if (authenticated) {
            sendResponse("-ERR Already authenticated");
            return;
        }
        if (arg.isEmpty()) {
            sendResponse("-ERR Token required");
            return;
        }

        // Validate via RMI — no direct DB call
        if (!RemoteUserStore.validateToken(username, arg)) {
            sendResponse("-ERR Authentication failed: invalid token");
            username = null;   // reset so client can try again
            return;
        }

        sessionToken  = arg;
        authenticated = true;

        // Load emails from file-store (same source as IMAP)
        loadEmails();
        markedForDeletion.clear();

        sendResponse("+OK Mailbox ready, " + emails.size() + " messages");
    }

    // ── STAT ─────────────────────────────────────────────────────────────────
    private void handleStat() {
        if (!authenticated) { sendResponse("-ERR Authentication required"); return; }
        refreshEmails();
        int  count     = getActiveCount();
        long totalSize = getTotalSize();
        sendResponse("+OK " + count + " " + totalSize);
    }

    // ── LIST [msg] ───────────────────────────────────────────────────────────
    private void handleList(String argument) {
        if (!authenticated) { sendResponse("-ERR Authentication required"); return; }
        refreshEmails();

        if (argument.isEmpty()) {
            sendResponse("+OK " + getActiveCount() + " messages");
            int index = 1;
            for (MailMessage m : emails) {
                if (!markedForDeletion.contains(m.fileName)) {
                    sendResponse(index + " " + m.fullContent.length());
                    index++;
                }
            }
            sendResponse(".");
        } else {
            try {
                int msgNum = Integer.parseInt(argument);
                MailMessage m = getByMsgNum(msgNum);
                if (m == null) sendResponse("-ERR No such message");
                else           sendResponse("+OK " + msgNum + " " + m.fullContent.length());
            } catch (NumberFormatException e) {
                sendResponse("-ERR Invalid message number");
            }
        }
    }

    // ── RETR msg ─────────────────────────────────────────────────────────────
    private void handleRetr(String argument) {
        if (!authenticated) { sendResponse("-ERR Authentication required"); return; }
        refreshEmails();

        try {
            int msgNum    = Integer.parseInt(argument);
            MailMessage m = getByMsgNum(msgNum);
            if (m == null) { sendResponse("-ERR No such message"); return; }

            sendResponse("+OK " + m.fullContent.length() + " octets");
            for (String line : m.fullContent.split("\r?\n")) {
                sendResponse(line.startsWith(".") ? "." + line : line);
            }
            sendResponse(".");

            // Mark as seen in the file-store (same mechanism as IMAP)
            m.seen = true;
            MailStore.persistSeen(username, m.fileName, true);

        } catch (NumberFormatException e) {
            sendResponse("-ERR Invalid message number");
        }
    }

    // ── DELE msg ─────────────────────────────────────────────────────────────
    private void handleDele(String argument) {
        if (!authenticated) { sendResponse("-ERR Authentication required"); return; }
        refreshEmails();

        try {
            int msgNum    = Integer.parseInt(argument);
            MailMessage m = getByMsgNum(msgNum);
            if (m == null) { sendResponse("-ERR No such message"); return; }

            if (markedForDeletion.contains(m.fileName)) {
                sendResponse("-ERR Message already marked for deletion");
                return;
            }
            markedForDeletion.add(m.fileName);
            sendResponse("+OK Message marked for deletion");

        } catch (NumberFormatException e) {
            sendResponse("-ERR Invalid message number");
        }
    }

    // ── RSET ─────────────────────────────────────────────────────────────────
    private void handleRset() {
        if (!authenticated) { sendResponse("-ERR Authentication required"); return; }
        markedForDeletion.clear();
        sendResponse("+OK Deletion marks reset");
    }

    // ── NOOP ─────────────────────────────────────────────────────────────────
    private void handleNoop() {
        if (!authenticated) { sendResponse("-ERR Authentication required"); return; }
        sendResponse("+OK");
    }

    // ── UIDL [msg] ───────────────────────────────────────────────────────────
    private void handleUidl(String argument) {
        if (!authenticated) { sendResponse("-ERR Authentication required"); return; }
        refreshEmails();

        if (argument.isEmpty()) {
            sendResponse("+OK");
            int index = 1;
            for (MailMessage m : emails) {
                if (!markedForDeletion.contains(m.fileName)) {
                    sendResponse(index + " " + m.uid);
                    index++;
                }
            }
            sendResponse(".");
        } else {
            try {
                int msgNum    = Integer.parseInt(argument);
                MailMessage m = getByMsgNum(msgNum);
                if (m == null) sendResponse("-ERR No such message");
                else           sendResponse("+OK " + msgNum + " " + m.uid);
            } catch (NumberFormatException e) {
                sendResponse("-ERR Invalid message number");
            }
        }
    }

    // ── TOP msg n ────────────────────────────────────────────────────────────
    private void handleTop(String argument) {
        if (!authenticated) { sendResponse("-ERR Authentication required"); return; }
        refreshEmails();

        String[] parts = argument.split(" ");
        if (parts.length < 2) { sendResponse("-ERR Syntax: TOP msg n"); return; }

        try {
            int msgNum    = Integer.parseInt(parts[0]);
            int lineCount = Integer.parseInt(parts[1]);
            MailMessage m = getByMsgNum(msgNum);
            if (m == null) { sendResponse("-ERR No such message"); return; }

            String[] lines = m.fullContent.split("\r?\n");

            // Find end of headers (blank line)
            int headerEnd = lines.length;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].isEmpty()) { headerEnd = i; break; }
            }

            sendResponse("+OK");
            for (int i = 0; i <= Math.min(headerEnd, lines.length - 1); i++) {
                sendResponse(lines[i]);
            }
            int bodyLines = Math.min(lineCount, lines.length - headerEnd - 1);
            for (int i = headerEnd + 1; i < headerEnd + 1 + bodyLines; i++) {
                sendResponse(lines[i]);
            }
            sendResponse(".");

        } catch (NumberFormatException e) {
            sendResponse("-ERR Invalid arguments");
        }
    }

    // ── QUIT ─────────────────────────────────────────────────────────────────
    // Applies deletions by removing files from the file-store — no DB call
    private void handleQuit() {
        if (authenticated) {
            for (String fileName : markedForDeletion) {
                File f = new File("shared/mailserver/" + username + "/" + fileName);
                if (f.exists() && f.delete()) {
                    logger("Deleted mail file: " + fileName);
                    // Also remove the .seen sidecar if present
                    new File(f.getPath() + ".seen").delete();
                } else {
                    logger("Failed to delete mail file: " + fileName);
                }
            }
            markedForDeletion.clear();
        }
        sendResponse("+OK POP3 server signing off");
    }

    // ── File-store helpers ────────────────────────────────────────────────────

    private void loadEmails() {
        try {
            emails = MailStore.loadInbox(username);
            logger("Loaded " + emails.size() + " emails from file-store");
        } catch (Exception e) {
            logger("Error loading emails: " + e.getMessage());
            emails = new ArrayList<>();
        }
    }

    private void refreshEmails() {
        try {
            List<MailMessage> fresh = MailStore.loadInbox(username);
            // Remove deletion marks for files that no longer exist
            Set<String> existingNames = new HashSet<>();
            for (MailMessage m : fresh) existingNames.add(m.fileName);
            markedForDeletion.retainAll(existingNames);
            emails = fresh;
        } catch (Exception e) {
            logger("Error refreshing emails: " + e.getMessage());
        }
    }

    private int getActiveCount() {
        int n = 0;
        for (MailMessage m : emails)
            if (!markedForDeletion.contains(m.fileName)) n++;
        return n;
    }

    private long getTotalSize() {
        long s = 0;
        for (MailMessage m : emails)
            if (!markedForDeletion.contains(m.fileName)) s += m.fullContent.length();
        return s;
    }

    /** Returns the N-th non-deleted message (1-based) */
    private MailMessage getByMsgNum(int msgNum) {
        int index = 1;
        for (MailMessage m : emails) {
            if (!markedForDeletion.contains(m.fileName)) {
                if (index == msgNum) return m;
                index++;
            }
        }
        return null;
    }

    // ── Logging helpers ───────────────────────────────────────────────────────

    private void logCommand(String message) {
        if (listener != null) listener.onLog(clientId + " -> " + message);
    }

    private void logResponse(String message) {
        if (listener != null) listener.onLog("server -> " + message);
    }

    private void logger(String message) {
        if (listener != null) listener.onLog(clientId + " ** " + message);
        System.out.println(message);
    }

    private void sendResponse(String message) {
        out.println(message);
        logResponse(message);
    }
}