package com.universe.identity.application.profile;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

@Service
public class UpdateBioService {

    private final UserRepositoryPort userRepository;

    public UpdateBioService(
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
            String newBio
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

        user.updateBio(newBio);

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