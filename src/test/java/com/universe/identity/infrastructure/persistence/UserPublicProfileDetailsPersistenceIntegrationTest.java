package com.universe.identity.infrastructure.persistence;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDetailsDTO;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
@DisplayName("UserPublicProfileDetails Persistence Integration Tests")
class UserPublicProfileDetailsPersistenceIntegrationTest {

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

    private static final UUID USER_1_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_2_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_3_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM identity_users");
    }

    @Test
    @DisplayName("Real DB: Find active user with bio and avatar by canonical handle")
    void shouldFindActiveUserWithBioAndAvatar() {
        User user = User.createLocal(
                USER_1_ID,
                new Email("alice@example.com"),
                "$2a$10$hash",
                "Alice Wonderland",
                "alice",
                NOW
        );
        user.updateBio("Yêu thích tiểu thuyết Kiếm Lai.");
        user.updateAvatarUrl("/media/assets/00000000-0000-0000-0000-000000000001/content");

        userRepositoryAdapter.save(user);

        Optional<UserPublicProfileDetailsDTO> detailsOpt = queryAdapter.findPublicProfileDetailsByHandle("alice");

        assertThat(detailsOpt).isPresent();
        UserPublicProfileDetailsDTO details = detailsOpt.get();
        assertThat(details.userId()).isEqualTo(USER_1_ID);
        assertThat(details.displayName()).isEqualTo("Alice Wonderland");
        assertThat(details.publicHandle()).isEqualTo("alice");
        assertThat(details.avatarUrl()).isEqualTo("/media/assets/00000000-0000-0000-0000-000000000001/content");
        assertThat(details.bio()).isEqualTo("Yêu thích tiểu thuyết Kiếm Lai.");
    }

    @Test
    @DisplayName("Real DB: Find active user with null bio and null avatar")
    void shouldFindActiveUserWithNullBioAndNullAvatar() {
        User user = User.createLocal(
                USER_2_ID,
                new Email("bob@example.com"),
                "$2a$10$hash",
                "Bob Builder",
                "bob_builder",
                NOW
        );

        userRepositoryAdapter.save(user);

        Optional<UserPublicProfileDetailsDTO> detailsOpt = queryAdapter.findPublicProfileDetailsByHandle("bob_builder");

        assertThat(detailsOpt).isPresent();
        UserPublicProfileDetailsDTO details = detailsOpt.get();
        assertThat(details.userId()).isEqualTo(USER_2_ID);
        assertThat(details.displayName()).isEqualTo("Bob Builder");
        assertThat(details.publicHandle()).isEqualTo("bob_builder");
        assertThat(details.avatarUrl()).isNull();
        assertThat(details.bio()).isNull();
    }

    @Test
    @DisplayName("Real DB: Case-insensitivity and @ prefix stripping in handle query")
    void shouldNormalizeCaseAndStripAtPrefix() {
        User user = User.createLocal(
                USER_1_ID,
                new Email("charlie@example.com"),
                "$2a$10$hash",
                "Charlie Chap",
                "charlie_vip",
                NOW
        );
        user.updateBio("Diễn viên kịch câm.");
        userRepositoryAdapter.save(user);

        // Mixed case without @
        Optional<UserPublicProfileDetailsDTO> opt1 = queryAdapter.findPublicProfileDetailsByHandle("Charlie_VIP");
        assertThat(opt1).isPresent();
        assertThat(opt1.get().userId()).isEqualTo(USER_1_ID);

        // Mixed case with @
        Optional<UserPublicProfileDetailsDTO> opt2 = queryAdapter.findPublicProfileDetailsByHandle("@CHARLIE_VIP");
        assertThat(opt2).isPresent();
        assertThat(opt2.get().userId()).isEqualTo(USER_1_ID);

        // Multiple @
        Optional<UserPublicProfileDetailsDTO> opt3 = queryAdapter.findPublicProfileDetailsByHandle("@@charlie_vip");
        assertThat(opt3).isPresent();
        assertThat(opt3.get().userId()).isEqualTo(USER_1_ID);
    }

    @Test
    @DisplayName("Real DB: Non-ACTIVE users (BLOCKED/BANNED) must return Optional.empty()")
    void shouldReturnEmptyForNonActiveUsers() {
        User user = User.createLocal(
                USER_3_ID,
                new Email("blocked@example.com"),
                "$2a$10$hash",
                "Blocked User",
                "blocked_user",
                NOW
        );
        user.updateBio("Tài khoản bị khóa.");
        user.block();

        userRepositoryAdapter.save(user);

        Optional<UserPublicProfileDetailsDTO> detailsOpt = queryAdapter.findPublicProfileDetailsByHandle("blocked_user");
        assertThat(detailsOpt).isEmpty();
    }

    @Test
    @DisplayName("Real DB: Nonexistent handle must return Optional.empty()")
    void shouldReturnEmptyForNonexistentHandle() {
        Optional<UserPublicProfileDetailsDTO> detailsOpt = queryAdapter.findPublicProfileDetailsByHandle("nonexistent_user");
        assertThat(detailsOpt).isEmpty();
    }

    @Test
    @DisplayName("Regression Proof: Existing summary findPublicProfileByHandle, findPublicProfilesByIds, searchPublicUsers unchanged")
    void shouldPreserveExistingSummaryQueries() {
        User user = User.createLocal(
                USER_1_ID,
                new Email("david@example.com"),
                "$2a$10$hash",
                "David Copperfield",
                "david_c",
                NOW
        );
        user.updateBio("Ảo thuật gia.");
        user.updateAvatarUrl("https://img.example.com/david.png");
        userRepositoryAdapter.save(user);

        // 1. findPublicProfileByHandle returns summary (4 fields)
        Optional<UserPublicProfileDTO> summaryOpt = queryAdapter.findPublicProfileByHandle("david_c");
        assertThat(summaryOpt).isPresent();
        assertThat(summaryOpt.get().userId()).isEqualTo(USER_1_ID);
        assertThat(summaryOpt.get().displayName()).isEqualTo("David Copperfield");
        assertThat(summaryOpt.get().publicHandle()).isEqualTo("david_c");
        assertThat(summaryOpt.get().avatarUrl()).isEqualTo("https://img.example.com/david.png");

        // 2. findPublicProfilesByIds
        Map<UUID, UserPublicProfileDTO> batchMap = queryAdapter.findPublicProfilesByIds(Set.of(USER_1_ID));
        assertThat(batchMap).containsEntry(USER_1_ID, summaryOpt.get());

        // 3. searchPublicUsers
        List<UserPublicProfileDTO> searchList = queryAdapter.searchPublicUsers("david", 10);
        assertThat(searchList).hasSize(1);
        assertThat(searchList.get(0)).isEqualTo(summaryOpt.get());
    }

    @Test
    @DisplayName("Real DB Bulk Query: findPublicProfilesByIds includes ACTIVE users A and B, excludes BLOCKED user C, and returns pure public DTOs")
    void shouldBulkFindActivePublicProfilesExcludingNonActiveAndPreservingPublicProjection() {
        // Active User A
        User userA = User.createLocal(
                USER_1_ID,
                new Email("user_a@example.com"),
                "$2a$10$hashA",
                "Alice Active",
                "alice_act",
                NOW
        );
        userA.updateAvatarUrl("https://img.example.com/alice.png");
        userRepositoryAdapter.save(userA);

        // Active User B
        User userB = User.createLocal(
                USER_2_ID,
                new Email("user_b@example.com"),
                "$2a$10$hashB",
                "Bob Active",
                "bob_act",
                NOW
        );
        userRepositoryAdapter.save(userB);

        // Blocked User C (non-ACTIVE)
        User userC = User.createLocal(
                USER_3_ID,
                new Email("user_c@example.com"),
                "$2a$10$hashC",
                "Charlie Blocked",
                "charlie_blk",
                NOW
        );
        userC.block();
        userRepositoryAdapter.save(userC);

        // Bulk lookup {A, B, C}
        Map<UUID, UserPublicProfileDTO> result = queryAdapter.findPublicProfilesByIds(Set.of(USER_1_ID, USER_2_ID, USER_3_ID));

        // Result includes A and B, strictly excludes C
        assertThat(result).hasSize(2);
        assertThat(result).containsKey(USER_1_ID);
        assertThat(result).containsKey(USER_2_ID);
        assertThat(result).doesNotContainKey(USER_3_ID);

        // Verify User A public projection
        UserPublicProfileDTO dtoA = result.get(USER_1_ID);
        assertThat(dtoA.userId()).isEqualTo(USER_1_ID);
        assertThat(dtoA.displayName()).isEqualTo("Alice Active");
        assertThat(dtoA.publicHandle()).isEqualTo("alice_act");
        assertThat(dtoA.avatarUrl()).isEqualTo("https://img.example.com/alice.png");

        // Verify User B public projection (null avatar)
        UserPublicProfileDTO dtoB = result.get(USER_2_ID);
        assertThat(dtoB.userId()).isEqualTo(USER_2_ID);
        assertThat(dtoB.displayName()).isEqualTo("Bob Active");
        assertThat(dtoB.publicHandle()).isEqualTo("bob_act");
        assertThat(dtoB.avatarUrl()).isNull();
    }
}
