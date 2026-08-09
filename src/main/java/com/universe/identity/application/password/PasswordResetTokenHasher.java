package com.universe.identity.application.password;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class PasswordResetTokenHasher {

    private static final String HASH_ALGORITHM = "SHA-256";

    public String hash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException(
                    "Reset token không hợp lệ."
            );
        }

        try {
            MessageDigest digest =
                    MessageDigest.getInstance(HASH_ALGORITHM);

            byte[] tokenBytes =
                    rawToken.getBytes(StandardCharsets.UTF_8);

            byte[] hashBytes =
                    digest.digest(tokenBytes);

            return HexFormat.of().formatHex(hashBytes);

        } catch (NoSuchAlgorithmException exception) {
            /*
             * SHA-256 luôn có trong Java chuẩn.
             * Nếu xảy ra lỗi này thì môi trường Java đang gặp
             * vấn đề nghiêm trọng, nên chuyển thành lỗi hệ thống.
             */
            throw new IllegalStateException(
                    "Thuật toán SHA-256 không khả dụng.",
                    exception
            );
        }
    }
}