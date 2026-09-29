package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.oauth.GoogleOAuthExistingUserExecutor;
import com.universe.identity.application.oauth.GoogleOAuthNewUserAttemptExecutor;
import com.universe.identity.application.oauth.GoogleOAuthUserService;
import com.universe.identity.application.oauth.GoogleUserInfo;
import com.universe.identity.application.password.PasswordPolicy;
import com.universe.identity.application.ports.PasswordHasherPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.application.registration.RegisterUserAttemptExecutor;
import com.universe.identity.application.registration.RegisterUserCommand;
import com.universe.identity.application.registration.RegisterUserUseCase;
import com.universe.identity.contracts.dto.UserDTO;
import com.universe.identity.domain.AuthProvider;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.messaging.OutboxPort;
import com.universe.shared.time.ClockPort;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
        RegisterUserAttemptExecutor.class,
        GoogleOAuthNewUserAttemptExecutor.class,
        GoogleOAuthExistingUserExecutor.class,
        UserPublicHandleConflictRecoveryIntegrationTest.TestConfig.class
})
class UserPublicHandleConflictRecoveryIntegrationTest {

    private static final UUID PRE_EXISTING_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEW_USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant TEST_TIME = Instant.parse("2026-09-01T12:00:00Z");

    @DynamicPropertySource
    static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        public ClockPort clockPort() {
            return () -> TEST_TIME;
        }

        @Bean
        public IdGeneratorPort idGeneratorPort() {
            return () -> NEW_USER_ID;
        }

        @Bean
        public OutboxPort outboxPort() {
            return mock(OutboxPort.class);
        }

        @Bean
        public PasswordHasherPort passwordHasherPort() {
            return new PasswordHasherPort() {
                @Override
                public String hash(String rawPassword) {
                    return "$2a$10$fakeHashedPasswordForTest";
                }

                @Override
                public boolean verify(String rawPassword, String encodedPassword) {
                    return true;
                }
            };
        }

        @Bean
        public PasswordPolicy passwordPolicy() {
            return new PasswordPolicy();
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepositoryAdapter userRepositoryAdapter;

    @Autowired
    private RegisterUserAttemptExecutor registerUserAttemptExecutor;

    @Autowired
    private GoogleOAuthNewUserAttemptExecutor googleOAuthNewUserAttemptExecutor;

    @Autowired
    private GoogleOAuthExistingUserExecutor googleOAuthExistingUserExecutor;

    @Autowired
    private PasswordHasherPort passwordHasherPort;

    @Autowired
    private PasswordPolicy passwordPolicy;

    @Autowired
    private ClockPort clockPort;

    @Autowired
    private OutboxPort outboxPort;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM identity_users");
        Mockito.reset(outboxPort);
    }

    @Test
    @DisplayName("DB Unique(public_handle) collision recovery on local registration: rolls back attempt 0 and commits attempt 1 in fresh transaction")
    void shouldRecoverFromDbUniqueHandleConflictOnLocalRegistration() {
        // 1. Pre-insert existing user with handle "athena" directly into database
        User existingUser = User.createLocal(
                PRE_EXISTING_USER_ID,
                new Email("existing@example.com"),
                "$2a$10$hash",
                "Athena Original",
                "athena",
                TEST_TIME
        );
        userRepositoryAdapter.save(existingUser);
        Mockito.clearInvocations(outboxPort);

        // 2. Simulate concurrent race: pre-check returned false (e.g. race window before concurrent insert committed)
        // We wrap UserRepositoryPort to simulate pre-check passing for attempt 0
        AtomicBoolean precheckSimulated = new AtomicBoolean(false);
        UserRepositoryPort racingRepositoryPort = new RacingUserRepositoryPortWrapper(userRepositoryAdapter, "athena", precheckSimulated);

        RegisterUserUseCase useCase = new RegisterUserUseCase(
                racingRepositoryPort,
                passwordHasherPort,
                passwordPolicy,
                () -> NEW_USER_ID,
                clockPort,
                registerUserAttemptExecutor
        );

        // 3. Execute registration for new user whose base handle would be "athena"
        RegisterUserCommand command = new RegisterUserCommand(
                "athena.new@example.com",
                "Password123",
                "Athena"
        );

        UserDTO result = useCase.execute(command);

        // 4. Verification: attempt 0 hit DB UNIQUE constraint and rolled back; attempt 1 ("athena_1111") committed
        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(NEW_USER_ID);
        assertThat(result.publicHandle()).isEqualTo("athena_1111");

        // Verify DB state: exactly 2 users exist with their respective handles
        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM identity_users",
                Integer.class
        );
        assertThat(userCount).isEqualTo(2);

        Map<String, Object> newDbRow = jdbcTemplate.queryForMap(
                "SELECT id, email, public_handle, display_name FROM identity_users WHERE id = ?",
                NEW_USER_ID.toString()
        );
        assertThat(newDbRow.get("public_handle")).isEqualTo("athena_1111");
        assertThat(newDbRow.get("email")).isEqualTo("athena.new@example.com");

        // Verify Outbox sequencing: failed attempt 0 did NOT publish event; successful attempt 1 published exactly once
        verify(outboxPort, times(1)).saveEvent(any(), eq("User"), eq(1L), eq("Identity"));
    }

    @Test
    @DisplayName("DB Unique(public_handle) collision recovery on Google OAuth: rolls back attempt 0 and commits attempt 1 in fresh transaction")
    void shouldRecoverFromDbUniqueHandleConflictOnGoogleOAuthRegistration() {
        // 1. Pre-insert existing user with handle "google_author"
        User existingUser = User.createLocal(
                PRE_EXISTING_USER_ID,
                new Email("existing.google@example.com"),
                "$2a$10$hash",
                "Google Author",
                "google_author",
                TEST_TIME
        );
        userRepositoryAdapter.save(existingUser);
        Mockito.clearInvocations(outboxPort);

        // 2. Simulate concurrent race for "google_author"
        AtomicBoolean precheckSimulated = new AtomicBoolean(false);
        UserRepositoryPort racingRepositoryPort = new RacingUserRepositoryPortWrapper(userRepositoryAdapter, "google_author", precheckSimulated);

        GoogleOAuthUserService googleService = new GoogleOAuthUserService(
                racingRepositoryPort,
                () -> NEW_USER_ID,
                clockPort,
                googleOAuthNewUserAttemptExecutor,
                googleOAuthExistingUserExecutor
        );

        GoogleUserInfo googleUserInfo = new GoogleUserInfo(
                "google-sub-unique-123",
                "new.oauth@example.com",
                "Google Author",
                "https://example.com/avatar.png",
                true
        );

        User result = googleService.findOrCreateGoogleUser(googleUserInfo);

        // 3. Verification: committed with collision candidate "google_author_1111"
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(NEW_USER_ID);
        assertThat(result.getPublicHandle()).isEqualTo("google_author_1111");

        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM identity_users",
                Integer.class
        );
        assertThat(userCount).isEqualTo(2);

        Map<String, Object> newDbRow = jdbcTemplate.queryForMap(
                "SELECT id, email, public_handle, auth_provider, provider_subject FROM identity_users WHERE id = ?",
                NEW_USER_ID.toString()
        );
        assertThat(newDbRow.get("public_handle")).isEqualTo("google_author_1111");
        assertThat(newDbRow.get("auth_provider")).isEqualTo("GOOGLE");
        assertThat(newDbRow.get("provider_subject")).isEqualTo("google-sub-unique-123");

        // Verify Outbox sequencing: failed attempt 0 did NOT publish event; successful attempt 1 published exactly once
        verify(outboxPort, times(1)).saveEvent(any(), eq("User"), eq(1L), eq("Identity"));
    }

    /**
     * Wrapper delegating all calls to real adapter except existsByPublicHandle for the raced handle on attempt 0.
     */
    private static class RacingUserRepositoryPortWrapper implements UserRepositoryPort {
        private final UserRepositoryPort delegate;
        private final String racedHandle;
        private final AtomicBoolean precheckSimulated;

        public RacingUserRepositoryPortWrapper(UserRepositoryPort delegate, String racedHandle, AtomicBoolean precheckSimulated) {
            this.delegate = delegate;
            this.racedHandle = racedHandle;
            this.precheckSimulated = precheckSimulated;
        }

        @Override
        public java.util.Optional<User> findByEmail(Email email) {
            return delegate.findByEmail(email);
        }

        @Override
        public java.util.Optional<User> findById(UUID userId) {
            return delegate.findById(userId);
        }

        @Override
        public java.util.Optional<User> findByProviderSubject(AuthProvider authProvider, String providerSubject) {
            return delegate.findByProviderSubject(authProvider, providerSubject);
        }

        @Override
        public java.util.Optional<User> findByPublicHandle(String publicHandle) {
            return delegate.findByPublicHandle(publicHandle);
        }

        @Override
        public boolean existsByEmail(Email email) {
            return delegate.existsByEmail(email);
        }

        @Override
        public boolean existsByPublicHandle(String publicHandle) {
            if (racedHandle.equals(publicHandle) && !precheckSimulated.getAndSet(true)) {
                // Simulate race: returns false so use case attempts insertion against DB
                return false;
            }
            return delegate.existsByPublicHandle(publicHandle);
        }

        @Override
        public void save(User user) {
            delegate.save(user);
        }
    }
}
