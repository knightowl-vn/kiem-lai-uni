package com.universe.identity.application.oauth;

import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.shared.messaging.OutboxPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleOAuthExistingUserExecutor Unit Tests")
class GoogleOAuthExistingUserExecutorTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private OutboxPort outboxPort;

    private GoogleOAuthExistingUserExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new GoogleOAuthExistingUserExecutor(userRepository, outboxPort);
    }

    @Test
    @DisplayName("updateByProviderSubject: cập nhật profile còn thiếu, lưu User và xuất domain events")
    void shouldUpdateByProviderSubjectAndSave() {
        User existingUser = User.createGoogle(
                USER_ID,
                new Email("test@example.com"),
                "Original Name",
                null,
                "google-sub-123",
                "original_handle",
                NOW
        );
        // Clear creation event to test new events
        existingUser.clearDomainEvents();

        User result = executor.updateByProviderSubject(
                existingUser,
                "New Name",
                "https://example.com/new-avatar.png"
        );

        assertThat(result.getAvatarUrl()).isEqualTo("https://example.com/new-avatar.png");
        verify(userRepository).save(existingUser);
        assertThat(existingUser.domainEventsSnapshot()).isEmpty();
    }

    @Test
    @DisplayName("linkAndProfileUpdate: liên kết Google subject, cập nhật profile, lưu User và xuất domain events")
    void shouldLinkGoogleAccountAndUpdateProfile() {
        User existingUser = User.createLocal(
                USER_ID,
                new Email("local@example.com"),
                "$2a$10$hash",
                "Local User",
                "local_handle",
                NOW
        );
        existingUser.clearDomainEvents();

        User result = executor.linkAndProfileUpdate(
                existingUser,
                "google-sub-456",
                "Local User Updated",
                "https://example.com/avatar.png"
        );

        assertThat(result.getProviderSubject()).isEqualTo("google-sub-456");
        assertThat(result.getAvatarUrl()).isEqualTo("https://example.com/avatar.png");
        verify(userRepository).save(existingUser);
        assertThat(existingUser.domainEventsSnapshot()).isEmpty();
    }
}
