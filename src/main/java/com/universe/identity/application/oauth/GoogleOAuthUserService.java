package com.universe.identity.application.oauth;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.messaging.OutboxPort;
import com.universe.shared.time.ClockPort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class GoogleOAuthUserService {

    private static final String AGGREGATE_TYPE =
            "User";

    private static final String SOURCE_MODULE =
            "Identity";

    private final UserRepositoryPort
            userRepository;

    private final IdGeneratorPort
            idGenerator;

    private final ClockPort
            clock;

    private final OutboxPort
            outboxPort;

    public GoogleOAuthUserService(
            UserRepositoryPort userRepository,
            IdGeneratorPort idGenerator,
            ClockPort clock,
            OutboxPort outboxPort
    ) {
        this.userRepository =
                userRepository;

        this.idGenerator =
                idGenerator;

        this.clock =
                clock;

        this.outboxPort =
                outboxPort;
    }

    /**
     * Tìm hoặc tạo tài khoản dựa trên thông tin
     * đã được đọc từ Google OAuth.
     */
    @Transactional
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
                        googleUserInfo.displayName(),
                        normalizedEmail
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
            existingGoogleUser
                    .updateOAuthProfileIfMissing(
                            normalizedDisplayName,
                            normalizedAvatarUrl
                    );

            saveUserAndDomainEvents(
                    existingGoogleUser
            );

            return existingGoogleUser;
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

            existingEmailUser.linkGoogleAccount(
                    subject
            );

            existingEmailUser
                    .updateOAuthProfileIfMissing(
                            normalizedDisplayName,
                            normalizedAvatarUrl
                    );

            saveUserAndDomainEvents(
                    existingEmailUser
            );

            return existingEmailUser;
        }

        UUID userId =
                idGenerator.generate();

        Instant now =
                clock.now();

        User newUser =
                User.createGoogle(
                        userId,
                        new Email(normalizedEmail),
                        normalizedDisplayName,
                        normalizedAvatarUrl,
                        subject,
                        now
                );

        saveUserAndDomainEvents(
                newUser
        );

        return newUser;
    }

    /**
     * Lưu User và các domain event trong cùng transaction.
     *
     * Với tài khoản Google mới, User.createGoogle()
     * đã tạo UserRegisteredEvent.
     *
     * Với user đã tồn tại, danh sách event hiện tại thường rỗng,
     * nên vòng lặp không tạo thêm UserRegisteredEvent.
     */
    private void saveUserAndDomainEvents(
            User user
    ) {
        userRepository.save(user);

        user.domainEventsSnapshot()
                .forEach(event ->
                        outboxPort.saveEvent(
                                event,
                                AGGREGATE_TYPE,
                                user.getAggregateVersion(),
                                SOURCE_MODULE
                        )
                );

        user.clearDomainEvents();
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
            String displayName,
            String email
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

        int separatorIndex =
                email.indexOf('@');

        String emailPrefix =
                separatorIndex > 0
                        ? email.substring(
                                0,
                                separatorIndex
                        )
                        : "";

        String safePrefix =
                emailPrefix
                        .replaceAll(
                                "[^\\p{L}0-9_\\s]",
                                ""
                        )
                        .trim();

        if (safePrefix.length() >= 3) {
            return safePrefix.length() <= 50
                    ? safePrefix
                    : safePrefix.substring(
                            0,
                            50
                    );
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