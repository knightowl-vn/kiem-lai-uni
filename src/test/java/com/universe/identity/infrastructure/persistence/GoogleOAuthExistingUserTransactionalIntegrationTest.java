package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.oauth.GoogleOAuthExistingUserExecutor;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.shared.messaging.OutboxPort;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Import({
        UserRepositoryAdapter.class,
        UserPersistenceMapper.class,
        GoogleOAuthExistingUserExecutor.class,
        GoogleOAuthExistingUserTransactionalIntegrationTest.TestConfig.class
})
class GoogleOAuthExistingUserTransactionalIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant TEST_TIME = Instant.parse("2026-09-01T12:00:00Z");

    @DynamicPropertySource
    static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public OutboxPort outboxPort() {
            return mock(OutboxPort.class);
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepositoryAdapter userRepositoryAdapter;

    @Autowired
    private GoogleOAuthExistingUserExecutor existingUserExecutor;

    @Autowired
    private OutboxPort outboxPort;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM identity_users");
        reset(outboxPort);
    }

    @Test
    @DisplayName("updateByProviderSubject thành công: cập nhật avatarUrl được commit vào database")
    void shouldCommitUpdateByProviderSubjectSuccessfully() {
        User existingUser = User.createGoogle(
                USER_ID,
                new Email("google_user@example.com"),
                "Google User",
                null,
                "google-sub-original",
                "google_user",
                TEST_TIME
        );
        userRepositoryAdapter.save(existingUser);

        existingUserExecutor.updateByProviderSubject(
                existingUser,
                "Google User Updated",
                "https://example.com/new_avatar.png"
        );

        Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                "SELECT avatar_url, display_name FROM identity_users WHERE id = ?",
                USER_ID.toString()
        );
        assertThat(dbRow.get("avatar_url")).isEqualTo("https://example.com/new_avatar.png");
    }

    @Test
    @DisplayName("linkAndProfileUpdate thành công: liên kết Google subject và cập nhật avatarUrl được commit vào database")
    void shouldCommitLinkAndProfileUpdateSuccessfully() {
        User localUser = User.createLocal(
                USER_ID,
                new Email("local_link@example.com"),
                "$2a$10$hash",
                "Local Link User",
                "local_link_user",
                TEST_TIME
        );
        userRepositoryAdapter.save(localUser);

        existingUserExecutor.linkAndProfileUpdate(
                localUser,
                "google-sub-linked",
                "Local Link User",
                "https://example.com/linked_avatar.png"
        );

        Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                "SELECT provider_subject, avatar_url FROM identity_users WHERE id = ?",
                USER_ID.toString()
        );
        assertThat(dbRow.get("provider_subject")).isEqualTo("google-sub-linked");
        assertThat(dbRow.get("avatar_url")).isEqualTo("https://example.com/linked_avatar.png");
    }

    @Test
    @DisplayName("Khi outboxPort ném lỗi trong updateByProviderSubject: transaction rollback và database không bị thay đổi")
    void shouldRollbackUpdateByProviderSubjectWhenOutboxFails() {
        User existingUser = User.createGoogle(
                USER_ID,
                new Email("google_rollback@example.com"),
                "Original Name",
                null,
                "google-sub-rb",
                "google_rollback",
                TEST_TIME
        );
        userRepositoryAdapter.save(existingUser);

        doThrow(new RuntimeException("Simulated outbox exception"))
                .when(outboxPort).saveEvent(any(), anyString(), anyLong(), anyString());

        assertThatThrownBy(() -> existingUserExecutor.updateByProviderSubject(
                existingUser,
                "Changed Name",
                "https://example.com/should_rollback.png"
        )).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated outbox exception");

        // Verify DB rollback: avatar_url remains null
        Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                "SELECT avatar_url, display_name FROM identity_users WHERE id = ?",
                USER_ID.toString()
        );
        assertThat(dbRow.get("avatar_url")).isNull();
        assertThat(dbRow.get("display_name")).isEqualTo("Original Name");
    }

    @Test
    @DisplayName("Khi outboxPort ném lỗi trong linkAndProfileUpdate: transaction rollback và provider_subject không bị liên kết")
    void shouldRollbackLinkAndProfileUpdateWhenOutboxFails() {
        User localUser = User.createLocal(
                USER_ID,
                new Email("local_rollback@example.com"),
                "$2a$10$hash",
                "Local Rollback User",
                "local_rollback_user",
                TEST_TIME
        );
        userRepositoryAdapter.save(localUser);

        doThrow(new RuntimeException("Simulated outbox exception"))
                .when(outboxPort).saveEvent(any(), anyString(), anyLong(), anyString());

        assertThatThrownBy(() -> existingUserExecutor.linkAndProfileUpdate(
                localUser,
                "google-sub-should-rollback",
                "Local Rollback User",
                "https://example.com/should_rollback.png"
        )).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated outbox exception");

        // Verify DB rollback: provider_subject remains null
        Map<String, Object> dbRow = jdbcTemplate.queryForMap(
                "SELECT provider_subject, avatar_url FROM identity_users WHERE id = ?",
                USER_ID.toString()
        );
        assertThat(dbRow.get("provider_subject")).isNull();
        assertThat(dbRow.get("avatar_url")).isNull();
    }
}
