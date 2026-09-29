package com.universe.identity.application.oauth;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.identity.domain.UserStatus;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.messaging.OutboxPort;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleOAuthUserService Unit Tests")
class GoogleOAuthUserServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private IdGeneratorPort idGenerator;

    @Mock
    private ClockPort clock;

    @Mock
    private OutboxPort outboxPort;

    private GoogleOAuthNewUserAttemptExecutor newUserAttemptExecutor;
    private GoogleOAuthExistingUserExecutor existingUserExecutor;
    private GoogleOAuthUserService service;

    @BeforeEach
    void setUp() {
        newUserAttemptExecutor = new GoogleOAuthNewUserAttemptExecutor(userRepository, outboxPort);
        existingUserExecutor = new GoogleOAuthExistingUserExecutor(userRepository, outboxPort);
        service = new GoogleOAuthUserService(userRepository, idGenerator, clock, newUserAttemptExecutor, existingUserExecutor);
    }

    @Test
    @DisplayName("Tạo tài khoản Google mới với publicHandle được sinh tự động")
    void shouldCreateNewGoogleUserWithGeneratedPublicHandle() {
        GoogleUserInfo googleInfo = new GoogleUserInfo(
                "google-sub-999",
                "tu_tien_gia@gmail.com",
                "Tu Tiên Giả",
                "https://example.com/avatar.png",
                true
        );

        when(userRepository.findByProviderSubject(AuthProvider.GOOGLE, "google-sub-999"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(new Email("tu_tien_gia@gmail.com")))
                .thenReturn(Optional.empty());
        when(userRepository.existsByPublicHandle("tu_tien_gia"))
                .thenReturn(false);
        when(idGenerator.generate()).thenReturn(USER_ID);
        when(clock.now()).thenReturn(NOW);

        User result = service.findOrCreateGoogleUser(googleInfo);

        assertThat(result.getId()).isEqualTo(USER_ID);
        assertThat(result.getDisplayName()).isEqualTo("Tu Tiên Giả");
        assertThat(result.getPublicHandle()).isEqualTo("tu_tien_gia");
        assertThat(result.getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
        assertThat(result.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(result.getProviderSubject()).isEqualTo("google-sub-999");
        assertThat(result.getStatus()).isEqualTo(UserStatus.ACTIVE);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPublicHandle()).isEqualTo("tu_tien_gia");
    }

    @Test
    @DisplayName("Tạo tài khoản Google với display name không hợp lệ: dùng fallback UUID và KHÔNG dùng email")
    void shouldCreateGoogleUserWithDeterministicUuidFallbackWhenDisplayNameIsUnusable() {
        GoogleUserInfo googleInfo = new GoogleUserInfo(
                "google-sub-888",
                "secret_ninja_99@example.com",
                "😊✨",
                "https://example.com/avatar.png",
                true
        );

        when(userRepository.findByProviderSubject(AuthProvider.GOOGLE, "google-sub-888"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(new Email("secret_ninja_99@example.com")))
                .thenReturn(Optional.empty());
        when(idGenerator.generate()).thenReturn(USER_ID);
        when(clock.now()).thenReturn(NOW);
        when(userRepository.existsByPublicHandle(any())).thenReturn(false);

        User result = service.findOrCreateGoogleUser(googleInfo);

        assertThat(result.getDisplayName()).isEqualTo("Người dùng Google");
        // Must derive from UUID, never from email
        assertThat(result.getPublicHandle()).startsWith("user_111111111111");
        assertThat(result.getPublicHandle()).doesNotContain("secret");
        assertThat(result.getPublicHandle()).doesNotContain("ninja");
        assertThat(result.getPublicHandle()).doesNotContain("example");
    }

    @Test
    @DisplayName("Đăng nhập bằng Google providerSubject có sẵn: bảo toàn publicHandle đã có")
    void shouldPreserveExistingPublicHandleWhenGoogleUserLogsInBySubject() {
        User existingUser = User.createGoogle(
                USER_ID,
                new Email("existing@example.com"),
                "Existing Google User",
                "https://example.com/old_avatar.png",
                "google-sub-777",
                "existing_handle",
                NOW
        );

        GoogleUserInfo googleInfo = new GoogleUserInfo(
                "google-sub-777",
                "existing@example.com",
                "Existing Google User",
                "https://example.com/old_avatar.png",
                true
        );

        when(userRepository.findByProviderSubject(AuthProvider.GOOGLE, "google-sub-777"))
                .thenReturn(Optional.of(existingUser));

        User result = service.findOrCreateGoogleUser(googleInfo);

        assertThat(result.getPublicHandle()).isEqualTo("existing_handle");
        verify(userRepository).save(existingUser);
    }

    @Test
    @DisplayName("Liên kết Google vào tài khoản local qua email: bảo toàn publicHandle của tài khoản local")
    void shouldPreserveExistingPublicHandleWhenEmailUserIsLinkedToGoogle() {
        User existingLocalUser = User.createLocal(
                USER_ID,
                new Email("local_author@example.com"),
                "$2a$10$hash",
                "Local Author",
                "local_author",
                NOW
        );

        GoogleUserInfo googleInfo = new GoogleUserInfo(
                "google-sub-666",
                "local_author@example.com",
                "Local Author",
                "https://example.com/avatar.png",
                true
        );

        when(userRepository.findByProviderSubject(AuthProvider.GOOGLE, "google-sub-666"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(new Email("local_author@example.com")))
                .thenReturn(Optional.of(existingLocalUser));

        User result = service.findOrCreateGoogleUser(googleInfo);

        assertThat(result.getPublicHandle()).isEqualTo("local_author");
        assertThat(result.getProviderSubject()).isEqualTo("google-sub-666");
        verify(userRepository).save(existingLocalUser);
    }

    @Test
    @DisplayName("Tự động thử candidate tiếp theo khi candidate đầu tiên đã tồn tại")
    void shouldRetryHandleGenerationWhenCandidateCollides() {
        GoogleUserInfo googleInfo = new GoogleUserInfo(
                "google-sub-555",
                "duplicate@example.com",
                "Duplicate User",
                "https://example.com/avatar.png",
                true
        );

        when(userRepository.findByProviderSubject(AuthProvider.GOOGLE, "google-sub-555"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(new Email("duplicate@example.com")))
                .thenReturn(Optional.empty());
        when(idGenerator.generate()).thenReturn(USER_ID);
        when(clock.now()).thenReturn(NOW);

        // "duplicate_user" exists, collision candidate "duplicate_user_1111" does not exist
        when(userRepository.existsByPublicHandle("duplicate_user")).thenReturn(true);
        when(userRepository.existsByPublicHandle("duplicate_user_1111")).thenReturn(false);

        User result = service.findOrCreateGoogleUser(googleInfo);

        assertThat(result.getPublicHandle()).isEqualTo("duplicate_user_1111");
        verify(userRepository).save(result);
    }

    @Test
    @DisplayName("Khôi phục thành công khi attempt đầu tiên gặp DuplicatePublicHandleException do race condition DB")
    void shouldRecoverWhenAttemptThrowsDuplicatePublicHandleException() {
        GoogleUserInfo googleInfo = new GoogleUserInfo(
                "google-sub-444",
                "race_user@example.com",
                "Race User",
                "https://example.com/avatar.png",
                true
        );

        when(userRepository.findByProviderSubject(AuthProvider.GOOGLE, "google-sub-444"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(new Email("race_user@example.com")))
                .thenReturn(Optional.empty());
        when(idGenerator.generate()).thenReturn(USER_ID);
        when(clock.now()).thenReturn(NOW);
        when(userRepository.existsByPublicHandle(any())).thenReturn(false);

        GoogleOAuthNewUserAttemptExecutor mockExecutor = org.mockito.Mockito.mock(GoogleOAuthNewUserAttemptExecutor.class);
        when(mockExecutor.executeAttempt(eq(USER_ID), any(), eq("Race User"), any(), eq("google-sub-444"), eq("race_user"), eq(NOW)))
                .thenThrow(new com.universe.identity.domain.exceptions.DuplicatePublicHandleException("race_user", null));

        User successUser = User.createGoogle(
                USER_ID,
                new Email("race_user@example.com"),
                "Race User",
                "https://example.com/avatar.png",
                "google-sub-444",
                "race_user_1111",
                NOW
        );
        when(mockExecutor.executeAttempt(eq(USER_ID), any(), eq("Race User"), any(), eq("google-sub-444"), eq("race_user_1111"), eq(NOW)))
                .thenReturn(successUser);

        GoogleOAuthUserService serviceWithMockExecutor = new GoogleOAuthUserService(
                userRepository, idGenerator, clock, mockExecutor, existingUserExecutor
        );

        User result = serviceWithMockExecutor.findOrCreateGoogleUser(googleInfo);

        assertThat(result.getPublicHandle()).isEqualTo("race_user_1111");
        verify(mockExecutor).executeAttempt(eq(USER_ID), any(), eq("Race User"), any(), eq("google-sub-444"), eq("race_user"), eq(NOW));
        verify(mockExecutor).executeAttempt(eq(USER_ID), any(), eq("Race User"), any(), eq("google-sub-444"), eq("race_user_1111"), eq(NOW));
    }
}
