package org.example.SMTP;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.example.common.ServerEventListener;
import org.example.common.ServerLogger;
import org.example.database.EmailDAO;
import org.example.rmi.RemoteUserStore;

public class SmtpServer {
    // Le serveur utilise maintenant le port via le contrôleur
}

class SmtpSession extends Thread {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private final SmtpServerController controller;
    private final ServerEventListener listener;
    private final ServerLogger logger;
    private final String clientId;

    // ── State machine ────────────────────────────────────────────────────────
    private enum SmtpState {
        CONNECTED,       // Waiting for HELO/EHLO
        AUTHENTICATED,   // AUTH passed; ready for MAIL FROM
        HELO_RECEIVED,   // HELO/EHLO received (unauthenticated path kept for relay use)
        MAIL_FROM_SET,   // MAIL FROM processed; ready for RCPT TO
        RCPT_TO_SET,     // At least one RCPT TO received; ready for DATA
        DATA_RECEIVING   // Reading email body
    }

    private SmtpState state;
    private String sender;
    private String authedUser;   // username validated by RMI
    private String authedToken;  // RMI token used for this session
    private List<String> recipients;
    private StringBuilder dataBuffer;
    private String currentSubject;

    public SmtpSession(Socket socket, SmtpServerController controller,
                       ServerEventListener listener, String clientId) {
        this.socket = socket;
        this.state = SmtpState.CONNECTED;
        this.recipients = new ArrayList<>();
        this.dataBuffer = new StringBuilder();
        this.controller = controller;
        this.listener = listener;
        this.logger = new ServerLogger(listener);
        this.clientId = clientId;
        this.currentSubject = "";
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            sendResponse("220 mail.example.com Service Ready");
            logger.server("220 mail.example.com Service Ready");

            String line;
            while ((line = in.readLine()) != null) {
                logger.client(clientId, line);

                // ── DATA phase: accumulate body ──────────────────────────────
                if (state == SmtpState.DATA_RECEIVING) {
                    if (line.equals(".")) {
                        deliverEmail(dataBuffer.toString());
                        dataBuffer.setLength(0);
                        state = SmtpState.HELO_RECEIVED;
                        sendResponse("250 OK: Message accepted for delivery");
                    } else {
                        if (line.toLowerCase().startsWith("subject:")) {
                            currentSubject = line.substring(8).trim();
                        }
                        // Un-stuff leading dot per RFC 5321
                        dataBuffer.append(line.startsWith("..") ? line.substring(1) : line)
                                .append("\r\n");
                    }
                    continue;
                }

                // ── Command dispatch ─────────────────────────────────────────
                String command  = extractToken(line).toUpperCase();
                String argument = extractArgument(line);

                switch (command) {
                    case "HELO": case "EHLO": handleHelo(argument);       break;
                    case "AUTH":              handleAuth(argument);        break;
                    case "MAIL":              handleMailFrom(argument);    break;
                    case "RCPT":              handleRcptTo(argument);      break;
                    case "DATA":              handleData();                break;
                    case "QUIT":              handleQuit(); return;
                    case "RSET":              handleRset();                break;
                    case "NOOP":              sendResponse("250 OK");      break;
                    case "VRFY":              handleVrfy(argument);        break;
                    default:                  sendResponse("500 Command unrecognized"); break;
                }
            }

            if (state == SmtpState.DATA_RECEIVING) {
                logger.error("Connection interrupted during DATA phase. Email discarded.");
            }

        } catch (IOException e) {
            logger.error("SMTP session error: " + e.getMessage());
        } finally {
            try {
                socket.close();
                controller.removeClient(socket);
            } catch (IOException e) { /* ignore */ }
        }
    }

    // ── AUTH username token ──────────────────────────────────────────────────
    // The client must send:  AUTH <username> <rmi-token>
    // The RMI token is the one returned by AuthService.login().
    private void handleAuth(String arg) {
        String[] parts = arg.split(" ", 2);
        if (parts.length < 2) {
            sendResponse("501 Syntax: AUTH <username> <token>");
            return;
        }
        String username = parts[0];
        String token    = parts[1];

        if (!RemoteUserStore.validateToken(username, token)) {
            sendResponse("535 Authentication failed: invalid token");
            return;
        }

        authedUser  = username;
        authedToken = token;
        state       = SmtpState.AUTHENTICATED;
        sendResponse("235 Authentication successful");
        logger.info("SMTP AUTH OK for " + username);
    }

    // ── HELO / EHLO ──────────────────────────────────────────────────────────
    private void handleHelo(String arg) {
        // Reset envelope but keep auth state
        sender = "";
        recipients.clear();
        currentSubject = "";
        dataBuffer.setLength(0);

        if (state == SmtpState.AUTHENTICATED) {
            // Already authed: stay AUTHENTICATED so MAIL FROM is accepted
            sendResponse("250 Hello " + arg);
        } else {
            state = SmtpState.HELO_RECEIVED;
            sendResponse("250 Hello " + arg);
        }
    }

    // ── MAIL FROM ────────────────────────────────────────────────────────────
    private void handleMailFrom(String arg) {
        if (state != SmtpState.AUTHENTICATED && state != SmtpState.HELO_RECEIVED) {
            sendResponse("503 Bad sequence of commands");
            return;
        }

        // Require authentication before sending
        if (authedUser == null) {
            sendResponse("530 Authentication required");
            return;
        }

        Pattern pattern = Pattern.compile("^FROM:\\s*<([^>]+)>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(arg);
        if (!matcher.find()) {
            sendResponse("501 Syntax error in parameters or arguments");
            return;
        }

        String email = matcher.group(1).trim();
        if (!isValidEmail(email)) {
            sendResponse("501 Invalid email address");
            return;
        }

        // The authenticated user must match the sender (prevent spoofing)
        String senderUser = extractUsername(email);
        if (!senderUser.equals(authedUser)) {
            sendResponse("553 Sender does not match authenticated user");
            return;
        }

        sender = email;
        state  = SmtpState.MAIL_FROM_SET;
        sendResponse("250 OK");
    }

    // ── RCPT TO ──────────────────────────────────────────────────────────────
    private void handleRcptTo(String arg) {
        if (state != SmtpState.MAIL_FROM_SET && state != SmtpState.RCPT_TO_SET) {
            sendResponse("503 Bad sequence of commands");
            return;
        }

        Pattern pattern = Pattern.compile("^TO:\\s*<([^>]+)>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(arg);
        if (!matcher.find()) {
            sendResponse("501 Syntax error in parameters or arguments");
            return;
        }

        String email = matcher.group(1).trim();
        if (!isValidEmail(email)) {
            sendResponse("501 Invalid email address");
            return;
        }

        // Validate recipient exists via RMI (no direct DB)
        String recipientUser = extractUsername(email);
        if (!RemoteUserStore.userExists(recipientUser)) {
            sendResponse("550 No such user here");
            return;
        }

        recipients.add(email);
        state = SmtpState.RCPT_TO_SET;
        sendResponse("250 OK");
    }

    // ── DATA ─────────────────────────────────────────────────────────────────
    private void handleData() {
        if (state != SmtpState.RCPT_TO_SET || recipients.isEmpty()) {
            sendResponse("503 Bad sequence of commands");
            return;
        }
        state          = SmtpState.DATA_RECEIVING;
        currentSubject = "";
        sendResponse("354 Start mail input; end with <CRLF>.<CRLF>");
    }

    // ── QUIT ─────────────────────────────────────────────────────────────────
    private void handleQuit() {
        sendResponse("221 mail.example.com Service closing transmission channel");
    }

    // ── RSET ─────────────────────────────────────────────────────────────────
    private void handleRset() {
        // Reset envelope; keep auth
        sender = "";
        recipients.clear();
        currentSubject = "";
        dataBuffer.setLength(0);
        state = (authedUser != null) ? SmtpState.AUTHENTICATED : SmtpState.HELO_RECEIVED;
        sendResponse("250 OK");
    }

    // ── VRFY ─────────────────────────────────────────────────────────────────
    // Uses RMI — no direct DB call
    private void handleVrfy(String arg) {
        String username = extractUsername(arg);
        if (RemoteUserStore.userExists(username)) {
            sendResponse("250 " + arg);
        } else {
            sendResponse("550 User not found");
        }
    }

    // ── Email delivery via database ───────────────────────────────────────────
    // Stores one row per recipient using EmailDAO.storeEmail() → stored procedure.
    private void deliverEmail(String rawData) {
        if (sender == null || recipients.isEmpty()) {
            logger.error("Cannot deliver email: missing sender or recipients");
            return;
        }

        if (currentSubject.isEmpty()) {
            // Tente d'extraire le Subject depuis les headers du rawData (fallback)
            currentSubject = extractSubject(rawData);
        }

        for (String recipient : recipients) {
            int emailId = EmailDAO.storeEmail(sender, recipient, currentSubject, rawData);
            if (emailId > 0) {
                logger.info("Email stored in DB for " + recipient + " (ID: " + emailId + ")");
                listener.onLog("[SMTP] Delivered: " + sender + " → " + recipient);
            } else {
                logger.error("Failed to store email in DB for " + recipient);
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String extractSubject(String data) {
        for (String line : data.split("\r?\n")) {
            if (line.toLowerCase().startsWith("subject:")) {
                return line.substring(8).trim();
            }
        }
        return "No Subject";
    }

    private String extractToken(String line) {
        String[] parts = line.split(" ");
        return parts.length > 0 ? parts[0] : "";
    }

    private String extractArgument(String line) {
        int index = line.indexOf(' ');
        return index > 0 ? line.substring(index).trim() : "";
    }

    private boolean isValidEmail(String email) {
        return Pattern.compile("^[A-Za-z0-9+_.-]+@.+$").matcher(email).matches();
    }

    private String extractUsername(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private void sendResponse(String response) {
        out.println(response);
        logger.server(response);
    }
}