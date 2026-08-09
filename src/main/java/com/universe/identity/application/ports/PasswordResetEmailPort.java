package com.universe.identity.application.ports;

public interface PasswordResetEmailPort {

    void sendPasswordResetEmail(
            String recipientEmail,
            String resetLink
    );
}