package org.example;

import javax.swing.*;
import java.awt.*;

public class ServerMonitorFrame extends JFrame implements ServerEventListener {

    private final JTextArea logArea = new JTextArea();
    private final JLabel clientsLabel = new JLabel("Clients connectés : 0");

    private final JButton startButton = new JButton("Démarrer");
    private final JButton stopButton = new JButton("Arrêter");

    private final ServerController controller;

    public ServerMonitorFrame(String title, ServerController controller) {
        super(title);
        this.controller = controller;

        setSize(800, 500);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 13));

        JScrollPane scrollPane = new JScrollPane(logArea);

        JPanel topPanel = new JPanel();
        topPanel.add(startButton);
        topPanel.add(stopButton);
        topPanel.add(clientsLabel);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        stopButton.setEnabled(false);

        startButton.addActionListener(e -> {
            controller.startServer();
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        });

        stopButton.addActionListener(e -> {
            controller.stopServer();
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        });
    }

    @Override
    public void onLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    @Override
    public void onClientCountChanged(int count) {
        SwingUtilities.invokeLater(() ->
            clientsLabel.setText("Clients connectés : " + count)
        );
    }
}