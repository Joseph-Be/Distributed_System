package org.example.POP3;

import javax.swing.SwingUtilities;

import org.example.common.ServerEventListener;
import org.example.common.ServerMonitorFrame;

public class Pop3MonitorApp {

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

            Pop3ServerController controller =
                    new Pop3ServerController(10010, listener);

            frameHolder[0] =
                    new ServerMonitorFrame("Supervision POP3", controller);

            frameHolder[0].setVisible(true);
        });
    }
}