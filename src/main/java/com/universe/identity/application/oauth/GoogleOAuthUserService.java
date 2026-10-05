package com.universe.identity.application.oauth;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.PublicHandleGenerator;
import com.universe.identity.domain.User;
import com.universe.identity.domain.exceptions.DuplicatePublicHandleException;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class GoogleOAuthUserService {

    private final UserRepositoryPort userRepository;
    private final IdGeneratorPort idGenerator;
    private final ClockPort clock;
    private final GoogleOAuthNewUserAttemptExecutor newUserAttemptExecutor;
    private final GoogleOAuthExistingUserExecutor existingUserExecutor;

    public GoogleOAuthUserService(
            UserRepositoryPort userRepository,
            IdGeneratorPort idGenerator,
            ClockPort clock,
            GoogleOAuthNewUserAttemptExecutor newUserAttemptExecutor,
            GoogleOAuthExistingUserExecutor existingUserExecutor
    ) {
        this.userRepository =
                Objects.requireNonNull(userRepository, "userRepository cannot be null");
        this.idGenerator =
                Objects.requireNonNull(idGenerator, "idGenerator cannot be null");
        this.clock =
                Objects.requireNonNull(clock, "clock cannot be null");
        this.newUserAttemptExecutor =
                Objects.requireNonNull(newUserAttemptExecutor, "newUserAttemptExecutor cannot be null");
        this.existingUserExecutor =
                Objects.requireNonNull(existingUserExecutor, "existingUserExecutor cannot be null");
    }

    /**
     * Tìm hoặc tạo tài khoản dựa trên thông tin
     * đã được đọc từ Google OAuth.
     */
    public User findOrCreateGoogleUser(
            GoogleUserInfo googleUserInfo
    ) {
        validateGoogleUserInfo(
                googleUserInfo
        );

        String subject =
                googleUserInfo
                        .subject()
                        .trim();

        String normalizedEmail =
                normalizeEmail(
                        googleUserInfo.email()
                );

        String normalizedDisplayName =
                normalizeDisplayName(
                        googleUserInfo.displayName()
                );

        String normalizedAvatarUrl =
                normalizeNullableValue(
                        googleUserInfo.avatarUrl()
                );

        /*
         * Ưu tiên tìm theo Google provider subject.
         *
         * Claim "sub" là mã định danh ổn định
         * của tài khoản trong Google.
         */
        User existingGoogleUser =
                userRepository
                        .findByProviderSubject(
                                AuthProvider.GOOGLE,
                                subject
                        )
                        .orElse(null);

        if (existingGoogleUser != null) {
            return existingUserExecutor.updateByProviderSubject(
                    existingGoogleUser,
                    normalizedDisplayName,
                    normalizedAvatarUrl
            );
        }

        User existingEmailUser =
                userRepository
                        .findByEmail(
                                new Email(
                                         normalizedEmail
                                )
                        )
                        .orElse(null);

        if (existingEmailUser != null) {
            /*
             * Chỉ liên kết tài khoản local với Google
             * khi Google đã xác minh email.
             */
            if (!googleUserInfo.emailVerified()) {
                throw new IllegalStateException(
                        "Google chưa xác minh địa chỉ email."
                );
            }

            return existingUserExecutor.linkAndProfileUpdate(
                    existingEmailUser,
                    subject,
                    normalizedDisplayName,
                    normalizedAvatarUrl
            );
        }

        UUID userId =
                idGenerator.generate();

        Instant now =
                clock.now();

        int finalAttemptIndex =
                PublicHandleGenerator.MAX_COLLISION_ATTEMPTS + 1;

        for (int attempt = 0; attempt <= finalAttemptIndex; attempt++) {
            String candidateHandle =
                    PublicHandleGenerator.candidateForAttempt(
                            googleUserInfo.displayName(),
                            userId,
                            attempt
                    );

            if (attempt <= PublicHandleGenerator.MAX_COLLISION_ATTEMPTS
                    && userRepository.existsByPublicHandle(candidateHandle)) {
                continue;
            }

            try {
                return newUserAttemptExecutor.executeAttempt(
                        userId,
                        new Email(normalizedEmail),
                        normalizedDisplayName,
                        normalizedAvatarUrl,
                        subject,
                        candidateHandle,
                        now
                );
            } catch (DuplicatePublicHandleException ex) {
                if (attempt >= finalAttemptIndex) {
                    throw new IllegalStateException(
                            "Không thể cấp phát public handle duy nhất sau " + (finalAttemptIndex + 1) + " lần thử.",
                            ex
                    );
                }
            }
        }

        throw new IllegalStateException(
                "Không thể cấp phát public handle duy nhất."
        );
    }

    private void validateGoogleUserInfo(
            GoogleUserInfo googleUserInfo
    ) {
        if (googleUserInfo == null) {
            throw new IllegalArgumentException(
                    "Thông tin Google không được để trống."
            );
        }

        if (googleUserInfo.subject() == null
                || googleUserInfo.subject().isBlank()) {

            throw new IllegalStateException(
                    "Google không trả về mã định danh người dùng."
            );
        }

        if (googleUserInfo.email() == null
                || googleUserInfo.email().isBlank()) {

            throw new IllegalStateException(
                    "Google không trả về email người dùng."
            );
        }

        if (!googleUserInfo.emailVerified()) {
            throw new IllegalStateException(
                    "Email Google chưa được xác minh."
            );
        }
    }

    private String normalizeEmail(
            String email
    ) {
        String normalized =
                email.trim()
                        .toLowerCase(Locale.ROOT);

        /*
         * Value Object Email sẽ tiếp tục
         * kiểm tra định dạng đầy đủ.
         */
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "Email Google không hợp lệ."
            );
        }

        return normalized;
    }

    private String normalizeDisplayName(
            String displayName
    ) {
        if (displayName != null
                && !displayName.isBlank()) {

            String normalized =
                    displayName.trim();

            if (normalized.length() > 50) {
                normalized =
                        normalized.substring(
                                0,
                                50
                        );
            }

            /*
             * Domain User chỉ cho phép chữ Unicode,
             * số, dấu gạch dưới và khoảng trắng.
             */
            String safeName =
                    normalized
                            .replaceAll(
                                    "[^\\p{L}0-9_\\s]",
                                    ""
                              )
                            .trim();

            if (safeName.length() >= 3) {
                return safeName.length() <= 50
                        ? safeName
                        : safeName.substring(
                                0,
                                50
                        );
            }
        }

        return "Người dùng Google";
    }

    private String normalizeNullableValue(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}