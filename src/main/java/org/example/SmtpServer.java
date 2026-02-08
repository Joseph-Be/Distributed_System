package org.example;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class SmtpServer {
    // Use a custom port (e.g., 2525) to avoid needing special privileges.
    private static final int PORT = 25;

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("SMTP Server started on port " + PORT);
            // Continuously accept new client connections
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Connection from " + clientSocket.getInetAddress());
                // Handle each connection in its own thread
                new SmtpSession(clientSocket).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class SmtpSession extends Thread {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;

    // Finite state machine for the SMTP session
    private enum SmtpState {
        CONNECTED,    // Connection established; waiting for HELO/EHLO.
        HELO_RECEIVED, // HELO/EHLO received; ready for MAIL FROM.
        MAIL_FROM_SET, // MAIL FROM command processed; ready for RCPT TO.
        RCPT_TO_SET,   // At least one RCPT TO received; ready for DATA.
        DATA_RECEIVING // DATA command received; reading email content.
    }

    private SmtpState state;
    private String sender;
    private List<String> recipients;
    private StringBuilder dataBuffer;

    public SmtpSession(Socket socket) {
        this.socket = socket;
        this.state = SmtpState.CONNECTED;
        this.recipients = new ArrayList<>();
        this.dataBuffer = new StringBuilder();
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            // Send initial greeting (RFC 5321 specifies a 220 response)
            out.println("220 smtp.example.com Service Ready");

            String line;
            while ((line = in.readLine()) != null) {
                System.out.println("Received: " + line);
                // If we are in DATA receiving state, accumulate message lines
                if (state == SmtpState.DATA_RECEIVING) {
                    // End of DATA input is signaled by a single dot on a line.
                    if (line.equals(".")) {
                        // Store the email and reset for next message.
                        storeEmail(dataBuffer.toString());
                        dataBuffer.setLength(0);
                        // After DATA, we allow additional RCPT TO commands for new messages,
                        // or can reset to HELO_RECEIVED depending on design.
                        state = SmtpState.HELO_RECEIVED;
                        out.println("250 OK: Message accepted for delivery");
                    } else {
                        dataBuffer.append(line).append("\r\n");
                    }
                    continue;
                }

                // Process commands outside of DATA state.
                String command = extractToken(line).toUpperCase();
                String argument = extractArgument(line);

                switch (command) {
                    case "HELO":
                    case "EHLO":
                        handleHelo(argument);
                        break;
                    case "MAIL":
                        handleMailFrom(argument);
                        break;
                    case "RCPT":
                        handleRcptTo(argument);
                        break;
                    case "DATA":
                        handleData();
                        break;
                    case "QUIT":
                        handleQuit();
                        return; // Terminate session after QUIT.
                    default:
                        out.println("500 Command unrecognized");
                        break;
                }
            }
            // Si la boucle se termine alors que nous étions en train de recevoir les données,
            // cela signifie que la connexion a été interrompue avant la réception du point final.
            if (state == SmtpState.DATA_RECEIVING) {
                System.err.println("Connection interrupted during DATA phase. Email incomplete, not stored.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try { socket.close(); } catch (IOException e) { /* ignore */ }
        }
    }

    private void handleHelo(String arg) {
        // Reset any previous session data
        state = SmtpState.HELO_RECEIVED;
        sender = "";
        recipients.clear();
        out.println("250 Hello " + arg);
    }

    private void handleMailFrom(String arg) {
        // Vérifier que l'argument correspond exactement au format "FROM:<email>"
        // L'expression régulière vérifie que la chaîne commence par "FROM:", suivie de zéro ou plusieurs espaces,
        // puis d'une adresse email entre chevrons et rien d'autre.
        if (!arg.toUpperCase().matches("^FROM:\\s*<[^>]+>$")) {
            out.println("501 Syntax error in parameters or arguments");
            out.println(arg.toUpperCase());
            return;
        }
        // Extraire l'adresse email en retirant "FROM:" et les chevrons.
        String potentialEmail = arg.substring(5).trim();  // Extrait ce qui suit "FROM:"
        // Retirer les chevrons (< et >)
        potentialEmail = potentialEmail.substring(1, potentialEmail.length() - 1).trim();

        String email = extractEmail(potentialEmail);
        if (email == null) {
            out.println("501 Syntax error in parameters or arguments");
            return;
        }
        sender = email;
        state = SmtpState.MAIL_FROM_SET;
        out.println("250 OK");
    }

    private void handleRcptTo(String arg) {
        if (state != SmtpState.MAIL_FROM_SET && state != SmtpState.RCPT_TO_SET) {
            out.println("503 Bad sequence of commands");
            return;
        }
        if (!arg.toUpperCase().startsWith("TO:")) {
            out.println("501 Syntax error in parameters or arguments");
            return;
        }
        String potentialEmail = arg.substring(3).trim();
        String email = extractEmail(potentialEmail);
        if (email == null) {
            out.println("501 Syntax error in parameters or arguments");
            return;
        }

        // Check if the recipient's directory exists.
        // The user directory is assumed to be "mailserver/username" where username is the part before '@'.
        String username = email.split("@")[0];
        File userDir = new File(System.getProperty("user.dir") + "/mailserver/" + username);
        if (!userDir.exists()) {
            boolean created = userDir.mkdirs();  // Create user directory
            if (!created) {
                out.println("550 Failed to create user directory");
                return;
            }
        }


        recipients.add(email);
        state = SmtpState.RCPT_TO_SET;
        out.println("250 OK");
    }

    private void handleData() {
        if (state != SmtpState.RCPT_TO_SET || recipients.isEmpty()) {
            out.println("503 Bad sequence of commands");
            return;
        }
        state = SmtpState.DATA_RECEIVING;
        out.println("354 Start mail input; end with <CRLF>.<CRLF>");
    }

    private void handleQuit() {
        out.println("221 smtp.example.com Service closing transmission channel");
    }

    // Helper to extract the first token (command) from the input line.
    private String extractToken(String line) {
        String[] parts = line.split(" ");
        return parts.length > 0 ? parts[0] : "";
    }

    // Helper to extract the argument portion (everything after the command).
    private String extractArgument(String line) {
        int index = line.indexOf(' ');
        return index > 0 ? line.substring(index).trim() : "";
    }

    // Simple email extraction: removes angle brackets and performs a basic validation.
    private String extractEmail(String input) {
        // Remove any surrounding angle brackets.
        input = input.replaceAll("[<>]", "");
        if (input.contains("@") && input.indexOf("@") > 0 && input.indexOf("@") < input.length() - 1) {
            return input;
        }
        return null;
    }

    // Store the email for each recipient in the corresponding user directory.
    // Files are named using the current timestamp.

    private void storeEmail(String data) {
        // Use a readable timestamp format (YYYYMMDD_HHMMSS)
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        for (String recipient : recipients) {
            // Extract username (before @)
            String username = recipient.split("@")[0];

            // Define user directory path
            File userDir = new File("mailserver/" + username);

            // Ensure the directory exists
            if (!userDir.exists()) {
                userDir.mkdirs();  // Create if missing
            }

            // Define email file path
            File emailFile = new File(userDir, timestamp + ".txt");

            // Write email content
            try (PrintWriter writer = new PrintWriter(new FileWriter(emailFile))) {
                // Basic email headers (RFC 5322)
                writer.println("From: " + sender);
                writer.println("To: " + String.join(", ", recipients));
                writer.println("Date: " + new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z").format(new Date()));
                writer.println("Subject: Test Email");
                writer.println();
                writer.print(data);

                // Log success
                System.out.println(" Stored email for " + recipient + " in " + emailFile.getAbsolutePath());
            } catch (IOException e) {
                System.err.println(" Error storing email: " + e.getMessage());
            }
        }
    }
}

