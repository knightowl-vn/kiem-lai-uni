package com.universe.identity.application.password;

import com.universe.identity.application.ports.PasswordHasherPort;
import com.universe.identity.domain.User;
import org.springframework.stereotype.Component;

@Component
public class PasswordUpdater {

    private final PasswordHasherPort passwordHasher;

    public PasswordUpdater(
            PasswordHasherPort passwordHasher
    ) {
        this.passwordHasher =
                passwordHasher;
    }

    public void updatePassword(
            User user,
            String rawPassword
    ) {
        if (user == null) {
            throw new IllegalArgumentException(
                    "User không được để trống."
            );
        }

        if (rawPassword == null
                || rawPassword.isBlank()) {

            throw new IllegalArgumentException(
                    "Mật khẩu mới không được để trống."
            );
        }

        String passwordHash =
                passwordHasher.hash(
                        rawPassword
                );

        if (user.hasPassword()) {
            user.updatePasswordHash(
                    passwordHash
            );
        } else {
            user.createPasswordHash(
                    passwordHash
            );
        }
    }
}