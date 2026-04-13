// org/example/api/MailAPI.java
package org.example.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Header;

import org.example.rmi.RemoteUserStore;

import java.io.*;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


public class MailAPI {

    private static final Gson gson = new Gson();

    // httpToken -> username
    private static final Map<String, String> sessions = new ConcurrentHashMap<>();

    // httpToken -> rmiToken  (used as password when talking to IMAP / SMTP)
    private static final Map<String, String> rmiTokens = new ConcurrentHashMap<>();

    // Ports des serveurs internes
    private static final String SMTP_HOST = "localhost";
    private static final int    SMTP_PORT = 50025;
    private static final String IMAP_HOST = "localhost";
    private static final int    IMAP_PORT = 1143;

    // httpToken -> { restId -> imapSeq }
    private static final Map<String, Map<Integer, Integer>> seqMaps = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        Javalin app = Javalin.create().start(8080);

        // CORS
        app.before(ctx -> {
            ctx.header(Header.ACCESS_CONTROL_ALLOW_ORIGIN,  "*");
            ctx.header(Header.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
            ctx.header(Header.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization, Content-Type");
            if (ctx.method().toString().equals("OPTIONS")) {
                ctx.status(200).result("OK");
            }
        });

        // Routes publiques
        app.post("/api/login",  MailAPI::handleLogin);
        app.post("/api/logout", MailAPI::handleLogout);

        // Routes protégées
        app.before("/api/*", MailAPI::authenticate);
        app.get   ("/api/emails",           MailAPI::handleGetEmails);
        app.get   ("/api/emails/{id}",      MailAPI::handleGetEmail);
        app.post  ("/api/emails",           MailAPI::handleSendEmail);
        app.delete("/api/emails/{id}",      MailAPI::handleDeleteEmail);
        app.put   ("/api/emails/{id}/seen", MailAPI::handleMarkSeen);

        System.out.println("API REST démarrée sur http://localhost:8080");
    }

    // ── Middleware d'authentification ────────────────────────────────────────
    private static void authenticate(Context ctx) {
        String path = ctx.path();
        if (path.equals("/api/login") || path.equals("/api/logout")) return;

        String header = ctx.header("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            ctx.status(401).json(Map.of("error", "Missing or invalid token"));
            return;
        }
        String httpToken = header.substring(7);
        String username  = sessions.get(httpToken);
        if (username == null) {
            ctx.status(401).json(Map.of("error", "Invalid or expired token"));
            return;
        }
        ctx.attribute("username",  username);
        ctx.attribute("httpToken", httpToken);
        // Make the RMI token available to handlers that open IMAP/SMTP sockets
        ctx.attribute("rmiToken",  rmiTokens.get(httpToken));
    }

    // ── POST /api/login ──────────────────────────────────────────────────────
    private static void handleLogin(Context ctx) {
        JsonObject body     = gson.fromJson(ctx.body(), JsonObject.class);
        String     username = body.get("username").getAsString();
        String     password = body.get("password").getAsString();

        // Authentification via RMI → AuthService (pas de DB directe)
        String rmiToken = RemoteUserStore.login(username, password);
        if (rmiToken != null) {
            String httpToken = UUID.randomUUID().toString();
            sessions.put(httpToken,   username);
            rmiTokens.put(httpToken,  rmiToken);   // ← store the real RMI token
            seqMaps.put(httpToken,    new ConcurrentHashMap<>());
            ctx.json(Map.of("token", httpToken, "username", username));
        } else {
            ctx.status(401).json(Map.of("error", "Invalid credentials"));
        }
    }

    // ── POST /api/logout ─────────────────────────────────────────────────────
    private static void handleLogout(Context ctx) {
        String httpToken = ctx.attribute("httpToken");
        if (httpToken != null) {
            String username = sessions.remove(httpToken);
            String rmiToken = rmiTokens.remove(httpToken);
            seqMaps.remove(httpToken);
            if (username != null && rmiToken != null) {
                RemoteUserStore.logout(username, rmiToken);
            }
        }
        ctx.json(Map.of("message", "Logged out successfully"));
    }

    // ── GET /api/emails ──────────────────────────────────────────────────────
    private static void handleGetEmails(Context ctx) {
        String username = ctx.attribute("username");
        String httpToken = ctx.attribute("httpToken");
        try {
            ctx.json(imapFetchList(username, httpToken));
        } catch (Exception e) {
            ctx.status(502).json(Map.of("error", "IMAP error: " + e.getMessage()));
        }
    }

    // ── GET /api/emails/{id} ─────────────────────────────────────────────────
    private static void handleGetEmail(Context ctx) {
        String username  = ctx.attribute("username");
        String httpToken = ctx.attribute("httpToken");
        int    restId    = Integer.parseInt(ctx.pathParam("id"));
        try {
            Map<String, Object> email = imapFetchOne(username, httpToken, restId);
            if (email == null) ctx.status(404).json(Map.of("error", "Email not found"));
            else               ctx.json(email);
        } catch (Exception e) {
            ctx.status(502).json(Map.of("error", "IMAP error: " + e.getMessage()));
        }
    }

    // ── POST /api/emails ─────────────────────────────────────────────────────
    private static void handleSendEmail(Context ctx) {
        String     from      = ctx.attribute("username");
        String     httpToken = ctx.attribute("httpToken");
        JsonObject body      = gson.fromJson(ctx.body(), JsonObject.class);
        String     to        = body.get("to").getAsString();
        String     subject   = body.get("subject").getAsString();
        String     content   = body.get("content").getAsString();

        String[] recipients = Arrays.stream(to.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        if (recipients.length == 0) {
            ctx.status(400).json(Map.of("error", "No valid recipients"));
            return;
        }
        try {
            smtpSend(from, httpToken, recipients, subject, content);
            ctx.status(201).json(Map.of("message", "Email sent", "recipients", recipients.length));
        } catch (Exception e) {
            ctx.status(502).json(Map.of("error", "SMTP error: " + e.getMessage()));
        }
    }

    // ── DELETE /api/emails/{id} ──────────────────────────────────────────────
    private static void handleDeleteEmail(Context ctx) {
        String username  = ctx.attribute("username");
        String httpToken = ctx.attribute("httpToken");
        int    restId    = Integer.parseInt(ctx.pathParam("id"));
        try {
            if (imapDelete(username, httpToken, restId))
                ctx.json(Map.of("message", "Email deleted"));
            else
                ctx.status(404).json(Map.of("error", "Email not found"));
        } catch (Exception e) {
            ctx.status(502).json(Map.of("error", "IMAP error: " + e.getMessage()));
        }
    }

    // ── PUT /api/emails/{id}/seen ────────────────────────────────────────────
    private static void handleMarkSeen(Context ctx) {
        String username  = ctx.attribute("username");
        String httpToken = ctx.attribute("httpToken");
        int    restId    = Integer.parseInt(ctx.pathParam("id"));
        try {
            if (imapMarkSeen(username, httpToken, restId))
                ctx.json(Map.of("message", "Email marked as seen"));
            else
                ctx.status(404).json(Map.of("error", "Email not found"));
        } catch (Exception e) {
            ctx.status(502).json(Map.of("error", "IMAP error: " + e.getMessage()));
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  IMAP helpers — login with the RMI token (not the HTTP session UUID)
    // ════════════════════════════════════════════════════════════════════════

    private static List<Map<String, Object>> imapFetchList(String username, String httpToken)
            throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<Integer, Integer> seqMap = seqMaps.computeIfAbsent(httpToken, k -> new ConcurrentHashMap<>());
        seqMap.clear();

        try (Socket sock = new Socket(IMAP_HOST, IMAP_PORT);
             BufferedReader in  = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter    out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true)) {

            in.readLine(); // greeting
            // Pass the RMI token as the IMAP password
            sendCmd(out, in, "A001 LOGIN " + username + " " + getRmiToken(httpToken));
            String selectResp = sendCmd(out, in, "A002 SELECT INBOX");

            int msgCount = parseExists(selectResp);
            if (msgCount == 0) { sendCmd(out, in, "A003 LOGOUT"); return result; }

            // A003 is reserved for the empty-mailbox LOGOUT above — use A004 here
            String fetchResp = sendCmd(out, in, "A004 FETCH 1:" + msgCount + " (FLAGS ENVELOPE)");
            sendCmd(out, in, "A005 LOGOUT");

            List<String[]> envelopes = parseEnvelopes(fetchResp);
            AtomicInteger restId = new AtomicInteger(1);
            for (String[] env : envelopes) {
                int id = restId.getAndIncrement();
                seqMap.put(id, Integer.parseInt(env[0]));
                Map<String, Object> m = new HashMap<>();
                m.put("id",      id);
                m.put("from",    env[1]);
                m.put("subject", env[2]);
                m.put("date",    env[3]);
                m.put("seen",    env[4].contains("\\Seen"));
                result.add(m);
            }
        }
        return result;
    }

    private static Map<String, Object> imapFetchOne(String username, String httpToken, int restId)
            throws Exception {
        Map<Integer, Integer> seqMap = seqMaps.computeIfAbsent(httpToken, k -> new ConcurrentHashMap<>());
        if (!seqMap.containsKey(restId)) {
            imapFetchList(username, httpToken);
        }
        Integer seq = seqMap.get(restId);
        if (seq == null) return null;

        try (Socket sock = new Socket(IMAP_HOST, IMAP_PORT);
             BufferedReader in  = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter    out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true)) {

            in.readLine();
            sendCmd(out, in, "B001 LOGIN " + username + " " + getRmiToken(httpToken));
            sendCmd(out, in, "B002 SELECT INBOX");
            String bodyResp = sendCmd(out, in, "B003 FETCH " + seq + " (FLAGS ENVELOPE BODY[])");
            sendCmd(out, in, "B004 STORE " + seq + " +FLAGS (\\Seen)");
            sendCmd(out, in, "B005 LOGOUT");
            return parseFullMessage(bodyResp, restId);
        }
    }

    private static boolean imapDelete(String username, String httpToken, int restId) throws Exception {
        Map<Integer, Integer> seqMap = seqMaps.getOrDefault(httpToken, Collections.emptyMap());
        Integer seq = seqMap.get(restId);
        if (seq == null) return false;

        try (Socket sock = new Socket(IMAP_HOST, IMAP_PORT);
             BufferedReader in  = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter    out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true)) {

            in.readLine();
            sendCmd(out, in, "C001 LOGIN " + username + " " + getRmiToken(httpToken));
            sendCmd(out, in, "C002 SELECT INBOX");
            // Mark as \Deleted — EXPUNGE is not implemented in the server
            sendCmd(out, in, "C003 STORE " + seq + " +FLAGS (\\Deleted)");
            sendCmd(out, in, "C004 LOGOUT");
        }
        seqMap.remove(restId);
        return true;
    }

    private static boolean imapMarkSeen(String username, String httpToken, int restId) throws Exception {
        Map<Integer, Integer> seqMap = seqMaps.getOrDefault(httpToken, Collections.emptyMap());
        Integer seq = seqMap.get(restId);
        if (seq == null) return false;

        try (Socket sock = new Socket(IMAP_HOST, IMAP_PORT);
             BufferedReader in  = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter    out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true)) {

            in.readLine();
            sendCmd(out, in, "D001 LOGIN " + username + " " + getRmiToken(httpToken));
            sendCmd(out, in, "D002 SELECT INBOX");
            sendCmd(out, in, "D003 STORE " + seq + " +FLAGS (\\Seen)");
            sendCmd(out, in, "D004 LOGOUT");
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SMTP helper — authenticates with the RMI token before sending
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Sends an email via SmtpServer.
     * Step 1: EHLO
     * Step 2: AUTH <username> <rmiToken>   ← new: server validates via RMI
     * Step 3: MAIL FROM / RCPT TO / DATA
     */
    private static void smtpSend(String from, String httpToken,
                                 String[] recipients,
                                 String subject, String content) throws Exception {
        String rmiToken  = getRmiToken(httpToken);
        String fromAddr  = from.contains("@") ? from : from + "@mail.example.com";

        try (Socket sock = new Socket(SMTP_HOST, SMTP_PORT);
             BufferedReader in  = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter    out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream()), true)) {

            expectCode(in, "220");

            out.println("EHLO api.mail.example.com");
            drainUntilOk(in, "250");

            // Authenticate with the RMI token
            out.println("AUTH " + from + " " + rmiToken);
            expectCode(in, "235");

            out.println("MAIL FROM:<" + fromAddr + ">");
            expectCode(in, "250");

            for (String rcpt : recipients) {
                String rcptAddr = rcpt.contains("@") ? rcpt : rcpt + "@mail.example.com";
                out.println("RCPT TO:<" + rcptAddr + ">");
                expectCode(in, "250");
            }

            out.println("DATA");
            expectCode(in, "354");

            // RFC 2822 headers
            out.println("From: " + fromAddr);
            out.println("To: " + String.join(", ", recipients));
            out.println("Subject: " + subject);
            out.println("Date: " + new SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).format(new Date()));
            out.println("MIME-Version: 1.0");
            out.println("Content-Type: text/plain; charset=UTF-8");
            out.println(); // ligne vide obligatoire : sépare headers et corps (RFC 2822)


            // Body — escape leading dots per RFC 5321
            for (String line : content.split("\n")) {
                String l = line.stripTrailing();
                out.println(l.startsWith(".") ? "." + l : l);
            }

            out.println(".");
            expectCode(in, "250");
            out.println("QUIT");
            expectCode(in, "221");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  IMAP low-level
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Sends an IMAP command and reads the full response, including literal blocks.
     * IMAP literals look like:  BODY[] {412}\r\n<412 raw bytes>)\r\n
     * readLine() alone cannot handle this — it reads {412} as a normal line and
     * then misframes everything after it, causing body/envelope to be empty.
     */
    private static String sendCmd(PrintWriter out, BufferedReader in, String cmd) throws Exception {
        out.println(cmd);
        String tag = cmd.split(" ")[0];
        StringBuilder sb = new StringBuilder();

        String line;
        while ((line = in.readLine()) != null) {
            sb.append(line).append("\n");

            // Detect IMAP literal:  ...{N}  at end of line
            java.util.regex.Matcher lit =
                    java.util.regex.Pattern.compile("\\{(\\d+)\\}\\s*$").matcher(line);
            if (lit.find()) {
                int n = Integer.parseInt(lit.group(1));
                char[] buf = new char[n];
                int read = 0;
                while (read < n) {
                    int r = in.read(buf, read, n - read);
                    if (r < 0) break;
                    read += r;
                }
                sb.append(buf, 0, read).append("\n");
                // Read the closing ")" line that follows the literal bytes
                String closing = in.readLine();
                if (closing != null) sb.append(closing).append("\n");
                continue;
            }

            if (line.startsWith(tag + " OK") || line.startsWith(tag + " NO") ||
                    line.startsWith(tag + " BAD")) break;
        }
        return sb.toString();
    }

    private static int parseExists(String resp) {
        for (String line : resp.split("\n")) {
            if (line.contains("EXISTS")) {
                try { return Integer.parseInt(line.trim().split(" ")[1]); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    private static List<String[]> parseEnvelopes(String fetchResp) {
        List<String[]> list = new ArrayList<>();
        String[] lines = fetchResp.split("\r?\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 1. Extract Sequence Number
            // Example: "* 1 FETCH ..." -> index 1 is "1"
            String[] topParts = line.split(" ");
            if (topParts.length < 3 || !topParts[0].equals("*")) continue;

            String seq = topParts[1];

            // 2. Extract FLAGS
            String flags = "";
            if (line.contains("FLAGS (")) {
                flags = line.substring(line.indexOf("FLAGS ("), line.indexOf(")", line.indexOf("FLAGS (")) + 1);
            }

            // 3. Extract ENVELOPE content
            if (line.contains("ENVELOPE (")) {
                int start = line.indexOf("ENVELOPE (") + 10; // Jump past "ENVELOPE ("
                int end = line.lastIndexOf("))"); // The double closing parens at the end

                if (end > start) {
                    String inner = line.substring(start, end);
                    String[] parts = splitEnvelope(inner);

                    String date    = parts.length > 0 ? dequote(parts[0]) : "";
                    String subject = parts.length > 1 ? dequote(parts[1]) : "";
                    // Note: IMAP Envelopes for "From" are usually nested lists ( (name, route, user, host) )
                    String from    = parts.length > 2 ? dequote(parts[2]) : "";

                    list.add(new String[]{seq, from, subject, date, flags});
                }
            }
        }
        return list;
    }

    /**
     * Parses a FETCH response containing a literal body block.
     * The server now sends FLAGS, ENVELOPE, and BODY[] all on the same "* N FETCH" line,
     * followed by the raw body bytes (already read by sendCmd), then a closing ")" line.
     *
     * Wire format (after sendCmd reassembles it):
     *   * 1 FETCH (FLAGS (\Unseen) ENVELOPE ("date" "subject" "from" NIL...) BODY[] {N}
     *   <body content>
     *   )
     *   B003 OK FETCH completed
     */
    private static Map<String, Object> parseFullMessage(String bodyResp, int restId) {
        String from = "", subject = "", date = "", flags = "";
        StringBuilder body = new StringBuilder();
        String[] lines = bodyResp.split("\n");
        boolean inBody = false;

        for (String line : lines) {
            String t = line.trim();

            // The "* N FETCH (...)" line carries FLAGS, ENVELOPE, and starts the literal
            if (t.startsWith("*") && t.contains("FETCH")) {
                java.util.regex.Matcher fm =
                        java.util.regex.Pattern.compile("FLAGS\\s*\\(([^)]*)\\)",
                                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
                if (fm.find()) flags = fm.group(1);

                java.util.regex.Matcher em =
                        java.util.regex.Pattern.compile("ENVELOPE\\s*\\((.+?)\\)\\s*(?:BODY|\\{|$)",
                                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
                if (em.find()) {
                    String[] parts = splitEnvelope(em.group(1));
                    if (parts.length > 0) date    = dequote(parts[0]);
                    if (parts.length > 1) subject = dequote(parts[1]);
                    if (parts.length > 2) from    = dequote(parts[2]);
                }

                if (t.contains("BODY[]")) inBody = true;
                continue;
            }

            if (inBody && t.equals(")")) { inBody = false; continue; }
            if (inBody) body.append(line).append("\n");
        }

        Map<String, Object> m = new HashMap<>();
        m.put("id",      restId);
        m.put("from",    from);
        m.put("subject", subject);
        m.put("date",    date);
        m.put("seen",    flags.contains("\\Seen"));
        // Strip headers — keep only the text after the first blank line
        String full = body.toString();

        int sep = full.lastIndexOf("\r\n\r\n");
        if (sep < 0) sep = full.indexOf("\n\n");
        String contentOnly = sep >= 0 ? full.substring(sep).trim() : full.trim();
        System.out.println(contentOnly);
        m.put("content", contentOnly);
        return m;
    }


    private static String[] splitEnvelope(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0; boolean inQ = false; StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '"' && depth == 0) { inQ = !inQ; cur.append(c); }
            else if (!inQ && c == '(')  { depth++; cur.append(c); }
            else if (!inQ && c == ')')  { depth--; cur.append(c); }
            else if (!inQ && c == ' ' && depth == 0) { parts.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        if (cur.length() > 0) parts.add(cur.toString());
        return parts.toArray(new String[0]);
    }

    private static String dequote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s.equals("NIL") ? "" : s;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  SMTP low-level
    // ════════════════════════════════════════════════════════════════════════

    private static void expectCode(BufferedReader in, String code) throws Exception {
        String line = in.readLine();
        if (line == null || !line.startsWith(code))
            throw new IOException("Expected " + code + ", got: " + line);
    }

    private static void drainUntilOk(BufferedReader in, String code) throws Exception {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith(code + " ")) break;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Token lookup
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns the RMI token for the given HTTP session token.
     * This is the credential forwarded to IMAP and SMTP — NOT the HTTP UUID.
     */
    private static String getRmiToken(String httpToken) {
        String t = rmiTokens.get(httpToken);
        return t != null ? t : "__no_rmi_token__";
    }
}