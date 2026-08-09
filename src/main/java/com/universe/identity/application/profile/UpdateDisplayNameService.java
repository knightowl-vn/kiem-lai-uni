package com.universe.identity.application.profile;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

@Service
public class UpdateDisplayNameService {

    private final UserRepositoryPort userRepository;

    public UpdateDisplayNameService(
            UserRepositoryPort userRepository
    ) {
        this.userRepository =
                Objects.requireNonNull(
                        userRepository,
                        "User repository không được để trống."
                );
    }

    @Transactional
    public void execute(
            String currentUserEmail,
            String newDisplayName
    ) {
        Email email =
                new Email(
                        normalizeEmail(currentUserEmail)
                );

        User user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Không tìm thấy tài khoản đang đăng nhập."
                                )
                        );

        /*
         * Domain User tự:
         * - trim tên;
         * - kiểm tra từ 3 đến 50 ký tự;
         * - kiểm tra ký tự hợp lệ;
         * - tăng aggregateVersion nếu tên thay đổi.
         */
        user.updateDisplayName(
                newDisplayName
        );

        userRepository.save(user);
    }

    private String normalizeEmail(
            String email
    ) {
        if (email == null || email.isBlank()) {
            throw new IllegalStateException(
                    "Không xác định được email người dùng."
            );
        }

        return email
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}