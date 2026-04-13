package org.example.POP3;

import javax.swing.SwingUtilities;
import org.example.common.ServerEventListener;
import org.example.common.ServerMonitorFrame;

/**
 * Application de supervision POP3.
 * Même correction que SmtpMonitorApp.
 */
public class Pop3MonitorApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ServerEventListener[] listenerHolder = new ServerEventListener[1];
            listenerHolder[0] = new ServerEventListener() {
                @Override public void onLog(String message) { }
                @Override public void onClientCountChanged(int count) { }
            };

            Pop3ServerController controller = new Pop3ServerController(10010, new ServerEventListener() {
                @Override public void onLog(String message)           { listenerHolder[0].onLog(message); }
                @Override public void onClientCountChanged(int count) { listenerHolder[0].onClientCountChanged(count); }
            });

            ServerMonitorFrame frame = new ServerMonitorFrame("Supervision POP3", controller);
            listenerHolder[0] = frame;
            frame.setVisible(true);
        });
    }
}
