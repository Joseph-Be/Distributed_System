package org.example.IMAP;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Paths;
import org.example.common.ServerController;
import org.example.common.ServerEventListener;
import org.example.common.ServerLogger;
import org.example.common.UserStore;
import org.example.common.MailStore;
import org.example.common.MailMessage;
import org.example.rmi.RemoteUserStore;

public class ImapServerAsync {

    private static final int PORT = 1143;

    public static void main(String[] args) {
        try {
            AsynchronousServerSocketChannel server =
                    AsynchronousServerSocketChannel.open()
                            .bind(new InetSocketAddress(PORT));

            System.out.println("Async IMAP Server started on port " + PORT);

            CountDownLatch latch = new CountDownLatch(1);

            server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {
                @Override
                public void completed(AsynchronousSocketChannel client, Object attachment) {
                    server.accept(null, this); // accepter la prochaine connexion

                    try {
                        System.out.println("Connection from " + client.getRemoteAddress());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    ImapSessionAsync session = new ImapSessionAsync(client);
                    session.start();
                }

                @Override
                public void failed(Throwable exc, Object attachment) {
                    System.err.println("Failed to accept connection: " + exc.getMessage());
                }
            });

            latch.await();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

class ImapSessionAsync {

    private final AsynchronousSocketChannel socket;
    private final ByteBuffer readBuffer = ByteBuffer.allocate(8192);
    private final StringBuilder inputBuffer = new StringBuilder();

    private enum ImapState {
        NOT_AUTHENTICATED,
        AUTHENTICATED,
        SELECTED,
        LOGOUT
    }

    private ImapState state = ImapState.NOT_AUTHENTICATED;

    private String currentUser = null;
    private String selectedMailbox = null;
    private List<MailMessage> selectedMessages = new ArrayList<>();

    public ImapSessionAsync(AsynchronousSocketChannel socket) {
        this.socket = socket;
    }

    public void start() {
        sendLine("* OK IMAP Service Ready", this::readLoop);
    }

    private void readLoop() {
        if (!socket.isOpen()) return;

        readBuffer.clear();
        socket.read(readBuffer, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer bytesRead, Void attachment) {
                if (bytesRead == -1) {
                    close();
                    return;
                }

                readBuffer.flip();
                String chunk = StandardCharsets.UTF_8.decode(readBuffer).toString();
                inputBuffer.append(chunk);

                processBufferedLines();
                readLoop();
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                System.err.println("Read failed: " + exc.getMessage());
                close();
            }
        });
    }

    private void processBufferedLines() {
        int idx;
        while ((idx = findLineEnd(inputBuffer)) >= 0) {
            String line = inputBuffer.substring(0, idx).trim();
            removeProcessedLine(inputBuffer, idx);

            if (line.isEmpty()) continue;

            System.out.println("IMAP Received: " + line);
            handleCommand(line);
        }
    }

    private void handleCommand(String line) {
        String[] parts = splitOnce(line);
        if (parts == null) {
            sendLine("* BAD Missing tag", null);
            return;
        }

        String tag = parts[0];
        String rest = parts[1];

        String[] cmdParts = splitOnce(rest);
        String cmd = (cmdParts == null ? rest : cmdParts[0]).toUpperCase(Locale.ROOT);
        String args = (cmdParts == null ? "" : cmdParts[1]);

        try {
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
                    break;
                default:
                    sendTagged(tag, "BAD", "Command unknown or arguments invalid");
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendLine(tag + " BAD Internal server error", null);
        }
    }

    private void handleLogin(String tag, String args) {
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
        sendTagged(tag, "OK", "LOGIN completed");
    }

    private void handleSelect(String tag, String args) {
        if (state != ImapState.AUTHENTICATED) {
            sendTagged(tag, "BAD", "SELECT not allowed in this state");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            sendTagged(tag, "BAD", "SELECT requires mailbox name");
            return;
        }

        String mailbox = stripQuotes(args.trim());

        if (!mailbox.equalsIgnoreCase("INBOX")) {
            selectedMailbox = null;
            selectedMessages = new ArrayList<>();
            state = ImapState.AUTHENTICATED;
            sendTagged(tag, "NO", "No such mailbox");
            return;
        }

        if (state == ImapState.SELECTED && selectedMailbox != null
                && !selectedMailbox.equalsIgnoreCase(mailbox)) {
            sendLine("* OK [CLOSED] Previous mailbox is now closed", null);
        }

        selectedMailbox = "INBOX";
        selectedMessages = MailStore.loadInbox(currentUser);

        sendLine("* " + selectedMessages.size() + " EXISTS", null);
        sendLine("* FLAGS (\\Seen)", null);
        sendLine("* LIST () \"/\" INBOX", null);

        state = ImapState.SELECTED;
        sendTagged(tag, "OK", "SELECT completed, " + selectedMessages.size() + " messages");
    }

    private void handleFetch(String tag, String args) {
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

        int seq = parseSeq(firstSplit[0]);
        String items = firstSplit[1].trim();

        MailMessage msg = getBySeq(seq);
        if (msg == null) {
            sendTagged(tag, "BAD", "Invalid message sequence number");
            return;
        }

        boolean wantFlags = items.toUpperCase(Locale.ROOT).contains("FLAGS");
        boolean wantHeader = items.toUpperCase(Locale.ROOT).contains("BODY[HEADER]");
        boolean wantBodyAll = items.toUpperCase(Locale.ROOT).contains("BODY[]");

        if (!wantFlags && !wantHeader && !wantBodyAll) {
            sendTagged(tag, "BAD", "Unsupported FETCH data item");
            return;
        }

        StringBuilder resp = new StringBuilder();
        resp.append("* ").append(seq).append(" FETCH (");

        if (wantFlags) {
            resp.append("FLAGS (");
            resp.append(msg.seen ? "\\Seen" : "\\Unseen");
            resp.append(") ");
        }

        if (wantHeader) {
            appendFetchLiteral(resp, "BODY[HEADER]", msg.headersOnly());
            resp.append(" ");
        }

        if (wantBodyAll) {
            appendFetchLiteral(resp, "BODY[]", msg.fullContent);
            resp.append(" ");
        }

        if (resp.charAt(resp.length() - 1) == ' ') {
            resp.setLength(resp.length() - 1);
        }
        resp.append(")");

        sendLine(resp.toString(), null);
        sendTagged(tag, "OK", "FETCH completed");
    }

    private void handleStore(String tag, String args) {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, "BAD", "STORE not allowed in this state");
            return;
        }

        if (args == null || args.trim().isEmpty()) {
            sendTagged(tag, "BAD", "STORE requires arguments");
            return;
        }

        Pattern p = Pattern.compile(
                "^([0-9]+)\\s+(\\+FLAGS|-FLAGS|FLAGS)\\s+\\(([^)]*)\\)\\s*$",
                Pattern.CASE_INSENSITIVE
        );
        Matcher m = p.matcher(args.trim());

        if (!m.matches()) {
            sendTagged(tag, "BAD", "Unsupported STORE syntax");
            return;
        }

        int seq = Integer.parseInt(m.group(1));
        String op = m.group(2).toUpperCase(Locale.ROOT);
        String flags = m.group(3).trim().toUpperCase(Locale.ROOT);

        if (!flags.contains("\\SEEN")) {
            sendTagged(tag, "BAD", "Only \\Seen flag is supported");
            return;
        }

        MailMessage msg = getBySeq(seq);
        if (msg == null) {
            sendTagged(tag, "BAD", "Invalid message sequence number");
            return;
        }

        if (op.equals("+FLAGS") || op.equals("FLAGS")) {
            msg.seen = true;
        } else if (op.equals("-FLAGS")) {
            msg.seen = false;
        }

        MailStore.persistSeen(currentUser, msg.fileName, msg.seen);

        sendLine("* " + seq + " FETCH (FLAGS (" + (msg.seen ? "\\Seen" : "") + "))", null);
        sendTagged(tag, "OK", "STORE completed");
    }

    private void handleSearch(String tag, String args) {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, "BAD", "SEARCH not allowed in this state");
            return;
        }

        List<String> tokens = tokenizeImapArgs(args == null ? "" : args.trim());
        if (tokens.isEmpty()) {
            sendTagged(tag, "BAD", "SEARCH requires criteria");
            return;
        }

        String key = tokens.get(0).toUpperCase(Locale.ROOT);
        String value = tokens.size() >= 2 ? tokens.get(1) : null;

        List<Integer> matches = new ArrayList<>();

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
                    ok = value != null &&
                            msg.getHeaderValue("Subject").toLowerCase(Locale.ROOT)
                                    .contains(value.toLowerCase(Locale.ROOT));
                    break;
                case "FROM":
                    ok = value != null &&
                            msg.getHeaderValue("From").toLowerCase(Locale.ROOT)
                                    .contains(value.toLowerCase(Locale.ROOT));
                    break;
                default:
                    sendTagged(tag, "BAD", "Unsupported SEARCH key");
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

        sendLine(sb.toString(), null);
        sendTagged(tag, "OK", "SEARCH completed");
    }

    private void handleLogout(String tag) {
        state = ImapState.LOGOUT;
        sendLine("* BYE IMAP Server logging out", () -> {
            sendTagged(tag, "OK", "LOGOUT completed");
            close();
        });
    }

    private MailMessage getBySeq(int seq) {
        if (seq < 1 || seq > selectedMessages.size()) return null;
        return selectedMessages.get(seq - 1);
    }

    private int parseSeq(String s) {
        s = s.trim();
        if (s.equals("*")) return selectedMessages.size();
        return Integer.parseInt(s);
    }

    private void sendTagged(String tag, String status, String text) {
        sendLine(tag + " " + status + " " + text, null);
    }

    private void sendLine(String s, Runnable onSuccess) {
        writeString(s + "\r\n", onSuccess);
    }

    private void writeString(String s, Runnable onSuccess) {
        ByteBuffer buffer = ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));

        socket.write(buffer, null, new CompletionHandler<Integer, Void>() {
            @Override
            public void completed(Integer result, Void attachment) {
                if (buffer.hasRemaining()) {
                    socket.write(buffer, null, this);
                    return;
                }
                if (onSuccess != null) onSuccess.run();
            }

            @Override
            public void failed(Throwable exc, Void attachment) {
                System.err.println("Write failed: " + exc.getMessage());
                close();
            }
        });
    }

    private void appendFetchLiteral(StringBuilder sb, String itemName, String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        sb.append(itemName)
          .append(" {")
          .append(bytes.length)
          .append("}\r\n")
          .append(data);
    }

    private void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static int findLineEnd(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '\n') return i;
        }
        return -1;
    }

    private static void removeProcessedLine(StringBuilder sb, int lineEndIndex) {
        int removeUntil = lineEndIndex + 1;
        sb.delete(0, removeUntil);
    }

    private static String[] splitOnce(String s) {
        int idx = s.indexOf(' ');
        if (idx < 0) return null;
        return new String[]{s.substring(0, idx), s.substring(idx + 1)};
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static List<String> tokenizeImapArgs(String args) {
        List<String> tokens = new ArrayList<>();
        if (args == null) return tokens;

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
}

