package com.universe.identity.infrastructure.persistence;

import com.universe.identity.application.security.AuthenticatedRequestIdentity;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
        AuthenticatedRequestIdentityQueryAdapter.class
})
@DisplayName("AuthenticatedRequestIdentity Persistence Integration Tests")
class AuthenticatedRequestIdentityPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepositoryAdapter userRepositoryAdapter;

    @Autowired
    private SpringDataUserJpaRepository repository;

    @Autowired
    private AuthenticatedRequestIdentityQueryAdapter queryAdapter;

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM identity_users");
    }

    @Test
    @DisplayName("Real DB: findRequestIdentityByEmail returns projection with persisted publicHandle")
    void shouldFindRequestIdentityWithPersistedPublicHandle() {
        String email = "han_lap@universe.local";
        String publicHandle = "han_lap";

        User user = User.createLocal(
                USER_ID,
                new Email(email),
                "$2a$10$hash",
                "Hàn Lập",
                publicHandle,
                NOW
        );
        userRepositoryAdapter.save(user);

        // 1. Direct Spring Data JPA repository / query path test
        Optional<AuthenticatedRequestIdentityProjection> projectionOpt =
                repository.findRequestIdentityByEmail(email);

        assertThat(projectionOpt).isPresent();
        AuthenticatedRequestIdentityProjection projection = projectionOpt.get();
        assertThat(projection.getUserId()).isEqualTo(USER_ID.toString());
        assertThat(projection.getNormalizedEmail()).isEqualTo(email);
        assertThat(projection.getDisplayName()).isEqualTo("Hàn Lập");
        assertThat(projection.getPublicHandle()).isEqualTo("han_lap");

        // 2. QueryAdapter integration path test
        Optional<AuthenticatedRequestIdentity> identityOpt =
                queryAdapter.findByEmail(email);

        assertThat(identityOpt).isPresent();
        AuthenticatedRequestIdentity identity = identityOpt.get();
        assertThat(identity.userId()).isEqualTo(USER_ID);
        assertThat(identity.normalizedEmail()).isEqualTo(email);
        assertThat(identity.displayName()).isEqualTo("Hàn Lập");
        assertThat(identity.publicHandle()).isEqualTo("han_lap");
    }
}
