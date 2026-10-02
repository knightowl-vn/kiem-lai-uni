package com.universe.community.application;

import com.universe.community.application.cursor.CommunityPostKeysetCursorCodec;
import com.universe.community.application.port.out.CommunityAuthorProfilePort;
import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.community.application.service.CommunityPostFeedAuthorEnricher;
import com.universe.community.application.usecase.GetCommunityAuthorPostsUseCase;
import com.universe.community.application.usecase.GetCommunityPublicProfilePostsUseCase;
import com.universe.community.application.usecase.GetCommunityPublicProfileUseCase;
import com.universe.community.contracts.dto.CommunityAuthorProfileDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.domain.CommunityPost;
import com.universe.community.infrastructure.identity.IdentityCommunityAuthorProfileAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostPersistenceAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostPersistenceMapper;
import com.universe.community.infrastructure.persistence.CommunityPostQueryAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostRevisionPersistenceAdapter;
import com.universe.community.infrastructure.persistence.CommunityPostRevisionPersistenceMapper;
import com.universe.identity.application.query.UserIdentityQueryService;
import com.universe.identity.domain.Email;
import com.universe.identity.domain.User;
import com.universe.identity.infrastructure.persistence.UserPersistenceMapper;
import com.universe.identity.infrastructure.persistence.UserPublicProfileQueryAdapter;
import com.universe.identity.infrastructure.persistence.UserRepositoryAdapter;
import com.universe.test.TestDatabaseSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
        // Real Identity Persistence & Query Service beans
        UserRepositoryAdapter.class,
        UserPersistenceMapper.class,
        UserPublicProfileQueryAdapter.class,
        UserIdentityQueryService.class,

        // Real Community Persistence, Codec & Outbound Adapter beans
        CommunityPostPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceAdapter.class,
        CommunityPostRevisionPersistenceMapper.class,
        CommunityPostQueryAdapter.class,
        CommunityPostKeysetCursorCodec.class,
        IdentityCommunityAuthorProfileAdapter.class,
        CommunityPostFeedAuthorEnricher.class,

        // Real Community Use Cases
        GetCommunityAuthorPostsUseCase.class,
        GetCommunityPublicProfileUseCase.class,
        GetCommunityPublicProfilePostsUseCase.class,

        CommunityProfileCompositionIntegrationTest.TestMetricsConfig.class
})
@DisplayName("Community Public Profile Composition Real Spring & MySQL Integration Tests")
class CommunityProfileCompositionIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestMetricsConfig {
        @Bean
        public CommunityPostEngagementMetricsPort engagementMetricsPort() {
            return (postIds, viewerUserId) -> Map.of();
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepositoryAdapter userRepositoryAdapter;

    @Autowired
    private CommunityPostPersistenceAdapter postPersistenceAdapter;

    @Autowired
    private CommunityAuthorProfilePort authorProfilePort;

    @Autowired
    private GetCommunityPublicProfileUseCase getPublicProfileUseCase;

    @Autowired
    private GetCommunityPublicProfilePostsUseCase getPublicProfilePostsUseCase;

    private static final Instant NOW = Instant.parse("2026-09-30T10:00:00Z");
    private static final UUID AUTHOR_1_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTHOR_2_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID BLOCKED_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @BeforeEach
    void setUp() {
        cleanUpTables();
    }

    @AfterEach
    void tearDown() {
        cleanUpTables();
    }

    private void cleanUpTables() {
        jdbcTemplate.execute("DELETE FROM community_post_revisions");
        jdbcTemplate.execute("DELETE FROM community_posts");
        jdbcTemplate.execute("DELETE FROM identity_users");
    }

    @Test
    @DisplayName("Real Spring & DB: Compose Identity live metadata and Community authored posts feed")
    void shouldComposeProfileMetadataAndAuthoredPostsFeedFromRealDatabaseWiring() {
        // 1. Create and save real Identity user
        User author = User.createLocal(
                AUTHOR_1_ID,
                new Email("tieu_viem@universe.com"),
                "$2a$10$hash123",
                "Tiêu Viêm",
                "tieu_viem",
                NOW
        );
        author.updateBio("Đấu Phá Thương Khung - Viêm Đế");
        author.updateAvatarUrl("/media/assets/00000000-0000-0000-0000-000000000001/content");
        userRepositoryAdapter.save(author);

        // 2. Create and save real authored Community posts
        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post1 = CommunityPost.create(
                UUID.randomUUID(),
                AUTHOR_1_ID,
                "Post 1 - Oldest",
                null,
                baseTime.minus(2, ChronoUnit.HOURS)
        );
        CommunityPost post2 = CommunityPost.create(
                UUID.randomUUID(),
                AUTHOR_1_ID,
                "Post 2 - Newest",
                null,
                baseTime.minus(1, ChronoUnit.HOURS)
        );

        // Another author's post (must not appear in tieu_viem's feed)
        CommunityPost otherPost = CommunityPost.create(
                UUID.randomUUID(),
                AUTHOR_2_ID,
                "Other author post",
                null,
                baseTime
        );

        postPersistenceAdapter.save(post1);
        postPersistenceAdapter.save(post2);
        postPersistenceAdapter.save(otherPost);

        // 3. Execute profile composition use case via real Spring bean chain
        Optional<CommunityAuthorProfileDTO> profileOpt = getPublicProfileUseCase.execute("tieu_viem");

        assertThat(profileOpt).isPresent();
        CommunityAuthorProfileDTO profile = profileOpt.get();
        assertThat(profile.publicHandle()).isEqualTo("tieu_viem");
        assertThat(profile.displayName()).isEqualTo("Tiêu Viêm");
        assertThat(profile.avatarUrl()).isEqualTo("/media/assets/00000000-0000-0000-0000-000000000001/content");
        assertThat(profile.bio()).isEqualTo("Đấu Phá Thương Khung - Viêm Đế");

        // Authored feed must contain only post2 and post1 in DESC order
        assertThat(profile.posts().items()).hasSize(2);
        assertThat(profile.posts().items().get(0).id()).isEqualTo(post2.getId());
        assertThat(profile.posts().items().get(0).caption()).isEqualTo("Post 2 - Newest");
        assertThat(profile.posts().items().get(1).id()).isEqualTo(post1.getId());
        assertThat(profile.posts().items().get(1).caption()).isEqualTo("Post 1 - Oldest");
        assertThat(profile.posts().hasNext()).isFalse();
    }

    @Test
    @DisplayName("Real Spring & DB: Paginate authored posts via GetCommunityPublicProfilePostsUseCase")
    void shouldPaginateAuthoredPostsViaRealDatabaseWiring() {
        User author = User.createLocal(
                AUTHOR_1_ID,
                new Email("tieu_viem@universe.com"),
                "$2a$10$hash123",
                "Tiêu Viêm",
                "tieu_viem",
                NOW
        );
        userRepositoryAdapter.save(author);

        Instant baseTime = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CommunityPost post1 = CommunityPost.create(
                UUID.randomUUID(),
                AUTHOR_1_ID,
                "First",
                null,
                baseTime.minus(2, ChronoUnit.HOURS)
        );
        CommunityPost post2 = CommunityPost.create(
                UUID.randomUUID(),
                AUTHOR_1_ID,
                "Second",
                null,
                baseTime.minus(1, ChronoUnit.HOURS)
        );

        postPersistenceAdapter.save(post1);
        postPersistenceAdapter.save(post2);

        // Page 1 with size=1
        Optional<CommunityNewestFeedResponseDTO> page1Opt =
                getPublicProfilePostsUseCase.execute("tieu_viem", null, 1);

        assertThat(page1Opt).isPresent();
        CommunityNewestFeedResponseDTO page1 = page1Opt.get();
        assertThat(page1.items()).hasSize(1);
        assertThat(page1.items().get(0).id()).isEqualTo(post2.getId());
        assertThat(page1.hasNext()).isTrue();
        assertThat(page1.nextCursor()).isNotNull();

        // Page 2 with cursor from page 1
        Optional<CommunityNewestFeedResponseDTO> page2Opt =
                getPublicProfilePostsUseCase.execute("tieu_viem", page1.nextCursor(), 1);

        assertThat(page2Opt).isPresent();
        CommunityNewestFeedResponseDTO page2 = page2Opt.get();
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).id()).isEqualTo(post1.getId());
        assertThat(page2.hasNext()).isFalse();
    }

    @Test
    @DisplayName("Real Spring & DB: Inactive or blocked Identity user must return Optional.empty()")
    void shouldReturnEmptyWhenUserIsInactiveOrBlockedInIdentityDatabase() {
        User blockedUser = User.createLocal(
                BLOCKED_USER_ID,
                new Email("blocked@universe.com"),
                "$2a$10$hash123",
                "Blocked User",
                "blocked_user",
                NOW
        );
        blockedUser.block();
        userRepositoryAdapter.save(blockedUser);

        Optional<CommunityAuthorProfileDTO> profileOpt = getPublicProfileUseCase.execute("blocked_user");
        assertThat(profileOpt).isEmpty();

        Optional<CommunityNewestFeedResponseDTO> postsOpt =
                getPublicProfilePostsUseCase.execute("blocked_user", null, 10);
        assertThat(postsOpt).isEmpty();
    }

    @Test
    @DisplayName("Real Spring & DB: Nonexistent Identity user must return Optional.empty()")
    void shouldReturnEmptyWhenUserDoesNotExistInIdentityDatabase() {
        Optional<CommunityAuthorProfileDTO> profileOpt = getPublicProfileUseCase.execute("nonexistent_user");
        assertThat(profileOpt).isEmpty();

        Optional<CommunityNewestFeedResponseDTO> postsOpt =
                getPublicProfilePostsUseCase.execute("nonexistent_user", null, 20);
        assertThat(postsOpt).isEmpty();
    }

    @Test
    @DisplayName("Real Spring & DB: Active Identity user with zero posts returns composed profile with empty feed")
    void shouldReturnComposedProfileWithEmptyFeedWhenActiveUserHasZeroPosts() {
        User author = User.createLocal(
                AUTHOR_1_ID,
                new Email("author_noposts@universe.com"),
                "$2a$10$hash123",
                "Author No Posts",
                "author_noposts",
                NOW
        );
        userRepositoryAdapter.save(author);

        Optional<CommunityAuthorProfileDTO> profileOpt = getPublicProfileUseCase.execute("author_noposts");

        assertThat(profileOpt).isPresent();
        CommunityAuthorProfileDTO profile = profileOpt.get();
        assertThat(profile.publicHandle()).isEqualTo("author_noposts");
        assertThat(profile.displayName()).isEqualTo("Author No Posts");
        assertThat(profile.posts().items()).isEmpty();
        assertThat(profile.posts().hasNext()).isFalse();
    }
}
