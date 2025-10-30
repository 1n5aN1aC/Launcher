/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.dialog;

import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.util.SharedLocale;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Dialog for entering an offline username.
 */
public class OfflineUsernameDialog extends JDialog {
    
    private final JTextField usernameField = new JTextField(20);
    private final JButton okButton = new JButton(SharedLocale.tr("button.ok"));
    private final JButton cancelButton = new JButton(SharedLocale.tr("button.cancel"));
    
    private String enteredUsername = null;
    
    public OfflineUsernameDialog(Window parent) {
        super(parent, SharedLocale.tr("login.offline.title"), ModalityType.DOCUMENT_MODAL);
        
        initComponents();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        setResizable(false);
        setLocationRelativeTo(parent);
    }
    
    private void initComponents() {
        setLayout(new BorderLayout());
        
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Message
        JLabel messageLabel = new JLabel(SharedLocale.tr("login.offline.message"));
        messageLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(messageLabel);
        mainPanel.add(Box.createVerticalStrut(10));
        
        // Username field
        JPanel usernamePanel = new JPanel(new BorderLayout(5, 0));
        JLabel usernameLabel = new JLabel(SharedLocale.tr("login.offline.username"));
        usernamePanel.add(usernameLabel, BorderLayout.WEST);
        usernamePanel.add(usernameField, BorderLayout.CENTER);
        usernamePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(usernamePanel);
        
        add(mainPanel, BorderLayout.CENTER);
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.add(okButton);
        add(buttonPanel, BorderLayout.SOUTH);
        
        // Event handlers
        okButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                attemptOk();
            }
        });
        
        cancelButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        
        // Enter key support
        usernameField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                attemptOk();
            }
        });
        
        // Focus on username field
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                usernameField.requestFocusInWindow();
            }
        });
    }
    
    private void attemptOk() {
        String username = usernameField.getText().trim();
        
        if (username.isEmpty()) {
            SwingHelper.showErrorDialog(this, 
                SharedLocale.tr("login.offline.noUsernameError"), 
                SharedLocale.tr("login.offline.noUsernameTitle"));
            return;
        }
        
        enteredUsername = username;
        dispose();
    }
    
    /**
     * Show the dialog and return the entered username, or null if cancelled.
     */
    public static String showUsernameDialog(Window parent) {
        OfflineUsernameDialog dialog = new OfflineUsernameDialog(parent);
        dialog.setVisible(true);
        return dialog.enteredUsername;
    }
}