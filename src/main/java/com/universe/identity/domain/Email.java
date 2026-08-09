package com.universe.identity.domain;

import com.universe.identity.domain.exceptions.InvalidEmailException;

import java.util.Locale;

public record Email(String value) {

    private static final String EMAIL_REGEX =
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$";

    public Email {
        if (value == null || value.isBlank()) {
            throw new InvalidEmailException(
                    "Email không được để trống."
            );
        }

        value =
                value.trim()
                        .toLowerCase(Locale.ROOT);

        if (value.length() > 255) {
            throw new InvalidEmailException(
                    "Email không được vượt quá 255 ký tự."
            );
        }

        if (!value.matches(EMAIL_REGEX)) {
            throw new InvalidEmailException(
                    "Email không đúng định dạng."
            );
        }
    }
}