package org.example;

import javax.swing.SwingUtilities;

public class ImapMonitorApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ServerMonitorFrame[] frameHolder = new ServerMonitorFrame[1];

            ServerEventListener listener = new ServerEventListener() {
                @Override
                public void onLog(String message) {
                    frameHolder[0].onLog(message);
                }

                @Override
                public void onClientCountChanged(int count) {
                    frameHolder[0].onClientCountChanged(count);
                }
            };

            ImapServerController controller = new ImapServerController(143, listener);
            frameHolder[0] = new ServerMonitorFrame("Supervision IMAP", controller);
            frameHolder[0].setVisible(true);
        });
    }
}