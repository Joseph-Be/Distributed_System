package org.example.SMTP;

import javax.swing.SwingUtilities;
import org.example.common.ServerEventListener;
import org.example.common.ServerMonitorFrame;

/**
 * Application de supervision SMTP.
 *
 * Correction du bug original : frameHolder[0] était référencé dans le listener
 * avant d'être assigné → NullPointerException → aucun log affiché.
 *
 * Solution : créer la frame EN PREMIER, puis construire le contrôleur
 * en passant directement la frame comme listener.
 */
public class SmtpMonitorApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Placeholder listener (capturé par le contrôleur dès la construction)
            ServerEventListener[] listenerHolder = new ServerEventListener[1];
            listenerHolder[0] = new ServerEventListener() {
                @Override public void onLog(String message) { /* init, pas encore de frame */ }
                @Override public void onClientCountChanged(int count) { }
            };

            // Créer le contrôleur avec un listener délégant
            SmtpServerController controller = new SmtpServerController(50025, new ServerEventListener() {
                @Override public void onLog(String message)          { listenerHolder[0].onLog(message); }
                @Override public void onClientCountChanged(int count){ listenerHolder[0].onClientCountChanged(count); }
            });

            // Créer la frame — elle implémente ServerEventListener
            ServerMonitorFrame frame = new ServerMonitorFrame("Supervision SMTP", controller);

            // Brancher le vrai listener : tous les futurs messages iront à la frame
            listenerHolder[0] = frame;

            frame.setVisible(true);
        });
    }
}
