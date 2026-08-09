package com.universe.identity.application.password;

import com.universe.identity.domain.exceptions.WeakPasswordException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    private PasswordPolicy passwordPolicy;

    @BeforeEach
    void setUp() {
        passwordPolicy =
                new PasswordPolicy();
    }

    @Test
    @DisplayName(
            "Từ chối mật khẩu null"
    )
    void shouldRejectNullPassword() {
        assertThatThrownBy(() ->
                passwordPolicy.validate(null)
        )
                .isInstanceOf(
                        WeakPasswordException.class
                )
                .hasMessage(
                        "Mật khẩu phải từ 8 đến 64 ký tự."
                );
    }

    @Test
    @DisplayName(
            "Từ chối mật khẩu ngắn hơn 8 ký tự"
    )
    void shouldRejectPasswordShorterThanEightCharacters() {
        assertThatThrownBy(() ->
                passwordPolicy.validate(
                        "Abc123"
                )
        )
                .isInstanceOf(
                        WeakPasswordException.class
                )
                .hasMessage(
                        "Mật khẩu phải từ 8 đến 64 ký tự."
                );
    }

    @Test
    @DisplayName(
            "Từ chối mật khẩu dài hơn 64 ký tự"
    )
    void shouldRejectPasswordLongerThanSixtyFourCharacters() {
        String password =
                "A1a" + "x".repeat(62);

        assertThatThrownBy(() ->
                passwordPolicy.validate(password)
        )
                .isInstanceOf(
                        WeakPasswordException.class
                )
                .hasMessage(
                        "Mật khẩu phải từ 8 đến 64 ký tự."
                );
    }

    @Test
    @DisplayName(
            "Từ chối mật khẩu không có chữ hoa"
    )
    void shouldRejectPasswordWithoutUppercaseLetter() {
        assertThatThrownBy(() ->
                passwordPolicy.validate(
                        "password123"
                )
        )
                .isInstanceOf(
                        WeakPasswordException.class
                )
                .hasMessage(
                        "Mật khẩu phải có ít nhất một chữ hoa."
                );
    }

    @Test
    @DisplayName(
            "Từ chối mật khẩu không có chữ thường"
    )
    void shouldRejectPasswordWithoutLowercaseLetter() {
        assertThatThrownBy(() ->
                passwordPolicy.validate(
                        "PASSWORD123"
                )
        )
                .isInstanceOf(
                        WeakPasswordException.class
                )
                .hasMessage(
                        "Mật khẩu phải có ít nhất một chữ thường."
                );
    }

    @Test
    @DisplayName(
            "Từ chối mật khẩu không có chữ số"
    )
    void shouldRejectPasswordWithoutDigit() {
        assertThatThrownBy(() ->
                passwordPolicy.validate(
                        "PasswordABC"
                )
        )
                .isInstanceOf(
                        WeakPasswordException.class
                )
                .hasMessage(
                        "Mật khẩu phải có ít nhất một chữ số."
                );
    }

    @Test
    @DisplayName(
            "Chấp nhận mật khẩu hợp lệ"
    )
    void shouldAcceptValidPassword() {
        assertThatCode(() ->
                passwordPolicy.validate(
                        "Password123"
                )
        )
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "Từ chối xác nhận mật khẩu không khớp"
    )
    void shouldRejectMismatchedPasswordConfirmation() {
        assertThatThrownBy(() ->
                passwordPolicy.validateConfirmation(
                        "Password123",
                        "Password456"
                )
        )
                .isInstanceOf(
                        WeakPasswordException.class
                )
                .hasMessage(
                        "Xác nhận mật khẩu không khớp."
                );
    }

    @Test
    @DisplayName(
            "Chấp nhận xác nhận mật khẩu trùng khớp"
    )
    void shouldAcceptMatchingPasswordConfirmation() {
        assertThatCode(() ->
                passwordPolicy.validateConfirmation(
                        "Password123",
                        "Password123"
                )
        )
                .doesNotThrowAnyException();
    }
}