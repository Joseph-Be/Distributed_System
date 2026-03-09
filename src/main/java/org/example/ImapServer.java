package org.example;

import java.io.*;
import java.net.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImapServer {

    private static final int PORT = 143;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("IMAP Server started on port " + PORT);
            // Continuously accept new client connections
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Connection from " + clientSocket.getInetAddress());
                // Handle each connection in its own thread
                new ImapSession(clientSocket).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class ImapSession extends Thread {
    private final Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    // Finite state machine for the SMTP session
    private enum ImapState {
        NOT_AUTHENTICATED,
        AUTHENTICATED,
        SELECTED,
        LOGOUT
    }

    private ImapState state = ImapState.NOT_AUTHENTICATED;

    private String currentUser = null;
    private String selectedMailbox = null; // ex: INBOX
    private List<MailMessage> selectedMessages = new ArrayList<>();


    public ImapSession(Socket clientSocket) {
        this.socket = clientSocket;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

            // Greeting IMAP (untagged OK)
            sendLine("* OK IMAP Service Ready");

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("IMAP Received: " + line);
                line = line.trim();
                if (line.isEmpty()) continue;

                // Format IMAP: <tag> <command> [args...]
                String[] parts = splitOnce(line);
                if (parts == null) {
                    // pas de tag -> BAD
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
            e.printStackTrace();
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

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
    String pass = tokens.get(1);

    if (!UserStore.authenticate(user, pass)) {
        sendTagged(tag, "NO", "Authentication failed");
        return;
    }

    File dir = new File("mailserver/" + user);
    if (!dir.exists()) {
        dir.mkdirs();   // crée le dossier utilisateur si inexistant
    }

    currentUser = user;
    this.state = ImapState.AUTHENTICATED;

    sendTagged(tag, "OK", "LOGIN completed");
}

    private void handleSelect(String tag, String args) throws IOException {
        if (state != ImapState.AUTHENTICATED) {
            sendTagged(tag, "BAD", "SELECT not allowed in this state");
            return;
        }

        if (args == null || args.isBlank()) {
            sendTagged(tag, "BAD", "SELECT requires mailbox name");
            return;
        }

        String mailbox = stripQuotes(args.trim());
        
        if (!mailbox.equalsIgnoreCase("INBOX")) {
            // "no such mailbox" -> NO et reste AUTHENTICATED (si fail, mailbox deselected)
            selectedMailbox = null;
            selectedMessages = new ArrayList<>();
            state = ImapState.AUTHENTICATED;
            sendTagged(tag, "NO", "No such mailbox");
            return;
        }

        if (state == ImapState.SELECTED && selectedMailbox != null && !selectedMailbox.equalsIgnoreCase(mailbox)) {
            sendLine("* OK [CLOSED] Previous mailbox is now closed");
        }

        selectedMailbox = "INBOX";
        // Charger les messages de l'INBOX
        selectedMessages = MailStore.loadInbox(currentUser);

        sendLine("* " + selectedMessages.size() + " EXISTS");
        sendLine("* FLAGS (\\Seen)");
        sendLine("* LIST () \"/\" INBOX");

        state = ImapState.SELECTED;
        sendTagged(tag, "OK", "SELECT completed, " + selectedMessages.size() + " messages");
    }

    private void handleFetch(String tag, String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, "BAD", "FETCH not allowed in this state");
            return;
        }
        if (args == null || args.isBlank()) {
            sendTagged(tag, "BAD", "FETCH requires arguments");
            return;
        }

        // MVP: FETCH <seq> (FLAGS|BODY[HEADER]|BODY[])
        // Exemples:
        // A1 FETCH 1 FLAGS
        // A2 FETCH 1 BODY[HEADER]
        // A3 FETCH 1 (FLAGS BODY[HEADER])
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

        // Si rien reconnu, on renvoie BAD
        if (!wantFlags && !wantHeader && !wantBodyAll) {
            sendTagged(tag, "BAD", "Unsupported FETCH data item");
            return;
        }

        StringBuilder resp = new StringBuilder();
        resp.append("* ").append(seq).append(" FETCH (");

        if (wantFlags) {
            resp.append("FLAGS (");
            if (msg.seen) resp.append("\\Seen");
            else resp.append("\\Unseen");
            resp.append(") ");
        }

        if (wantHeader) {
            String header = msg.headersOnly();
            writeFetchLiteral(resp, "BODY[HEADER]", header);
            resp.append(" ");
        }

        if (wantBodyAll) {
            String body = msg.fullContent;
            writeFetchLiteral(resp, "BODY[]", body);
            resp.append(" ");
        }

        // close
        if (resp.charAt(resp.length() - 1) == ' ') resp.setLength(resp.length() - 1);
        resp.append(")");

        sendLine(resp.toString());
        sendTagged(tag, "OK", "FETCH completed");
    }

    private void handleStore(String tag, String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, "BAD", "STORE not allowed in this state");
            return;
        }
        if (args == null || args.isBlank()) {
            sendTagged(tag, "BAD", "STORE requires arguments");
            return;
        }

        // MVP: STORE <seq> +FLAGS (\Seen)  |  STORE <seq> -FLAGS (\Seen) | STORE <seq> FLAGS (\Seen)
        // On supporte uniquement \Seen.
        Pattern p = Pattern.compile("^([0-9]+)\\s+(\\+FLAGS|-FLAGS|FLAGS)\\s+\\(([^)]*)\\)\\s*$", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(args.trim());
        if (!m.matches()) {
            sendTagged(tag, "BAD", "Unsupported STORE syntax");
            return;
        }

        int seq = Integer.parseInt(m.group(1));
        String op = m.group(2).toUpperCase(Locale.ROOT);
        String flags = m.group(3).trim().toUpperCase(Locale.ROOT);

        if (!flags.contains("\\SEEN")) {
            // On limite à \Seen
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

        // Renvoi untagged FETCH avec FLAGS (classique)
        sendLine("* " + seq + " FETCH (FLAGS (" + (msg.seen ? "\\Seen" : "") + "))");
        sendTagged(tag, "OK", "STORE completed");
    }

    private void handleSearch(String tag, String args) throws IOException {
        if (state != ImapState.SELECTED) {
            sendTagged(tag, "BAD", "SEARCH not allowed in this state");
            return;
        }

        // MVP: SEARCH ALL | SEARCH SEEN | SEARCH UNSEEN | SEARCH SUBJECT "x" | SEARCH FROM "x"
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
                    ok = value != null && msg.getHeaderValue("Subject").toLowerCase(Locale.ROOT)
                            .contains(value.toLowerCase(Locale.ROOT));
                    break;
                case "FROM":
                    ok = value != null && msg.getHeaderValue("From").toLowerCase(Locale.ROOT)
                            .contains(value.toLowerCase(Locale.ROOT));
                    break;
                default:
                    sendTagged(tag, "BAD", "Unsupported SEARCH key");
                    return;
            }

            if (ok) matches.add(seq);
        }

        // SEARCH response (untagged)
        StringBuilder sb = new StringBuilder("* SEARCH");
        for (int seq : matches) sb.append(" ").append(seq);
        sendLine(sb.toString());

        sendTagged(tag, "OK", "SEARCH completed");
    }

    private void handleLogout(String tag) throws IOException {
        // RFC: BYE untagged puis tagged OK et fermeture :contentReference[oaicite:7]{index=7}
        state = ImapState.LOGOUT;
        sendLine("* BYE IMAP Server logging out");
        sendTagged(tag, "OK", "LOGOUT completed");
        out.flush();
        socket.close();
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
        if (cur.length() > 0) tokens.add(cur.toString());
        return tokens;
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
    private void sendLine(String s) throws IOException {
        out.write(s);
        out.write("\r\n");
        out.flush();
    }

    private void sendTagged(String tag, String status, String text) throws IOException {
        sendLine(tag + " " + status + " " + text);
    }
    private static String[] splitOnce(String s) {
        int idx = s.indexOf(' ');
        if (idx < 0) return null;
        return new String[]{ s.substring(0, idx), s.substring(idx + 1) };
    }

    private static String stripQuotes(String s) {
        s = s.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
    private void writeFetchLiteral(StringBuilder respPrefix, String itemName, String data) throws IOException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        respPrefix.append(itemName).append(" {").append(bytes.length).append("}\r\n");
        // Attention: on doit envoyer le prefix courant maintenant (car on inclut CRLF + data)
        out.write(respPrefix.toString());
        out.write(data);
        // On reprend ensuite un builder “propre”
        respPrefix.setLength(0);
        out.flush();
    }


}



class MailStore {
    /**
     * Structure attendue (comme ton SMTP server):
     * mailserver/<user>/*.txt
     * + mailserver/<user>/<filename>.seen (persist flag)
     */
    public static List<MailMessage> loadInbox(String user) {
        File dir = new File("mailserver/" + user);
        if (!dir.exists() || !dir.isDirectory()) return new ArrayList<>();

        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".txt"));
        if (files == null) return new ArrayList<>();

        // tri stable par nom (timestamps)
        Arrays.sort(files, Comparator.comparing(File::getName));

        List<MailMessage> msgs = new ArrayList<>();
        long uid = 1;
        for (File f : files) {
            try {
                String content = readAll(f);
                boolean seen = readSeenFlag(user, f.getName());
                msgs.add(new MailMessage(f.getName(), uid++, content, seen));
            } catch (IOException ignored) {}
        }
        return msgs;
    }

    public static void persistSeen(String user, String fileName, boolean seen) {
        File dir = new File("mailserver/" + user);
        if (!dir.exists()) dir.mkdirs();
        File seenFile = new File(dir, fileName + ".seen");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(seenFile), StandardCharsets.UTF_8)) {
            w.write(seen ? "1" : "0");
        } catch (IOException ignored) {}
    }

    private static boolean readSeenFlag(String user, String fileName) {
        File seenFile = new File("mailserver/" + user + "/" + fileName + ".seen");
        if (!seenFile.exists()) return false;
        try {
            String v = readAll(seenFile).trim();
            return v.equals("1");
        } catch (IOException e) {
            return false;
        }
    }

    private static String readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}



class MailMessage {
    public final String fileName;
    public final long uid;
    public final String fullContent;
    public boolean seen;

    public MailMessage(String fileName, long uid, String fullContent, boolean seen) {
        this.fileName = fileName;
        this.uid = uid;
        this.fullContent = fullContent;
        this.seen = seen;
    }

    public String headersOnly() {
        // séparateur headers/body: ligne vide
        int idx = fullContent.indexOf("\r\n\r\n");
        if (idx >= 0) return fullContent.substring(0, idx + 2) + "\r\n";
        idx = fullContent.indexOf("\n\n");
        if (idx >= 0) return fullContent.substring(0, idx + 1) + "\n";
        // fallback: tout si pas de séparation
        return fullContent;
    }

    public String getHeaderValue(String headerName) {
        String[] lines = headersOnly().split("\\r?\\n");
        String prefix = headerName + ":";
        for (String l : lines) {
            if (l.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return l.substring(prefix.length()).trim();
            }
        }
        return "";
    }
}

class UserStore {

    private static final String USERS_FILE = "mailserver/users.json";
    private static final Map<String, String> users = new HashMap<>();

    static {
        loadUsers();
    }

    private static void loadUsers() {
        try {
            File file = new File(USERS_FILE);
            if (!file.exists()) {
                System.err.println("users.json not found");
                return;
            }

            String content = new String(
                    Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8
            );

            // Nettoyage basique du JSON
            content = content.trim();
            content = content.substring(1, content.length() - 1); // retire { }

            String[] entries = content.split(",");

            for (String entry : entries) {
                String[] pair = entry.split(":");
                if (pair.length == 2) {
                    String key = clean(pair[0]);
                    String value = clean(pair[1]);
                    users.put(key, value);
                }
            }

            System.out.println("Loaded users: " + users.keySet());

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String clean(String s) {
        return s.trim()
                .replace("\"", "")
                .replace("{", "")
                .replace("}", "");
    }

    public static boolean authenticate(String username, String password) {
        return users.containsKey(username)
                && users.get(username).equals(password);
    }
}