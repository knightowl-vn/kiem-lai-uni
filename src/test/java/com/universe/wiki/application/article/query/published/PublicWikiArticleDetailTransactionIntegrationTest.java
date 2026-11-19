package com.universe.wiki.application.article.query.published;

import com.universe.identity.application.ports.UserPublicProfileQueryPort;
import com.universe.identity.application.ports.UserRepositoryPort;
import com.universe.identity.application.query.UserIdentityQueryService;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.wiki.application.appreciation.GetWikiAppreciationDetailStateUseCase;
import com.universe.wiki.application.article.query.contributor.GetWikiArticlePublicContributorsUseCase;
import com.universe.wiki.application.article.query.contributor.WikiArticlePublicContributorAggregate;
import com.universe.wiki.application.exceptions.PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.application.ports.WikiAppreciationRepositoryPort;
import com.universe.wiki.application.ports.WikiArticlePublicContributorQueryPort;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import com.universe.wiki.application.ports.WikiSavedArticleRepositoryPort;
import com.universe.wiki.application.saved.IsWikiArticleSavedUseCase;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.domain.appreciation.WikiAppreciationRating;
import com.universe.wiki.domain.appreciation.WikiAppreciationScore;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PublicWikiArticleDetailTransactionIntegrationTest.TestConfig.class)
@DisplayName("PublicWikiArticleDetailTransactionIntegrationTest — Spring Transaction Participation")
class PublicWikiArticleDetailTransactionIntegrationTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID AUTHOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CONTRIBUTOR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID COVER_ASSET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        private final Connection connection = mock(Connection.class);
        private final DataSource dataSource = mock(DataSource.class);
        private final WikiArticleQueryPort articleQueryPort = mock(WikiArticleQueryPort.class);
        private final WikiSavedArticleRepositoryPort savedArticleRepositoryPort = mock(WikiSavedArticleRepositoryPort.class);
        private final WikiPublicCoverImageDeliveryPort coverDeliveryPort = mock(WikiPublicCoverImageDeliveryPort.class);
        private final WikiAppreciationQueryPort appreciationQueryPort = mock(WikiAppreciationQueryPort.class);
        private final WikiAppreciationRepositoryPort appreciationRepositoryPort = mock(WikiAppreciationRepositoryPort.class);
        private final UserRepositoryPort userRepositoryPort = mock(UserRepositoryPort.class);
        private final UserPublicProfileQueryPort userPublicProfileQueryPort = mock(UserPublicProfileQueryPort.class);
        private final WikiArticlePublicContributorQueryPort contributorQueryPort = mock(WikiArticlePublicContributorQueryPort.class);

        @Bean
        public Connection connection() {
            return connection;
        }

        @Bean
        public DataSource dataSource() throws SQLException {
            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            return dataSource;
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public WikiArticleQueryPort articleQueryPort() {
            return articleQueryPort;
        }

        @Bean
        public WikiSavedArticleRepositoryPort savedArticleRepositoryPort() {
            return savedArticleRepositoryPort;
        }

        @Bean
        public WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort() {
            return coverDeliveryPort;
        }

        @Bean
        public WikiAppreciationQueryPort appreciationQueryPort() {
            return appreciationQueryPort;
        }

        @Bean
        public WikiAppreciationRepositoryPort appreciationRepositoryPort() {
            return appreciationRepositoryPort;
        }

        @Bean
        public UserRepositoryPort userRepositoryPort() {
            return userRepositoryPort;
        }

        @Bean
        public UserPublicProfileQueryPort userPublicProfileQueryPort() {
            return userPublicProfileQueryPort;
        }

        @Bean
        public UserIdentityContract userIdentityContract(
                UserRepositoryPort userRepositoryPort,
                UserPublicProfileQueryPort userPublicProfileQueryPort
        ) {
            return new UserIdentityQueryService(userRepositoryPort, userPublicProfileQueryPort);
        }

        @Bean
        public WikiArticlePublicContributorQueryPort contributorQueryPort() {
            return contributorQueryPort;
        }

        @Bean
        public GetPublishedWikiArticleUseCase getPublishedWikiArticleUseCase(WikiArticleQueryPort queryPort) {
            return new GetPublishedWikiArticleUseCase(queryPort);
        }

        @Bean
        public IsWikiArticleSavedUseCase isWikiArticleSavedUseCase(WikiSavedArticleRepositoryPort port) {
            return new IsWikiArticleSavedUseCase(port);
        }

        @Bean
        public GetWikiAppreciationDetailStateUseCase getWikiAppreciationDetailStateUseCase(
                WikiAppreciationQueryPort queryPort,
                WikiAppreciationRepositoryPort repositoryPort
        ) {
            return new GetWikiAppreciationDetailStateUseCase(queryPort, repositoryPort);
        }

        @Bean
        public GetWikiArticlePublicContributorsUseCase getWikiArticlePublicContributorsUseCase(
                WikiArticlePublicContributorQueryPort port,
                UserIdentityContract identityContract
        ) {
            return new GetWikiArticlePublicContributorsUseCase(port, identityContract);
        }

        @Bean
        public GetPublicWikiArticleDetailUseCase getPublicWikiArticleDetailUseCase(
                GetPublishedWikiArticleUseCase getPublishedArticleUseCase,
                IsWikiArticleSavedUseCase isWikiArticleSavedUseCase,
                WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort,
                GetWikiAppreciationDetailStateUseCase getWikiAppreciationDetailStateUseCase,
                UserIdentityContract userIdentityContract,
                GetWikiArticlePublicContributorsUseCase getWikiArticlePublicContributorsUseCase
        ) {
            return new GetPublicWikiArticleDetailUseCase(
                    getPublishedArticleUseCase,
                    isWikiArticleSavedUseCase,
                    wikiPublicCoverImageDeliveryPort,
                    getWikiAppreciationDetailStateUseCase,
                    userIdentityContract,
                    getWikiArticlePublicContributorsUseCase
            );
        }
    }

    @Autowired
    private GetPublicWikiArticleDetailUseCase coordinator;

    @Autowired
    private GetWikiArticlePublicContributorsUseCase contributorsUseCase;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Connection connection;

    @Autowired
    private WikiArticleQueryPort articleQueryPort;

    @Autowired
    private WikiSavedArticleRepositoryPort savedArticleRepositoryPort;

    @Autowired
    private WikiPublicCoverImageDeliveryPort coverDeliveryPort;

    @Autowired
    private WikiAppreciationQueryPort appreciationQueryPort;

    @Autowired
    private WikiAppreciationRepositoryPort appreciationRepositoryPort;

    @Autowired
    private UserIdentityContract userIdentityContract;

    @Autowired
    private UserPublicProfileQueryPort userPublicProfileQueryPort;

    @Autowired
    private WikiArticlePublicContributorQueryPort contributorQueryPort;

    @BeforeEach
    void resetMocks() throws SQLException {
        reset(
                dataSource,
                connection,
                articleQueryPort,
                savedArticleRepositoryPort,
                coverDeliveryPort,
                appreciationQueryPort,
                appreciationRepositoryPort,
                userPublicProfileQueryPort,
                contributorQueryPort
        );
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
    }

    @Test
    @DisplayName("Guest detail read participates in a single Spring AOP read-only transaction (1 conn, 1 commit, 1 close)")
    void executesGuestDetailReadInSingleTransactionBoundary() throws SQLException {
        assertThat(AopUtils.isAopProxy(coordinator)).isTrue();
        assertThat(AopUtils.isAopProxy(userIdentityContract)).isTrue();
        assertThat(AopUtils.isAopProxy(contributorsUseCase)).isTrue();

        PublishedWikiArticleDTO article = createPublishedArticle(ArticleType.CHARACTER, AUTHOR_ID);
        when(articleQueryPort.findPublishedByArticleTypeAndSlug(ArticleType.CHARACTER, new Slug("tran-binh-an")))
                .thenReturn(Optional.of(article));
        when(coverDeliveryPort.resolvePublicCoverUrl(COVER_ASSET_ID, 300, true))
                .thenReturn(Optional.of("https://res.cloudinary.com/demo/image/upload/w_300/tba.webp"));
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID))
                .thenReturn(new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.8"), 15L));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID)))
                .thenReturn(Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Tác giả", null, "tac_gia")));
        when(contributorQueryPort.findActiveContributorsByArticleId(ARTICLE_ID))
                .thenReturn(List.of(new WikiArticlePublicContributorAggregate(CONTRIBUTOR_ID, 2L, NOW)));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(CONTRIBUTOR_ID)))
                .thenReturn(Map.of(CONTRIBUTOR_ID, new UserPublicProfileDTO(CONTRIBUTOR_ID, "Độc giả", null, "doc_gia")));

        PublicWikiArticleDetailResult result = coordinator.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.CHARACTER, "tran-binh-an", null)
        );

        assertThat(result.article()).isEqualTo(article);
        assertThat(result.saved()).isFalse();
        assertThat(result.directCoverUrl()).isEqualTo("https://res.cloudinary.com/demo/image/upload/w_300/tba.webp");
        assertThat(result.appreciationEligible()).isTrue();
        assertThat(result.appreciationState().count()).isEqualTo(15L);
        assertThat(result.attributionLine()).isEqualTo("Đăng bởi Tác giả");
        assertThat(result.publicContributorsResult().contributors()).hasSize(1);

        verify(savedArticleRepositoryPort, never()).existsByUserIdAndArticleId(any(), any());
        verify(dataSource, times(1)).getConnection();
        verify(connection, times(1)).setReadOnly(true);
        verify(connection, times(1)).setAutoCommit(false);
        verify(connection, times(1)).commit();
        verify(connection, never()).rollback();
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("Authenticated detail read consolidates saved state and viewer appreciation into 1 single transaction")
    void executesAuthenticatedDetailReadInSingleTransactionBoundary() throws SQLException {
        PublishedWikiArticleDTO article = createPublishedArticle(ArticleType.FACTION, AUTHOR_ID);
        when(articleQueryPort.findPublishedByArticleTypeAndSlug(ArticleType.FACTION, new Slug("lac-phach-son")))
                .thenReturn(Optional.of(article));
        when(savedArticleRepositoryPort.existsByUserIdAndArticleId(VIEWER_ID, ARTICLE_ID))
                .thenReturn(true);
        when(coverDeliveryPort.resolvePublicCoverUrl(COVER_ASSET_ID, 300, true))
                .thenReturn(Optional.empty());
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID))
                .thenReturn(new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.9"), 30L));
        when(appreciationRepositoryPort.findByWikiArticleIdAndUserId(ARTICLE_ID, VIEWER_ID))
                .thenReturn(Optional.of(WikiAppreciationRating.create(
                        UUID.randomUUID(),
                        ARTICLE_ID,
                        VIEWER_ID,
                        WikiAppreciationScore.fromHalfStarUnits(10),
                        NOW
                )));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(any()))
                .thenReturn(Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "Tác giả", null, "tac_gia")));
        when(contributorQueryPort.findActiveContributorsByArticleId(ARTICLE_ID))
                .thenReturn(List.of());

        PublicWikiArticleDetailResult result = coordinator.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.FACTION, "lac-phach-son", VIEWER_ID)
        );

        assertThat(result.saved()).isTrue();
        assertThat(result.appreciationEligible()).isTrue();
        assertThat(result.appreciationState().viewerValue()).isEqualByComparingTo("5.0");

        verify(savedArticleRepositoryPort, times(1)).existsByUserIdAndArticleId(VIEWER_ID, ARTICLE_ID);
        verify(dataSource, times(1)).getConnection();
        verify(connection, times(1)).setReadOnly(true);
        verify(connection, times(1)).setAutoCommit(false);
        verify(connection, times(1)).commit();
        verify(connection, never()).rollback();
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("Missing published article triggers transaction rollback and connection cleanup")
    void triggersRollbackAndResourceCleanupWhenArticleNotFound() throws SQLException {
        when(articleQueryPort.findPublishedByArticleTypeAndSlug(ArticleType.CHARACTER, new Slug("missing-article")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> coordinator.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.CHARACTER, "missing-article", VIEWER_ID)
        )).isInstanceOf(PublishedWikiArticleNotFoundException.class);

        verify(savedArticleRepositoryPort, never()).existsByUserIdAndArticleId(any(), any());
        verify(dataSource, times(1)).getConnection();
        verify(connection, never()).commit();
        verify(connection, times(1)).rollback();
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("Missing or blank author and contributor profiles commit cleanly in 1 single transaction")
    void executesDetailReadGracefullyWhenProfilesAreMissingOrBlank() throws SQLException {
        PublishedWikiArticleDTO article = createPublishedArticle(ArticleType.CHARACTER, AUTHOR_ID);
        when(articleQueryPort.findPublishedByArticleTypeAndSlug(ArticleType.CHARACTER, new Slug("tran-binh-an")))
                .thenReturn(Optional.of(article));
        when(coverDeliveryPort.resolvePublicCoverUrl(COVER_ASSET_ID, 300, true))
                .thenReturn(Optional.empty());
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID))
                .thenReturn(new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.8"), 15L));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID)))
                .thenReturn(Map.of(AUTHOR_ID, new UserPublicProfileDTO(AUTHOR_ID, "   ", null, "blank_author")));
        when(contributorQueryPort.findActiveContributorsByArticleId(ARTICLE_ID))
                .thenReturn(List.of(new WikiArticlePublicContributorAggregate(CONTRIBUTOR_ID, 2L, NOW)));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(CONTRIBUTOR_ID)))
                .thenReturn(Map.of());

        PublicWikiArticleDetailResult result = coordinator.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.CHARACTER, "tran-binh-an", null)
        );

        assertThat(result.article()).isEqualTo(article);
        assertThat(result.attributionLine()).isNull();
        assertThat(result.publicContributorsResult().contributors()).isEmpty();

        verify(savedArticleRepositoryPort, never()).existsByUserIdAndArticleId(any(), any());
        verify(dataSource, times(1)).getConnection();
        verify(connection, times(1)).setReadOnly(true);
        verify(connection, times(1)).setAutoCommit(false);
        verify(connection, times(1)).commit();
        verify(connection, never()).rollback();
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("P2-01 Closure: Proxied attribution Identity failure rolls back cleanly without UnexpectedRollbackException")
    void attributionProxiedIdentityFailureRollsBackCleanlyWithoutUnexpectedRollbackException() throws SQLException {
        PublishedWikiArticleDTO article = createPublishedArticle(ArticleType.CHARACTER, AUTHOR_ID);
        when(articleQueryPort.findPublishedByArticleTypeAndSlug(ArticleType.CHARACTER, new Slug("tran-binh-an")))
                .thenReturn(Optional.of(article));
        when(coverDeliveryPort.resolvePublicCoverUrl(COVER_ASSET_ID, 300, true))
                .thenReturn(Optional.empty());
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID))
                .thenReturn(new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.8"), 15L));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(AUTHOR_ID)))
                .thenThrow(new IllegalStateException("Simulated identity failure during attribution"));

        assertThatThrownBy(() -> coordinator.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.CHARACTER, "tran-binh-an", null)
        ))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class)
                .hasMessageContaining("Simulated identity failure during attribution");

        verify(contributorQueryPort, never()).findActiveContributorsByArticleId(any());
        verify(savedArticleRepositoryPort, never()).existsByUserIdAndArticleId(any(), any());
        verify(dataSource, times(1)).getConnection();
        verify(connection, never()).commit();
        verify(connection, times(1)).rollback();
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("P2-02 Closure (Coordinator): Proxied contributor Identity failure rolls back cleanly without UnexpectedRollbackException")
    void contributorProxiedIdentityFailureInsideCoordinatorRollsBackCleanlyWithoutUnexpectedRollbackException() throws SQLException {
        PublishedWikiArticleDTO article = createPublishedArticle(ArticleType.CHARACTER, null);
        when(articleQueryPort.findPublishedByArticleTypeAndSlug(ArticleType.CHARACTER, new Slug("tran-binh-an")))
                .thenReturn(Optional.of(article));
        when(coverDeliveryPort.resolvePublicCoverUrl(COVER_ASSET_ID, 300, true))
                .thenReturn(Optional.empty());
        when(appreciationQueryPort.findSummaryByWikiArticleId(ARTICLE_ID))
                .thenReturn(new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.8"), 15L));
        when(contributorQueryPort.findActiveContributorsByArticleId(ARTICLE_ID))
                .thenReturn(List.of(new WikiArticlePublicContributorAggregate(CONTRIBUTOR_ID, 2L, NOW)));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(CONTRIBUTOR_ID)))
                .thenThrow(new IllegalStateException("Simulated identity failure during contributors"));

        assertThatThrownBy(() -> coordinator.execute(
                GetPublicWikiArticleDetailQuery.of(ArticleType.CHARACTER, "tran-binh-an", null)
        ))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class)
                .hasMessageContaining("Simulated identity failure during contributors");

        verify(savedArticleRepositoryPort, never()).existsByUserIdAndArticleId(any(), any());
        verify(dataSource, times(1)).getConnection();
        verify(connection, never()).commit();
        verify(connection, times(1)).rollback();
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("P2-02 Closure (Standalone): Proxied GetWikiArticlePublicContributorsUseCase rolls back cleanly without UnexpectedRollbackException")
    void standaloneContributorsProxiedIdentityFailureRollsBackCleanlyWithoutUnexpectedRollbackException() throws SQLException {
        when(contributorQueryPort.findActiveContributorsByArticleId(ARTICLE_ID))
                .thenReturn(List.of(new WikiArticlePublicContributorAggregate(CONTRIBUTOR_ID, 2L, NOW)));
        when(userPublicProfileQueryPort.findPublicProfilesByIds(Set.of(CONTRIBUTOR_ID)))
                .thenThrow(new IllegalStateException("Simulated standalone contributor identity failure"));

        assertThatThrownBy(() -> contributorsUseCase.execute(ARTICLE_ID))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class)
                .hasMessageContaining("Simulated standalone contributor identity failure");

        verify(savedArticleRepositoryPort, never()).existsByUserIdAndArticleId(any(), any());
        verify(dataSource, times(1)).getConnection();
        verify(connection, never()).commit();
        verify(connection, times(1)).rollback();
        verify(connection, times(1)).close();
    }

    private PublishedWikiArticleDTO createPublishedArticle(ArticleType type, UUID createdBy) {
        return new PublishedWikiArticleDTO(
                ARTICLE_ID,
                "Tiêu đề",
                type == ArticleType.CHARACTER ? "tran-binh-an" : "lac-phach-son",
                type.name(),
                "Tóm tắt",
                "## Nội dung",
                NOW,
                NOW,
                COVER_ASSET_ID,
                50,
                50,
                1L,
                createdBy,
                null
        );
    }
}
