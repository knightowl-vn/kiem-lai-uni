package com.universe.identity.application.password;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreatePasswordService {

    private final UserRepositoryPort userRepository;
    private final PasswordPolicy passwordPolicy;
    private final PasswordUpdater passwordUpdater;

    public CreatePasswordService(
            UserRepositoryPort userRepository,
            PasswordPolicy passwordPolicy,
            PasswordUpdater passwordUpdater
    ) {
        this.userRepository =
                userRepository;

        this.passwordPolicy =
                passwordPolicy;

        this.passwordUpdater =
                passwordUpdater;
    }

    @Transactional
    public void createPassword(
            String email,
            String newPassword,
            String confirmPassword
    ) {
        User user =
                findUser(email);

        if (user.hasPassword()) {
            throw new IllegalStateException(
                    "Tài khoản đã có mật khẩu."
            );
        }

        if (user.getAuthProvider()
                != AuthProvider.GOOGLE) {

            throw new IllegalStateException(
                    "Tài khoản không đủ điều kiện tạo mật khẩu."
            );
        }

        passwordPolicy.validateConfirmation(
                newPassword,
                confirmPassword
        );

        passwordUpdater.updatePassword(
                user,
                newPassword
        );

        /*
         * PasswordUpdater chỉ thay đổi User domain.
         * Phải lưu aggregate lại qua repository port.
         */
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