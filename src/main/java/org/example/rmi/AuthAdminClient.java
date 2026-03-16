package org.example.rmi;

import javax.swing.*;
import java.awt.*;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;

public class AuthAdminClient extends JFrame {

    private AuthService authService;

    private JTextField usernameField;
    private JPasswordField passwordField;
    private DefaultListModel<String> userListModel;
    private JList<String> userList;
    private JTextArea logArea;

    public AuthAdminClient() {
        setTitle("Client d'administration RMI");
        setSize(700, 450);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        
        initUi();
        initRmi();
        loadUsers();
    }

    private void initRmi() {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            authService = (AuthService) registry.lookup("AuthService");
            log("Connecté au serveur RMI.");
        } catch (Exception e) {
            log("Erreur connexion RMI : " + e.getMessage());
            JOptionPane.showMessageDialog(
                    this,
                    "Impossible de se connecter au serveur RMI.\n" + e.getMessage(),
                    "Erreur",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void initUi() {
        setLayout(new BorderLayout());

        JPanel topPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        topPanel.setBorder(BorderFactory.createTitledBorder("Gestion utilisateur"));

        topPanel.add(new JLabel("Nom d'utilisateur :"));
        usernameField = new JTextField();
        topPanel.add(usernameField);

        topPanel.add(new JLabel("Mot de passe :"));
        passwordField = new JPasswordField();
        topPanel.add(passwordField);

        add(topPanel, BorderLayout.NORTH);

        userListModel = new DefaultListModel<String>();
        userList = new JList<String>(userListModel);
        JScrollPane listScrollPane = new JScrollPane(userList);
        listScrollPane.setBorder(BorderFactory.createTitledBorder("Liste des utilisateurs"));

        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(BorderFactory.createTitledBorder("Logs"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScrollPane, logScrollPane);
        splitPane.setDividerLocation(250);
        add(splitPane, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout());

        JButton addButton = new JButton("Ajouter");
        JButton updateButton = new JButton("Modifier");
        JButton deleteButton = new JButton("Supprimer");
        JButton refreshButton = new JButton("Actualiser");

        buttonPanel.add(addButton);
        buttonPanel.add(updateButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(refreshButton);

        add(buttonPanel, BorderLayout.SOUTH);

        addButton.addActionListener(e -> addUser());
        updateButton.addActionListener(e -> updateUser());
        deleteButton.addActionListener(e -> deleteUser());
        refreshButton.addActionListener(e -> loadUsers());

        userList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selected = userList.getSelectedValue();
                if (selected != null) {
                    usernameField.setText(selected);
                }
            }
        });
    }

    private void loadUsers() {
        if (authService == null) {
            log("Service RMI indisponible.");
            return;
        }

        try {
            List<String> users = authService.getAllUsers();
            userListModel.clear();

            for (String user : users) {
                userListModel.addElement(user);
            }

            log("Liste des utilisateurs actualisée.");
        } catch (Exception e) {
            log("Erreur chargement utilisateurs : " + e.getMessage());
        }
    }

    private void addUser() {
        if (authService == null) {
            log("Service RMI indisponible.");
            return;
        }

        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword()).trim();

        if (username.isEmpty() || password.isEmpty()) {
            log("Ajouter : nom d'utilisateur ou mot de passe vide.");
            JOptionPane.showMessageDialog(this, "Veuillez remplir tous les champs.");
            return;
        }

        try {
            boolean ok = authService.createUser(username, password);

            if (ok) {
                log("Utilisateur ajouté : " + username);
                loadUsers();
                clearFields();
            } else {
                log("Ajout impossible : utilisateur déjà existant.");
                JOptionPane.showMessageDialog(this, "L'utilisateur existe déjà.");
            }
        } catch (Exception e) {
            log("Erreur ajout utilisateur : " + e.getMessage());
        }
    }

    private void updateUser() {
        if (authService == null) {
            log("Service RMI indisponible.");
            return;
        }

        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword()).trim();

        if (username.isEmpty() || password.isEmpty()) {
            log("Modifier : nom d'utilisateur ou mot de passe vide.");
            JOptionPane.showMessageDialog(this, "Veuillez remplir tous les champs.");
            return;
        }

        try {
            boolean ok = authService.updateUser(username, password);

            if (ok) {
                log("Utilisateur modifié : " + username);
                loadUsers();
                clearFields();
            } else {
                log("Modification impossible : utilisateur introuvable.");
                JOptionPane.showMessageDialog(this, "Utilisateur introuvable.");
            }
        } catch (Exception e) {
            log("Erreur modification utilisateur : " + e.getMessage());
        }
    }

    private void deleteUser() {
        if (authService == null) {
            log("Service RMI indisponible.");
            return;
        }

        String username = usernameField.getText().trim();

        if (username.isEmpty()) {
            log("Suppression : nom d'utilisateur vide.");
            JOptionPane.showMessageDialog(this, "Veuillez saisir un nom d'utilisateur.");
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Supprimer l'utilisateur : " + username + " ?",
                "Confirmation",
                JOptionPane.YES_NO_OPTION
        );

        if (confirm != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            boolean ok = authService.deleteUser(username);

            if (ok) {
                log("Utilisateur supprimé : " + username);
                loadUsers();
                clearFields();
            } else {
                log("Suppression impossible : utilisateur introuvable.");
                JOptionPane.showMessageDialog(this, "Utilisateur introuvable.");
            }
        } catch (Exception e) {
            log("Erreur suppression utilisateur : " + e.getMessage());
        }
    }

    private void clearFields() {
        usernameField.setText("");
        passwordField.setText("");
        userList.clearSelection();
    }

    private void log(String message) {
        logArea.append(message + "\n");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new AuthAdminClient().setVisible(true);
            }
        });
    }
}