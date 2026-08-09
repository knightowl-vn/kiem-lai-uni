package com.universe.identity.application.password;

import com.universe.identity.domain.exceptions.WeakPasswordException;

import org.springframework.stereotype.Component;

@Component
public class PasswordPolicy {

    public void validate(
            String password
    ) {
        if (password == null
                || password.length() < 8
                || password.length() > 64) {

            throw new WeakPasswordException(
                    "Mật khẩu phải từ 8 đến 64 ký tự."
            );
        }

        if (!password.matches(".*[A-Z].*")) {
            throw new WeakPasswordException(
                    "Mật khẩu phải có ít nhất một chữ hoa."
            );
        }

        if (!password.matches(".*[a-z].*")) {
            throw new WeakPasswordException(
                    "Mật khẩu phải có ít nhất một chữ thường."
            );
        }

        if (!password.matches(".*\\d.*")) {
            throw new WeakPasswordException(
                    "Mật khẩu phải có ít nhất một chữ số."
            );
        }
    }

    public void validateConfirmation(
            String password,
            String confirmation
    ) {
        validate(password);

        if (confirmation == null
                || !password.equals(confirmation)) {

            throw new WeakPasswordException(
                    "Xác nhận mật khẩu không khớp."
            );
        }
    }
}