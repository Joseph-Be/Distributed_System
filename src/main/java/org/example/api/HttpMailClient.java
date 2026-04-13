// org/example/api/HttpMailClient.java
package org.example.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Scanner;

public class HttpMailClient {
    private static final String BASE_URL = "http://localhost:8080/api";
    private static String token    = null;
    private static String username = null;

    public static void main(String[] args) throws Exception {
        Scanner    scanner = new Scanner(System.in);
        HttpClient client  = HttpClient.newHttpClient();

        System.out.println("=== Client Email HTTP ===");

        while (true) {
            if (token == null) {
                System.out.println("\n1. Login");
                System.out.println("2. Quitter");
                System.out.print("Choix : ");
                int choice = scanner.nextInt();
                scanner.nextLine();

                if (choice == 1) {
                    System.out.print("Utilisateur : ");
                    String user = scanner.nextLine();
                    System.out.print("Mot de passe : ");
                    String pass = scanner.nextLine();

                    String json = String.format(
                        "{\"username\":\"%s\",\"password\":\"%s\"}",
                        escape(user), escape(pass));

                    HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(BASE_URL + "/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        token    = extractJson(resp.body(), "token");
                        username = user;
                        System.out.println("Connexion réussie !");
                    } else {
                        System.out.println("Échec : " + resp.body());
                    }
                } else {
                    break;
                }

            } else {
                System.out.println("\n=== Menu (" + username + ") ===");
                System.out.println("1. Lister les emails");
                System.out.println("2. Lire un email");
                System.out.println("3. Envoyer un email");
                System.out.println("4. Supprimer un email");
                System.out.println("5. Déconnexion");
                System.out.print("Choix : ");
                int choice = scanner.nextInt();
                scanner.nextLine();

                switch (choice) {
                    case 1 -> listEmails(client);
                    case 2 -> {
                        System.out.print("ID email : ");
                        int id = scanner.nextInt();
                        scanner.nextLine();
                        readEmail(client, id);
                    }
                    case 3 -> sendEmailInteractive(client, scanner);
                    case 4 -> {
                        System.out.print("ID email : ");
                        int id = scanner.nextInt();
                        scanner.nextLine();
                        deleteEmail(client, id);
                    }
                    case 5 -> logout(client);
                }
            }
        }
        scanner.close();
    }

    // ── Lister ──────────────────────────────────────────────────────────────
    private static void listEmails(HttpClient client) throws Exception {
        HttpResponse<String> resp = get(client, "/emails");
        System.out.println("Emails :\n" + resp.body());
    }

    // ── Lire ────────────────────────────────────────────────────────────────
    private static void readEmail(HttpClient client, int id) throws Exception {
        HttpResponse<String> resp = get(client, "/emails/" + id);
        System.out.println("Email :\n" + resp.body());
    }

    // ── Envoyer ─────────────────────────────────────────────────────────────
    /**
     * Saisie interactive :
     *  - "To" accepte plusieurs adresses séparées par des virgules
     *  - "Content" accepte plusieurs lignes ; terminer par une ligne vide
     */
    private static void sendEmailInteractive(HttpClient client, Scanner scanner) throws Exception {
        System.out.print("À (virgules pour plusieurs) : ");
        String to = scanner.nextLine().trim();

        System.out.print("Sujet : ");
        String subject = scanner.nextLine();

        System.out.println("Contenu (ligne vide pour terminer) :");
        StringBuilder contentBuilder = new StringBuilder();
        String line;
        while (!(line = scanner.nextLine()).isEmpty()) {
            if (contentBuilder.length() > 0) contentBuilder.append("\n");
            contentBuilder.append(line);
        }
        String content = contentBuilder.toString();

        sendEmail(client, to, subject, content);
    }

    private static void sendEmail(HttpClient client,
                                   String to, String subject, String content) throws Exception {
        // Construction JSON sûre : on échappe les caractères spéciaux
        String json = String.format(
            "{\"to\":\"%s\",\"subject\":\"%s\",\"content\":\"%s\"}",
            escape(to), escape(subject), escape(content));

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/emails"))
            .header("Authorization",  "Bearer " + token)
            .header("Content-Type",   "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("Résultat : " + resp.body());
    }

    // ── Supprimer ────────────────────────────────────────────────────────────
    private static void deleteEmail(HttpClient client, int id) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/emails/" + id))
            .header("Authorization", "Bearer " + token)
            .DELETE()
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("Résultat : " + resp.body());
    }

    // ── Déconnexion ──────────────────────────────────────────────────────────
    private static void logout(HttpClient client) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/logout"))
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        client.send(req, HttpResponse.BodyHandlers.ofString());
        token    = null;
        username = null;
        System.out.println("Déconnecté.");
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────
    private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** Échappe les caractères JSON spéciaux (guillemets, barres obliques, retours à la ligne). */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** Extraction JSON minimaliste sans dépendance externe. */
    private static String extractJson(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end >= 0 ? json.substring(start, end) : null;
    }
}
