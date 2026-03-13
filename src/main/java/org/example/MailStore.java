package org.example;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class MailStore {
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
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (InputStream is = new FileInputStream(f)) {
        byte[] data = new byte[4096];
        int nRead;
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
    }
    return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
}
}
