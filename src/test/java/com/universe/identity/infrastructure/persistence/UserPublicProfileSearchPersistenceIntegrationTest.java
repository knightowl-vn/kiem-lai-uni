package com.universe.identity.infrastructure.persistence;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
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
import java.util.List;
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
        UserPublicProfileQueryAdapter.class
})
class UserPublicProfileSearchPersistenceIntegrationTest {

    @DynamicPropertySource
    static void configureDynamicProperties(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepositoryAdapter userRepositoryAdapter;

    @Autowired
    private UserPublicProfileQueryAdapter queryAdapter;

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM identity_users");
    }

    @Test
    @DisplayName("5-Tier ranking proof: Exact handle (1) > Prefix handle (2) > Exact name (3) > Prefix name (4) > Contains name (5)")
    void shouldRankSearchCandidatesAccordingTo5TierHierarchy() {
        // Tier 1: Exact handle
        User u1 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new Email("t1@example.com"),
                "$2a$10$hash",
                "Other Name 1",
                "linhdan",
                NOW
        );
        // Tier 2: Prefix handle
        User u2 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new Email("t2@example.com"),
                "$2a$10$hash",
                "Other Name 2",
                "linhdan_pro",
                NOW
        );
        // Tier 3: Exact display name
        User u3 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                new Email("t3@example.com"),
                "$2a$10$hash",
                "Linhdan",
                "handle_three",
                NOW
        );
        // Tier 4: Prefix display name
        User u4 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000004"),
                new Email("t4@example.com"),
                "$2a$10$hash",
                "Linhdan Nguyen",
                "handle_four",
                NOW
        );
        // Tier 5: Contains display name
        User u5 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000005"),
                new Email("t5@example.com"),
                "$2a$10$hash",
                "Be Linhdan Xinh",
                "handle_five",
                NOW
        );

        userRepositoryAdapter.save(u5);
        userRepositoryAdapter.save(u4);
        userRepositoryAdapter.save(u3);
        userRepositoryAdapter.save(u2);
        userRepositoryAdapter.save(u1);

        List<UserPublicProfileDTO> results = queryAdapter.searchPublicUsers("linhdan", 10);

        assertThat(results).hasSize(5);
        assertThat(results.get(0).userId()).isEqualTo(u1.getId()); // Tier 1: exact handle
        assertThat(results.get(1).userId()).isEqualTo(u2.getId()); // Tier 2: prefix handle
        assertThat(results.get(2).userId()).isEqualTo(u3.getId()); // Tier 3: exact display name
        assertThat(results.get(3).userId()).isEqualTo(u4.getId()); // Tier 4: prefix display name
        assertThat(results.get(4).userId()).isEqualTo(u5.getId()); // Tier 5: contains display name
    }

    @Test
    @DisplayName("Ranking occurs before limit truncation: limit=2 returns only Tier 1 and Tier 2")
    void shouldSortInDatabaseBeforeApplyingLimitTruncation() {
        User u1 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new Email("t1@example.com"),
                "$2a$10$hash",
                "Random Alpha",
                "athena",
                NOW
        );
        User u2 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new Email("t2@example.com"),
                "$2a$10$hash",
                "Random Beta",
                "athena_vip",
                NOW
        );
        User u3 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                new Email("t3@example.com"),
                "$2a$10$hash",
                "Athena",
                "other_handle",
                NOW
        );

        // Save in reverse order
        userRepositoryAdapter.save(u3);
        userRepositoryAdapter.save(u2);
        userRepositoryAdapter.save(u1);

        // Request limit 2
        List<UserPublicProfileDTO> results = queryAdapter.searchPublicUsers("athena", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).userId()).isEqualTo(u1.getId()); // Tier 1
        assertThat(results.get(1).userId()).isEqualTo(u2.getId()); // Tier 2
    }

    @Test
    @DisplayName("Handle substring exclusion: searching for 'dan' does not match handle 'linh_dan'")
    void shouldExcludeHandleSubstringMatchesFromSearch() {
        User u1 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new Email("dan1@example.com"),
                "$2a$10$hash",
                "Nguyen Van A",
                "linh_dan", // contains "dan" in the middle/end, but does not start with "dan"
                NOW
        );
        User u2 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new Email("dan2@example.com"),
                "$2a$10$hash",
                "Nguyen Van B",
                "dan_le", // starts with "dan"
                NOW
        );

        userRepositoryAdapter.save(u1);
        userRepositoryAdapter.save(u2);

        List<UserPublicProfileDTO> results = queryAdapter.searchPublicUsers("dan", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).userId()).isEqualTo(u2.getId());
        assertThat(results.get(0).publicHandle()).isEqualTo("dan_le");
    }

    @Test
    @DisplayName("LIKE wildcard escaping: special characters '%' and '_' are treated as literal characters")
    void shouldEscapeLikeWildcardsLiterally() {
        User u1 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new Email("wild1@example.com"),
                "$2a$10$hash",
                "User Vip Member",
                "user_vip",
                NOW
        );
        User u2 = User.createLocal(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                new Email("wild2@example.com"),
                "$2a$10$hash",
                "Uservip Member",
                "uservip",
                NOW
        );

        userRepositoryAdapter.save(u1);
        userRepositoryAdapter.save(u2);

        // 1. Search for "user_" - with underscore escaping, should match only "user_vip", NOT "uservip"
        List<UserPublicProfileDTO> underscoreResults = queryAdapter.searchPublicUsers("user_", 10);
        assertThat(underscoreResults).hasSize(1);
        assertThat(underscoreResults.get(0).userId()).isEqualTo(u1.getId());
        assertThat(underscoreResults.get(0).publicHandle()).isEqualTo("user_vip");

        // 2. Search for "%" or "user%" - with percent escaping, should match 0 users because neither handle contains literal '%'
        List<UserPublicProfileDTO> percentResults = queryAdapter.searchPublicUsers("user%", 10);
        assertThat(percentResults).isEmpty();
    }
}
