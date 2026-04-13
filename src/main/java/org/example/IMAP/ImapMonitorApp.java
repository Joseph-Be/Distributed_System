package org.example.IMAP;

import javax.swing.SwingUtilities;
import org.example.common.ServerEventListener;
import org.example.common.ServerMonitorFrame;

/**
 * Application de supervision IMAP.
 * Même correction que SmtpMonitorApp.
 */
public class ImapMonitorApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ServerEventListener[] listenerHolder = new ServerEventListener[1];
            listenerHolder[0] = new ServerEventListener() {
                @Override public void onLog(String message) { }
                @Override public void onClientCountChanged(int count) { }
            };

            ImapServerController controller = new ImapServerController(1143, new ServerEventListener() {
                @Override public void onLog(String message)           { listenerHolder[0].onLog(message); }
                @Override public void onClientCountChanged(int count) { listenerHolder[0].onClientCountChanged(count); }
            });

            ServerMonitorFrame frame = new ServerMonitorFrame("Supervision IMAP", controller);
            listenerHolder[0] = frame;
            frame.setVisible(true);
        });
    }
}
