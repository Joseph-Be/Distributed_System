
package org.example.database;

import java.sql.Timestamp;

public class MailMessageDB {
    private int id;
    private String fromAddress;
    private String subject;
    private String content;
    private Timestamp sentDate;
    private boolean seen;
    
    // Constructeurs
    public MailMessageDB() {}
    
    public MailMessageDB(int id, String fromAddress, String subject, String content, Timestamp sentDate, boolean seen) {
        this.id = id;
        this.fromAddress = fromAddress;
        this.subject = subject;
        this.content = content;
        this.sentDate = sentDate;
        this.seen = seen;
    }
    
    // Getters et Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    
    public Timestamp getSentDate() { return sentDate; }
    public void setSentDate(Timestamp sentDate) { this.sentDate = sentDate; }
    
    public boolean isSeen() { return seen; }
    public void setSeen(boolean seen) { this.seen = seen; }
    
    // Convertir en format texte pour SMTP/POP3/IMAP
    public String toEmailFormat() {
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(fromAddress).append("\r\n");
        sb.append("To: ").append(getToFromContent()).append("\r\n");
        sb.append("Subject: ").append(subject).append("\r\n");
        sb.append("Date: ").append(sentDate).append("\r\n");
        sb.append("\r\n");
        sb.append(content);
        return sb.toString();
    }
    
    private String getToFromContent() {
        // Extraire le destinataire du contenu (ou utiliser une autre méthode)
        return "user@localhost";
    }
}