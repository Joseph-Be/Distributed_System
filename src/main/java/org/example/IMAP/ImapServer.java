package org.example.IMAP;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.example.database.EmailDAO;
import org.example.database.MailMessageDB;
import org.example.common.ServerEventListener;
import org.example.rmi.RemoteUserStore;

// ── No more MailStore / MailMessage / File I/O ────────────────────────────────
// All mailbox data now comes from the database via EmailDAO.
// MailMessageDB replaces MailMessage as the session's message type.

public class ImapServer {
    private static final int PORT = 1143;
}

class ImapSession extends Thread {
    private final Socket socket;
    private final ImapServerController controller;
    private final ServerEventListener listener;
    private final String clientId;

    private BufferedReader in;
    private BufferedWriter out;

    private enum ImapState {
        NOT_AUTHENTICATED,
        AUTHENTICATED,
        SELECTED,
        LOGOUT
    }

    private ImapState state = ImapState.NOT_AUTHENTICATED;

    private String currentUser = null;
    private String selectedMailbox = null;
    // Now backed by DB rows instead of flat files
    private List<MailMessageDB> selectedMessages = new ArrayList<>();

    public ImapSession(Socket socket, ImapServerController controller,
                       ServerEventListener listener, String clientId) {
        this.socket = socket;
        this.controller = controller;
        this.listener = listener;
        this.clientId = clientId;
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(),  StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            listener.onLog(clientId + " connecté");
            sendLine("* OK IMAP Service Ready");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                listener.onLog(clientId + " -> " + line);

                String[] parts = splitOnce(line);
                if (parts == null) { sendLine("* BAD Missing tag"); continue; }

                String tag  = parts[0];
                String rest = parts[1];

                String[] cmdParts = splitOnce(rest);
                String cmd  = (cmdParts == null ? rest      : cmdParts[0]).toUpperCase(Locale.ROOT);
                String args = (cmdParts == null ? ""        : cmdParts[1]);

                switch (cmd) {
                    case "LOGIN":  handleLogin(tag, args);  break;
                    case "SELECT": handleSelect(tag, args); break;
                    case "FETCH":  handleFetch(tag, args);  break;
                    case "STORE":  handleStore(tag, args);  break;
                    case "SEARCH": handleSearch(tag, args); break;
                    case "LOGOUT": handleLogout(tag); return;
                    default:       sendTagged(tag, "BAD", "Command unknown or arguments invalid"); break;
                }
            }
        } catch (IOException e) {
            listener.onLog("Erreur IMAP: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
            controller.removeClient(socket, clientId);
        }
    }

    // ── LOGIN <username> <rmi-token> ─────────────────────────────────────────
    private void handleLogin(String tag, String args) throws IOException {
        if (state != ImapState.NOT_AUTHENTICATED) {
            sendTagged(tag, "BAD", "LOGIN not allowed in this state");
            return;
        }

        List<String> tokens = tokenizeImapArgs(args);
        if (tokens.size() < 2) {
            sendTagged(tag, "BAD", "LOGIN requires username and password");
            return;
        }

        String user = tokens.get(0);
        String pass = tokens.get(1);  // RMI token

        // Validate via RMI — no direct DB call
        if (!RemoteUserStore.validateToken(user, pass)) {
            sendTagged(tag, "NO", "Authentication failed");
            return;
        }

        currentUser = user;
        state = ImapState.AUTHENTICATED;
        sendTagged(tag, "OK", "LOGIN completed");
    }

    // ── SELECT <mailbox> ──────────────────────────────────────────────────────
    // Loads the inbox from the database via EmailDAO.fetchEmails()
    private void handleSelect(String tag, String args) throws IOException {
        if (state != ImapState.AUTHENTICATED && state != ImapState.SELECTED) {
            sendTagged(tag, "BAD", "SELECT not allowed in this state");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            sendTagged(tag, "BAD", "SELECT requires mailbox name");
            return;
        }

        String mailbox = stripQuotes(args.trim());

        if (!mailbox.equalsIgnoreCase("INBOX")) {
            selectedMailbox  = null;
            selectedMessages = new ArrayList<>();
            state = ImapState.AUTHENTICATED;
            sendTagged(tag, "NO", "No such mailbox");
            return;
        }

        if (state == ImapState.SELECTED && selectedMailbox != null
                && !selectedMailbox.equalsIgnoreCase(mailbox)) {
            sendLine("* OK [CLOSED] Previous mailbox is now closed");
        }

        selectedMailbox  = "INBOX";
        // ── DB call replaces MailStore.loadInbox() ───────────────────────────
        selectedMessages = EmailDAO.fetchEmails(currentUser);

        sendLine("* " + selectedMessages.size() + " EXISTS");
        sendLine("* FLAGS (\\Seen)");
        sendLine("* LIST () \"/\" INBOX");

        state = ImapState.SELECTED;
        sendTagged(tag, "OK", "SELECT completed, " + selectedMessages.size() + " messages");
    }

    // ── FETCH <seq> <items> ───────────────────────────────────────────────────
    private void handleFetch(String tag, String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, "BAD", "FETCH not allowed in this state");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            sendTagged(tag, "BAD", "FETCH requires arguments");
            return;
        }

        String[] firstSplit = splitOnce(args.trim());
        if (firstSplit == null) {
            sendTagged(tag, "BAD", "FETCH requires sequence and data items");
            return;
        }

        // Support range "1:N" as well as single seq number
        String seqArg = firstSplit[0].trim();
        String items  = firstSplit[1].trim();

        if (seqArg.contains(":")) {
            // Range fetch (e.g. "1:5" or "1:*")
            handleFetchRange(tag, seqArg, items);
        } else {
            int seq;
            try { seq = parseSeq(seqArg); }
            catch (NumberFormatException e) {
                sendTagged(tag, "BAD", "Invalid message sequence number");
                return;
            }
            handleFetchSingle(tag, seq, items);
        }

        sendTagged(tag, "OK", "FETCH completed");
    }

    private void handleFetchRange(String tag, String range, String items) throws IOException {
        String[] bounds = range.split(":", 2);
        int from = 1;
        int to   = selectedMessages.size();
        try { from = Integer.parseInt(bounds[0].trim()); } catch (NumberFormatException ignored) {}
        if (!bounds[1].trim().equals("*")) {
            try { to = Integer.parseInt(bounds[1].trim()); } catch (NumberFormatException ignored) {}
        }
        to = Math.min(to, selectedMessages.size());

        for (int seq = from; seq <= to; seq++) {
            MailMessageDB msg = getBySeq(seq);
            if (msg != null) sendFetchResponse(seq, msg, items);
        }
    }

    private void handleFetchSingle(String tag, int seq, String items) throws IOException {
        MailMessageDB msg = getBySeq(seq);
        if (msg == null) {
            sendTagged(tag, "BAD", "Invalid message sequence number");
            return;
        }
        sendFetchResponse(seq, msg, items);
    }

    // Builds and sends the FETCH response for one message
    private void sendFetchResponse(int seq, MailMessageDB msg, String items) throws IOException {
        String itemsUp    = items.toUpperCase(Locale.ROOT);
        boolean wantFlags = itemsUp.contains("FLAGS");
        boolean wantEnv   = itemsUp.contains("ENVELOPE");
        boolean wantBody  = itemsUp.contains("BODY[]");
        boolean wantHdr   = itemsUp.contains("BODY[HEADER]");

        if (!wantFlags && !wantEnv && !wantBody && !wantHdr) {
            sendTagged("*", "BAD", "Unsupported FETCH data item");
            return;
        }

        // FLAGS-only path (no literal needed)
        if (wantFlags && !wantEnv && !wantBody && !wantHdr) {
            sendLine("* " + seq + " FETCH (FLAGS (" + (msg.isSeen() ? "\\Seen" : "\\Unseen") + "))");
            return;
        }

        // Build prefix parts
        StringBuilder prefix = new StringBuilder();
        prefix.append("* ").append(seq).append(" FETCH (");

        if (wantFlags) {
            prefix.append("FLAGS (").append(msg.isSeen() ? "\\Seen" : "\\Unseen").append(") ");
        }

        if (wantEnv) {
            // ENVELOPE: (date subject from sender reply-to to cc bcc in-reply-to message-id)
            String date    = msg.getSentDate() != null ? msg.getSentDate().toString() : "NIL";
            String subject = msg.getSubject()  != null ? "\"" + msg.getSubject()  + "\"" : "NIL";
            String from    = msg.getFromAddress() != null ? "\"" + msg.getFromAddress() + "\"" : "NIL";
            prefix.append("ENVELOPE (\"").append(date).append("\" ")
                    .append(subject).append(" ")
                    .append(from).append(" NIL NIL NIL NIL NIL NIL NIL) ");
        }

        // If body is needed, send as literal
        if (wantBody || wantHdr) {
            String body  = msg.toEmailFormat();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            String dataItem = wantBody ? "BODY[]" : "BODY[HEADER]";
            prefix.append(dataItem).append(" {").append(bytes.length).append("}");

            out.write(prefix.toString());
            out.write("\r\n");
            out.write(body);
            out.write(")\r\n");
            out.flush();
            listener.onLog("Serveur -> * " + seq + " FETCH (...)");
        } else {
            // No literal — close the parenthesis inline
            // Remove trailing space before closing
            String line = prefix.toString().stripTrailing() + ")";
            sendLine(line);
        }
    }

    // ── STORE <seq> <+FLAGS|-FLAGS|FLAGS> (<flag>) ────────────────────────────
    // Persists the seen flag to the database via EmailDAO.markEmailSeen()
    private void handleStore(String tag, String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, "BAD", "STORE not allowed in this state");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            sendTagged(tag, "BAD", "STORE requires arguments");
            return;
        }

        Pattern p = Pattern.compile("^([0-9]+)\\s+(\\+FLAGS|-FLAGS|FLAGS)\\s+\\(([^)]*)\\)\\s*$",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(args.trim());

        if (!m.matches()) {
            sendTagged(tag, "BAD", "Unsupported STORE syntax");
            return;
        }

        int    seq   = Integer.parseInt(m.group(1));
        String op    = m.group(2).toUpperCase(Locale.ROOT);
        String flags = m.group(3).trim().toUpperCase(Locale.ROOT);

        if (!flags.contains("\\SEEN") && !flags.contains("\\DELETED")) {
            sendTagged(tag, "BAD", "Only \\Seen and \\Deleted flags are supported");
            return;
        }

        MailMessageDB msg = getBySeq(seq);
        if (msg == null) {
            sendTagged(tag, "BAD", "Invalid message sequence number");
            return;
        }

        if (flags.contains("\\SEEN")) {
            boolean newSeen = !op.equals("-FLAGS");
            msg.setSeen(newSeen);
            // ── DB call replaces MailStore.persistSeen() ─────────────────────
            EmailDAO.markEmailSeen(msg.getId(), currentUser);
        }

        if (flags.contains("\\DELETED")) {
            // ── DB call replaces file deletion ───────────────────────────────
            EmailDAO.deleteEmail(msg.getId(), currentUser);
            selectedMessages.remove(msg);
            // Re-number will be reflected on next SELECT
            sendLine("* " + seq + " EXPUNGE");
            sendTagged(tag, "OK", "STORE completed");
            return;
        }

        sendLine("* " + seq + " FETCH (FLAGS (" + (msg.isSeen() ? "\\Seen" : "") + "))");
        sendTagged(tag, "OK", "STORE completed");
    }

    // ── SEARCH <criteria> ────────────────────────────────────────────────────
    private void handleSearch(String tag, String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, "BAD", "SEARCH not allowed in this state");
            return;
        }

        List<String> tokens = tokenizeImapArgs(args == null ? "" : args.trim());
        if (tokens.isEmpty()) {
            sendTagged(tag, "BAD", "SEARCH requires criteria");
            return;
        }

        String key   = tokens.get(0).toUpperCase(Locale.ROOT);
        String value = tokens.size() >= 2 ? tokens.get(1) : null;

        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < selectedMessages.size(); i++) {
            int seq          = i + 1;
            MailMessageDB msg = selectedMessages.get(i);

            boolean ok;
            switch (key) {
                case "ALL":     ok = true; break;
                case "SEEN":    ok = msg.isSeen(); break;
                case "UNSEEN":  ok = !msg.isSeen(); break;
                case "SUBJECT":
                    ok = value != null && msg.getSubject() != null &&
                            msg.getSubject().toLowerCase(Locale.ROOT)
                                    .contains(value.toLowerCase(Locale.ROOT));
                    break;
                case "FROM":
                    ok = value != null && msg.getFromAddress() != null &&
                            msg.getFromAddress().toLowerCase(Locale.ROOT)
                                    .contains(value.toLowerCase(Locale.ROOT));
                    break;
                default:
                    sendTagged(tag, "BAD", "Unsupported SEARCH key");
                    return;
            }

            if (ok) matches.add(seq);
        }

        StringBuilder sb = new StringBuilder("* SEARCH");
        for (int seq : matches) sb.append(" ").append(seq);
        sendLine(sb.toString());
        sendTagged(tag, "OK", "SEARCH completed");
    }

    // ── LOGOUT ────────────────────────────────────────────────────────────────
    private void handleLogout(String tag) throws IOException {
        state = ImapState.LOGOUT;
        sendLine("* BYE IMAP Server logging out");
        sendTagged(tag, "OK", "LOGOUT completed");
        out.flush();
        socket.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the message at 1-based sequence number, or null if out of range. */
    private MailMessageDB getBySeq(int seq) {
        if (seq < 1 || seq > selectedMessages.size()) return null;
        return selectedMessages.get(seq - 1);
    }

    private int parseSeq(String s) {
        s = s.trim();
        if (s.equals("*")) return selectedMessages.size();
        return Integer.parseInt(s);
    }

    private static List<String> tokenizeImapArgs(String args) {
        List<String> tokens = new ArrayList<>();
        if (args == null) return tokens;
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        for (char c : args.toCharArray()) {
            if (c == '"') { inQuote = !inQuote; continue; }
            if (!inQuote && Character.isWhitespace(c)) {
                if (cur.length() > 0) { tokens.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens;
    }

    private void sendLine(String s) throws IOException {
        out.write(s);
        out.write("\r\n");
        out.flush();
        listener.onLog("Serveur -> " + s);
    }

    private void sendTagged(String tag, String status, String text) throws IOException {
        sendLine(tag + " " + status + " " + text);
    }

    private static String[] splitOnce(String s) {
        int idx = s.indexOf(' ');
        if (idx < 0) return null;
        return new String[]{s.substring(0, idx), s.substring(idx + 1)};
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            return s.substring(1, s.length() - 1);
        return s;
    }
}