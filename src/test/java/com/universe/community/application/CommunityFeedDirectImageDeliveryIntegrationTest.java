package com.universe.community.application;

import com.universe.community.application.cursor.CommunityPostKeysetCursorCodec;
import com.universe.community.application.port.out.CommunityPostEngagementMetricsPort;
import com.universe.community.application.service.CommunityPostFeedAuthorEnricher;
import com.universe.community.application.usecase.GetCommunityAuthorPostsUseCase;
import com.universe.community.application.usecase.GetCommunityFeaturedFeedUseCase;
import com.universe.community.application.usecase.GetCommunityNewestFeedUseCase;
import com.universe.community.application.usecase.GetCommunityPostDetailUseCase;
import com.universe.community.contracts.dto.CommunityFeaturedFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityNewestFeedResponseDTO;
import com.universe.community.contracts.dto.CommunityPostFeedItemDTO;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.infrastructure.identity.IdentityCommunityAuthorProfileAdapter;
import com.universe.community.infrastructure.media.CommunityPostImageDeliveryAdapter;
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
import com.universe.media.application.image.ResolveMediaPublicImageDeliveryUrlUseCase;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.MediaPublicImageBatchDeliveryQueryPort;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.media.infrastructure.persistence.MediaPublicImageBatchDeliveryQueryPersistenceAdapter;
import com.universe.test.TestDatabaseSupport;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Import({
        UserRepositoryAdapter.class,
        UserPersistenceMapper.class,
        UserPublicProfileQueryAdapter.class,
        UserIdentityQueryService.class,

        CommunityPostPersistenceAdapter.class,
        CommunityPostPersistenceMapper.class,
        CommunityPostRevisionPersistenceAdapter.class,
        CommunityPostRevisionPersistenceMapper.class,
        CommunityPostQueryAdapter.class,
        CommunityPostKeysetCursorCodec.class,
        IdentityCommunityAuthorProfileAdapter.class,
        CommunityPostImageDeliveryAdapter.class,
        CommunityPostFeedAuthorEnricher.class,
        GetCommunityNewestFeedUseCase.class,
        GetCommunityFeaturedFeedUseCase.class,
        GetCommunityPostDetailUseCase.class,
        GetCommunityAuthorPostsUseCase.class,

        MediaPublicImageBatchDeliveryQueryPersistenceAdapter.class,
        ResolveMediaPublicImageDeliveryUrlUseCase.class,

        CommunityFeedDirectImageDeliveryIntegrationTest.TestConfig.class
})
@DisplayName("CommunityFeedDirectImageDeliveryIntegrationTest — Isolated MySQL Batch Image Delivery")
class CommunityFeedDirectImageDeliveryIntegrationTest {

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        TestDatabaseSupport.configureDynamicProperties(registry);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        public CommunityPostEngagementMetricsPort engagementMetricsPort() {
            return (postIds, viewerUserId) -> Map.of();
        }

        @Bean
        public MediaAssetRepositoryPort mediaAssetRepositoryPort() {
            return mock(MediaAssetRepositoryPort.class);
        }

        @Bean
        public MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort() {
            return mock(MediaAssetVersionRepositoryPort.class);
        }

        @Bean
        public MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort() {
            return mock(MediaImageVariantRepositoryPort.class);
        }

        @Bean
        public MediaContract mediaContract(ResolveMediaPublicImageDeliveryUrlUseCase resolveUseCase) {
            MediaContract contract = mock(MediaContract.class);
            when(contract.resolvePublicImageDeliveryUrls(any(), nullable(Integer.class), anyBoolean()))
                    .thenAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Collection<UUID> ids = inv.getArgument(0);
                        Integer width = inv.getArgument(1);
                        boolean fallback = inv.getArgument(2);
                        return resolveUseCase.executeBatch(ids, width, fallback);
                    });
            when(contract.resolvePublicImageDeliveryUrls(any(), nullable(String.class), anyBoolean()))
                    .thenAnswer(inv -> {
                        @SuppressWarnings("unchecked")
                        Collection<UUID> ids = inv.getArgument(0);
                        String variantKey = inv.getArgument(1);
                        boolean fallback = inv.getArgument(2);
                        return resolveUseCase.executeBatch(ids, variantKey, fallback);
                    });
            return contract;
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepositoryAdapter userRepositoryAdapter;

    @Autowired
    private CommunityPostPersistenceAdapter postPersistenceAdapter;

    @Autowired
    private GetCommunityNewestFeedUseCase newestFeedUseCase;

    @Autowired
    private GetCommunityFeaturedFeedUseCase featuredFeedUseCase;

    @Autowired
    private GetCommunityPostDetailUseCase postDetailUseCase;

    @Autowired
    private GetCommunityAuthorPostsUseCase authorPostsUseCase;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final UUID AUTHOR_ID = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        cleanTables();
        User author = User.createLocal(
                AUTHOR_ID,
                new Email("perf-community-img@example.com"),
                "$2a$10$hash123",
                "Perf Author",
                "perf_author",
                NOW
        );
        userRepositoryAdapter.save(author);
    }

    @AfterEach
    void tearDown() {
        cleanTables();
    }

    private void cleanTables() {
        jdbcTemplate.update("DELETE FROM community_post_revisions");
        jdbcTemplate.update("DELETE FROM community_posts");
        jdbcTemplate.update("DELETE FROM media_image_variants");
        jdbcTemplate.update("DELETE FROM media_asset_versions");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM identity_users WHERE id = ?", AUTHOR_ID.toString());
    }

    private void insertMediaImage(UUID assetId, String status, String visibility, String provider, String publicUrl) {
        Timestamp ts = Timestamp.from(NOW);
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, media_type, visibility, status, current_version_number, created_at, updated_at, persistence_version) "
                        + "VALUES (?, 'IMAGE', ?, ?, 1, ?, ?, 0)",
                assetId.toString(), visibility, status, ts, ts
        );
        jdbcTemplate.update(
                "INSERT INTO media_asset_versions (id, asset_id, version_number, storage_provider_id, storage_key, public_url, content_hash, mime_type, size_bytes, original_filename, created_at) "
                        + "VALUES (?, ?, 1, ?, ?, ?, ?, 'image/jpeg', 2048, 'post.jpg', ?)",
                UUID.randomUUID().toString(),
                assetId.toString(),
                provider,
                "community/" + assetId + ".jpg",
                publicUrl,
                "c".repeat(64),
                ts
        );
    }

    @Test
    @DisplayName("Newest, Featured, Author, and Detail feeds batch-resolve Cloudinary images in O(1) SQL while failing closed to contentUrl for non-eligible assets")
    void resolvesDirectUrlsForEligibleAssetsInBatchAndFailsClosedOtherwise() {
        UUID cloudinaryAsset1 = UUID.randomUUID();
        UUID cloudinaryAsset2 = UUID.randomUUID();
        UUID localAsset = UUID.randomUUID();
        UUID archivedAsset = UUID.randomUUID();

        String directUrl1 = "https://res.cloudinary.com/kiemlai/image/upload/v1/post1.jpg";
        String directUrl2 = "https://res.cloudinary.com/kiemlai/image/upload/v1/post2.webp";

        insertMediaImage(cloudinaryAsset1, "ACTIVE", "PUBLIC", "cloudinary", directUrl1);
        insertMediaImage(cloudinaryAsset2, "ACTIVE", "PUBLIC", "cloudinary", directUrl2);
        insertMediaImage(localAsset, "ACTIVE", "PUBLIC", "local", null);
        insertMediaImage(archivedAsset, "ARCHIVED", "PUBLIC", "cloudinary", "https://res.cloudinary.com/kiemlai/image/upload/v1/archived.jpg");

        UUID post1Id = UUID.randomUUID();
        UUID post2Id = UUID.randomUUID();
        UUID postLocalId = UUID.randomUUID();
        UUID postArchivedId = UUID.randomUUID();
        UUID postTextId = UUID.randomUUID();

        postPersistenceAdapter.save(CommunityPost.rehydrate(
                post1Id, AUTHOR_ID, "Post 1", null, cloudinaryAsset1,
                CommunityPostStatus.PUBLISHED, 0, NOW, NOW, NOW.plus(5, ChronoUnit.SECONDS), null
        ));
        postPersistenceAdapter.save(CommunityPost.rehydrate(
                post2Id, AUTHOR_ID, "Post 2", null, cloudinaryAsset2,
                CommunityPostStatus.PUBLISHED, 0, NOW, NOW, NOW.plus(4, ChronoUnit.SECONDS), null
        ));
        postPersistenceAdapter.save(CommunityPost.rehydrate(
                postLocalId, AUTHOR_ID, "Post Local", null, localAsset,
                CommunityPostStatus.PUBLISHED, 0, NOW, NOW, NOW.plus(3, ChronoUnit.SECONDS), null
        ));
        postPersistenceAdapter.save(CommunityPost.rehydrate(
                postArchivedId, AUTHOR_ID, "Post Archived Img", null, archivedAsset,
                CommunityPostStatus.PUBLISHED, 0, NOW, NOW, NOW.plus(2, ChronoUnit.SECONDS), null
        ));
        postPersistenceAdapter.save(CommunityPost.rehydrate(
                postTextId, AUTHOR_ID, "Text Only", null, null,
                CommunityPostStatus.PUBLISHED, 0, NOW, NOW, NOW.plus(1, ChronoUnit.SECONDS), null
        ));

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        CommunityNewestFeedResponseDTO newest = newestFeedUseCase.execute(null, 20);
        // 1 query for newest posts + 1 query for Identity profiles + 1 batched query for all 4 image assets
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);

        List<CommunityPostFeedItemDTO> items = newest.items();
        assertThat(items).hasSize(5);
        assertThat(items.get(0).imageUrl()).isEqualTo(directUrl1);
        assertThat(items.get(1).imageUrl()).isEqualTo(directUrl2);
        assertThat(items.get(2).imageUrl()).isEqualTo("/media/assets/" + localAsset + "/content");
        assertThat(items.get(3).imageUrl()).isEqualTo("/media/assets/" + archivedAsset + "/content");
        assertThat(items.get(4).imageUrl()).isNull();

        CommunityFeaturedFeedResponseDTO featured = featuredFeedUseCase.execute(0, 20);
        assertThat(featured.items()).extracting(CommunityPostFeedItemDTO::imageUrl)
                .containsExactly(
                        directUrl1,
                        directUrl2,
                        "/media/assets/" + localAsset + "/content",
                        "/media/assets/" + archivedAsset + "/content",
                        null
                );

        CommunityNewestFeedResponseDTO authored = authorPostsUseCase.execute(AUTHOR_ID, null, 20);
        assertThat(authored.items()).hasSize(5);
        assertThat(authored.items().get(0).imageUrl()).isEqualTo(directUrl1);

        Optional<CommunityPostFeedItemDTO> detail = postDetailUseCase.execute(post1Id);
        assertThat(detail).isPresent();
        assertThat(detail.get().imageUrl()).isEqualTo(directUrl1);
    }

    @Test
    @DisplayName("Text-only feed adds 0 Media queries")
    void textOnlyFeedSkipsMediaBatchQuery() {
        postPersistenceAdapter.save(CommunityPost.rehydrate(
                UUID.randomUUID(), AUTHOR_ID, "Pure text 1", null, null,
                CommunityPostStatus.PUBLISHED, 0, NOW, NOW, NOW.plus(2, ChronoUnit.SECONDS), null
        ));
        postPersistenceAdapter.save(CommunityPost.rehydrate(
                UUID.randomUUID(), AUTHOR_ID, "Pure text 2", null, null,
                CommunityPostStatus.PUBLISHED, 0, NOW, NOW, NOW.plus(1, ChronoUnit.SECONDS), null
        ));

        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();

        CommunityNewestFeedResponseDTO newest = newestFeedUseCase.execute(null, 20);

        // 1 query for newest posts + 1 query for Identity profiles, 0 Media queries
        assertThat(newest.items()).hasSize(2);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2);
    }
}
