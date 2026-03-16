package org.example.common;

public class MailMessage {
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
