package com.universe.identity.application.profile;

import com.universe.identity.application.ports.AvatarStoragePort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Objects;

@Service
public class UpdateAvatarService {

    private final UserRepositoryPort
            userRepository;

    private final AvatarStoragePort
            avatarStorage;

    public UpdateAvatarService(
            UserRepositoryPort userRepository,
            AvatarStoragePort avatarStorage
    ) {
        this.userRepository =
                Objects.requireNonNull(
                        userRepository,
                        "User repository không được để trống."
                );

        this.avatarStorage =
                Objects.requireNonNull(
                        avatarStorage,
                        "Avatar storage không được để trống."
                );
    }

    @Transactional
    public void execute(
            String currentUserEmail,
            MultipartFile avatarFile
    ) {
        Email email =
                new Email(
                        normalizeEmail(
                                currentUserEmail
                        )
                );

        User user =
                userRepository
                        .findByEmail(email)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Không tìm thấy tài khoản đang đăng nhập."
                                )
                        );

        String avatarUrl =
                avatarStorage.uploadAvatar(
                        user.getId(),
                        avatarFile
                );

        user.updateAvatarUrl(
                avatarUrl
        );

        userRepository.save(user);
    }

    private String normalizeEmail(
            String email
    ) {
        if (email == null
                || email.isBlank()) {

            throw new IllegalStateException(
                    "Không xác định được email người dùng."
            );
        }

        return email
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}