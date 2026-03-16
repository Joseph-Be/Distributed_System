package org.example.SMTP;
import javax.swing.SwingUtilities;

import org.example.common.ServerEventListener;
import org.example.common.ServerMonitorFrame;

public class SmtpMonitorApp {
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

            SmtpServerController controller = new SmtpServerController(50025, listener);
            frameHolder[0] = new ServerMonitorFrame("Supervision SMTP", controller);
            frameHolder[0].setVisible(true);
        });
    }
}