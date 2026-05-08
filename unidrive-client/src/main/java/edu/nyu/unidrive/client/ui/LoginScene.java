package edu.nyu.unidrive.client.ui;

import edu.nyu.unidrive.client.net.AuthApiClient;
import edu.nyu.unidrive.client.session.UserRole;
import edu.nyu.unidrive.common.dto.LoginResponse;
import java.io.IOException;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public final class LoginScene {

    private final AuthApiClient authApiClient;
    private final LoginCallback onSuccess;

    public LoginScene(AuthApiClient authApiClient, LoginCallback onSuccess) {
        this.authApiClient = authApiClient;
        this.onSuccess = onSuccess;
    }

    public Scene build() {
        TextField emailField = new TextField();
        emailField.setPromptText("student@nyu.edu");

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("password123");

        Label statusLabel = new Label();

        Button loginButton = new Button("Login");
        loginButton.setOnAction(event -> {
            String email = emailField.getText() == null ? "" : emailField.getText().trim();
            String password = passwordField.getText() == null ? "" : passwordField.getText();
            if (email.isEmpty()) {
                statusLabel.setText("Please enter your NYU email.");
                return;
            }
            if (password.isBlank()) {
                statusLabel.setText("Please enter your password.");
                return;
            }
            statusLabel.setText("Authenticating...");
            try {
                LoginResponse response = authApiClient.login(email, password);
                onSuccess.onLogin(response.userId(), UserRole.valueOf(response.role()));
            } catch (IOException | RuntimeException exception) {
                String message = exception.getMessage();
                statusLabel.setText(message == null || message.isBlank()
                    ? "Login failed. Please try again."
                    : message);
            }
        });

        VBox root = new VBox(
            12,
            new Label("University Drive"),
            new Label("Email:"),
            emailField,
            new Label("Password:"),
            passwordField,
            new Label("Demo accounts: student@nyu.edu or instructor@nyu.edu / password123"),
            loginButton,
            statusLabel
        );
        root.setPadding(new Insets(24));
        return new Scene(root, 420, 320);
    }

    @FunctionalInterface
    public interface LoginCallback {
        void onLogin(String userId, UserRole role);
    }
}
