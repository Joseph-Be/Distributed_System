package org.example.IMAP;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.example.common.MailMessage;
import org.example.common.MailStore;
import org.example.common.ServerEventListener;
import org.example.rmi.RemoteUserStore;

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
    private List<MailMessage> selectedMessages = new ArrayList<MailMessage>();

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
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            listener.onLog(clientId + " connecté");
            sendLine("* OK IMAP Service Ready");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                listener.onLog(clientId + " -> " + line);
                

                String[] parts = splitOnce(line);
                if (parts == null) {
                    sendLine("* BAD Missing tag");
                    continue;
                }

                String tag = parts[0];
                String rest = parts[1];

                String[] cmdParts = splitOnce(rest);
                String cmd = (cmdParts == null ? rest : cmdParts[0]).toUpperCase(Locale.ROOT);
                String args = (cmdParts == null ? "" : cmdParts[1]);

                switch (cmd) {
                    case "LOGIN":
                        handleLogin(tag, args);
                        break;
                    case "SELECT":
                        handleSelect(tag, args);
                        break;
                    case "FETCH":
                        handleFetch(tag, args);
                        break;
                    case "STORE":
                        handleStore(tag, args);
                        break;
                    case "SEARCH":
                        handleSearch(tag, args);
                        break;
                    case "LOGOUT":
                        handleLogout(tag);
                        return;
                    default:
                        sendTagged(tag, "BAD", "Command unknown or arguments invalid");
                        break;
                }
            }
        } catch (IOException e) {
            listener.onLog("Erreur IMAP: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            controller.removeClient(socket, clientId);
        }
    }

    private void handleLogin(String tag, String args) throws IOException {
        if (state != ImapState.NOT_AUTHENTICATED) {
            sendTagged(tag, " BAD", "LOGIN not allowed in this state");
            return;
        }

        List<String> tokens = tokenizeImapArgs(args);
        if (tokens.size() < 2) {
            sendTagged(tag, " BAD", "LOGIN requires username and password");
            return;
        }

        String user = tokens.get(0);
        String pass = tokens.get(1);

        if (!RemoteUserStore.validateToken(user, pass)) {
            sendTagged(tag, "NO", "Authentication failed");
            return;
        }

        File dir = new File("shared/mailserver/" + user);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        currentUser = user;
        state = ImapState.AUTHENTICATED;
        sendTagged(tag, " OK", "LOGIN completed");
    }

    private void handleSelect(String tag, String args) throws IOException {
        if (state != ImapState.AUTHENTICATED && state != ImapState.SELECTED) {
            sendTagged(tag, " BAD", "SELECT not allowed in this state");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            sendTagged(tag, " BAD", "SELECT requires mailbox name");
            return;
        }

        String mailbox = stripQuotes(args.trim());

        if (!mailbox.equalsIgnoreCase("INBOX")) {
            selectedMailbox = null;
            selectedMessages = new ArrayList<MailMessage>();
            state = ImapState.AUTHENTICATED;
            sendTagged(tag, " NO", "No such mailbox");
            return;
        }

        if (state == ImapState.SELECTED && selectedMailbox != null
                && !selectedMailbox.equalsIgnoreCase(mailbox)) {
            sendLine("* OK [CLOSED] Previous mailbox is now closed");
        }

        selectedMailbox = "INBOX";
        selectedMessages = MailStore.loadInbox(currentUser);

        sendLine("* " + selectedMessages.size() + " EXISTS");
        sendLine("* FLAGS (\\Seen)");
        sendLine("* LIST () \"/\" INBOX");

        state = ImapState.SELECTED;
        sendTagged(tag, " OK", "SELECT completed, " + selectedMessages.size() + " messages");
    }

    private void handleFetch(String tag, String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, " BAD", "FETCH not allowed in this state");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            sendTagged(tag, " BAD", "FETCH requires arguments");
            return;
        }

        String[] firstSplit = splitOnce(args.trim());
        if (firstSplit == null) {
            sendTagged(tag, " BAD", "FETCH requires sequence and data items");
            return;
        }

        int seq;
        try {
            seq = parseSeq(firstSplit[0]);
        } catch (NumberFormatException e) {
            sendTagged(tag, " BAD", "Invalid message sequence number");
            return;
        }

        String items = firstSplit[1].trim();
        MailMessage msg = getBySeq(seq);

        if (msg == null) {
            sendTagged(tag, " BAD", "Invalid message sequence number");
            return;
        }

        boolean wantFlags = items.toUpperCase(Locale.ROOT).contains("FLAGS");
        boolean wantHeader = items.toUpperCase(Locale.ROOT).contains("BODY[HEADER]");
        boolean wantBodyAll = items.toUpperCase(Locale.ROOT).contains("BODY[]");

        if (!wantFlags && !wantHeader && !wantBodyAll) {
            sendTagged(tag, " BAD", "Unsupported FETCH data item");
            return;
        }

        if (wantHeader || wantBodyAll) {
            sendFetchWithLiteral(seq, msg, wantFlags, wantHeader, wantBodyAll);
        } else {
            StringBuilder resp = new StringBuilder();
            resp.append("* ").append(seq).append(" FETCH (");
            resp.append("FLAGS (").append(msg.seen ? "\\Seen" : "\\Unseen").append(")");
            resp.append(")");
            sendLine(resp.toString());
        }

        sendTagged(tag, " OK", "FETCH completed");
    }

    private void sendFetchWithLiteral(int seq, MailMessage msg, boolean wantFlags,
                                      boolean wantHeader, boolean wantBodyAll) throws IOException {
        if (wantHeader) {
            String header = msg.headersOnly();
            byte[] bytes = header.getBytes(StandardCharsets.UTF_8);

            StringBuilder prefix = new StringBuilder();
            prefix.append("* ").append(seq).append(" FETCH (");
            if (wantFlags) {
                prefix.append("FLAGS (").append(msg.seen ? "\\Seen" : "\\Unseen").append(") ");
            }
            prefix.append("BODY[HEADER] {").append(bytes.length).append("}");

            out.write(prefix.toString());
            out.write("\r\n");
            out.write(header);

            if (wantBodyAll) {
                String body = msg.fullContent;
                byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                out.write(" BODY[] {" + bodyBytes.length + "}\r\n");
                out.write(body);
            }

            out.write(")\r\n");
            out.flush();
            listener.onLog("Serveur -> * " + seq + " FETCH (...)");
            return;
        }

        if (wantBodyAll) {
            String body = msg.fullContent;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            StringBuilder prefix = new StringBuilder();
            prefix.append("* ").append(seq).append(" FETCH (");
            if (wantFlags) {
                prefix.append("FLAGS (").append(msg.seen ? "\\Seen" : "\\Unseen").append(") ");
            }
            prefix.append("BODY[] {").append(bytes.length).append("}");

            out.write(prefix.toString());
            out.write("\r\n");
            out.write(body);
            out.write(")\r\n");
            out.flush();
            listener.onLog("Serveur -> * " + seq + " FETCH (...)");
        }
    }

    private void handleStore(String tag, String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, " BAD", "STORE not allowed in this state");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            sendTagged(tag, " BAD", "STORE requires arguments");
            return;
        }

        Pattern p = Pattern.compile("^([0-9]+)\\s+(\\+FLAGS|-FLAGS|FLAGS)\\s+\\(([^)]*)\\)\\s*$",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(args.trim());

        if (!m.matches()) {
            sendTagged(tag, " BAD", "Unsupported STORE syntax");
            return;
        }

        int seq = Integer.parseInt(m.group(1));
        String op = m.group(2).toUpperCase(Locale.ROOT);
        String flags = m.group(3).trim().toUpperCase(Locale.ROOT);

        if (!flags.contains("\\SEEN")) {
            sendTagged(tag, " BAD", "Only \\Seen flag is supported");
            return;
        }

        MailMessage msg = getBySeq(seq);
        if (msg == null) {
            sendTagged(tag, " BAD", "Invalid message sequence number");
            return;
        }

        if (op.equals("+FLAGS") || op.equals("FLAGS")) {
            msg.seen = true;
        } else if (op.equals("-FLAGS")) {
            msg.seen = false;
        }

        MailStore.persistSeen(currentUser, msg.fileName, msg.seen);
        sendLine("* " + seq + " FETCH (FLAGS (" + (msg.seen ? "\\Seen" : "") + "))");
        sendTagged(tag, " OK", "STORE completed");
    }

    private void handleSearch(String tag, String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, " BAD", "SEARCH not allowed in this state");
            return;
        }

        List<String> tokens = tokenizeImapArgs(args == null ? "" : args.trim());
        if (tokens.isEmpty()) {
            sendTagged(tag, " BAD", "SEARCH requires criteria");
            return;
        }

        String key = tokens.get(0).toUpperCase(Locale.ROOT);
        String value = tokens.size() >= 2 ? tokens.get(1) : null;

        List<Integer> matches = new ArrayList<Integer>();
        for (int i = 0; i < selectedMessages.size(); i++) {
            int seq = i + 1;
            MailMessage msg = selectedMessages.get(i);

            boolean ok;
            switch (key) {
                case "ALL":
                    ok = true;
                    break;
                case "SEEN":
                    ok = msg.seen;
                    break;
                case "UNSEEN":
                    ok = !msg.seen;
                    break;
                case "SUBJECT":
                    ok = value != null && msg.getHeaderValue("Subject").toLowerCase(Locale.ROOT)
                            .contains(value.toLowerCase(Locale.ROOT));
                    break;
                case "FROM":
                    ok = value != null && msg.getHeaderValue("From").toLowerCase(Locale.ROOT)
                            .contains(value.toLowerCase(Locale.ROOT));
                    break;
                default:
                    sendTagged(tag, " BAD", "Unsupported SEARCH key");
                    return;
            }

            if (ok) {
                matches.add(seq);
            }
        }

        StringBuilder sb = new StringBuilder("* SEARCH");
        for (int seq : matches) {
            sb.append(" ").append(seq);
        }
        sendLine(sb.toString());

        sendTagged(tag, " OK", "SEARCH completed");
    }

    private void handleLogout(String tag) throws IOException {
        state = ImapState.LOGOUT;
        sendLine("* BYE IMAP Server logging out");
        sendTagged(tag, " OK", "LOGOUT completed");
        out.flush();
        socket.close();
    }

    private static List<String> tokenizeImapArgs(String args) {
        List<String> tokens = new ArrayList<String>();
        if (args == null) {
            return tokens;
        }

        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
                continue;
            }
            if (!inQuote && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }

        if (cur.length() > 0) {
            tokens.add(cur.toString());
        }

        return tokens;
    }

    private MailMessage getBySeq(int seq) {
        if (seq < 1 || seq > selectedMessages.size()) {
            return null;
        }
        return selectedMessages.get(seq - 1);
    }

    private int parseSeq(String s) {
        s = s.trim();
        if (s.equals("*")) {
            return selectedMessages.size();
        }
        return Integer.parseInt(s);
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
        if (idx < 0) {
            return null;
        }
        return new String[]{s.substring(0, idx), s.substring(idx + 1)};
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}