package com.universe.identity.application.password;

import com.universe.identity.application.ports.PasswordHasherPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChangePasswordService {

    private final UserRepositoryPort userRepository;
    private final PasswordHasherPort passwordHasher;
    private final PasswordPolicy passwordPolicy;
    private final PasswordUpdater passwordUpdater;

    public ChangePasswordService(
            UserRepositoryPort userRepository,
            PasswordHasherPort passwordHasher,
            PasswordPolicy passwordPolicy,
            PasswordUpdater passwordUpdater
    ) {
        this.userRepository =
                userRepository;

        this.passwordHasher =
                passwordHasher;

        this.passwordPolicy =
                passwordPolicy;

        this.passwordUpdater =
                passwordUpdater;
    }

    @Transactional
    public void changePassword(
            String email,
            String currentPassword,
            String newPassword,
            String confirmNewPassword
    ) {
        User user =
                findUser(email);

        if (!user.hasPassword()) {
            throw new IllegalStateException(
                    "Tài khoản chưa có mật khẩu. "
                            + "Hãy tạo mật khẩu trước."
            );
        }

        if (currentPassword == null
                || currentPassword.isBlank()) {

            throw new IllegalArgumentException(
                    "Vui lòng nhập mật khẩu hiện tại."
            );
        }

        String currentPasswordHash =
                user.getPasswordHash();

        if (!passwordHasher.verify(
                currentPassword,
                currentPasswordHash
        )) {
            throw new IllegalArgumentException(
                    "Mật khẩu hiện tại không đúng."
            );
        }

        passwordPolicy.validateConfirmation(
                newPassword,
                confirmNewPassword
        );

        if (passwordHasher.verify(
                newPassword,
                currentPasswordHash
        )) {
            throw new IllegalArgumentException(
                    "Mật khẩu mới phải khác mật khẩu hiện tại."
            );
        }

        passwordUpdater.updatePassword(
                user,
                newPassword
        );

        userRepository.save(user);
    }

    private User findUser(
            String email
    ) {
        Email normalizedEmail =
                new Email(email);

        return userRepository
                .findByEmail(normalizedEmail)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Không tìm thấy tài khoản."
                        )
                );
    }
}