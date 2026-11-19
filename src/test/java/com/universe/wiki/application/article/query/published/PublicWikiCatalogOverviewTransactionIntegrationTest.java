package com.universe.wiki.application.article.query.published;

import com.universe.wiki.application.appreciation.GetWikiAppreciationSummariesUseCase;
import com.universe.wiki.application.ports.WikiAppreciationQueryPort;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.application.ports.WikiPublicCoverImageDeliveryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.domain.appreciation.WikiAppreciationSummary;
import com.universe.wiki.domain.article.ArticleType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PublicWikiCatalogOverviewTransactionIntegrationTest.TestConfig.class)
@DisplayName("PublicWikiCatalogOverviewTransactionIntegrationTest — Spring Transaction Participation")
class PublicWikiCatalogOverviewTransactionIntegrationTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COVER_ASSET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        private final Connection connection = mock(Connection.class);
        private final DataSource dataSource = mock(DataSource.class);
        private final WikiArticleQueryPort articleQueryPort = mock(WikiArticleQueryPort.class);
        private final WikiAppreciationQueryPort appreciationQueryPort = mock(WikiAppreciationQueryPort.class);
        private final WikiPublicCoverImageDeliveryPort coverDeliveryPort = mock(WikiPublicCoverImageDeliveryPort.class);

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
        public WikiAppreciationQueryPort appreciationQueryPort() {
            return appreciationQueryPort;
        }

        @Bean
        public WikiPublicCoverImageDeliveryPort wikiPublicCoverImageDeliveryPort() {
            return coverDeliveryPort;
        }

        @Bean
        public ListPublishedWikiArticlesUseCase listPublishedArticlesUseCase(WikiArticleQueryPort queryPort) {
            return new ListPublishedWikiArticlesUseCase(queryPort);
        }

        @Bean
        public GetWikiAppreciationSummariesUseCase getWikiAppreciationSummariesUseCase(WikiAppreciationQueryPort queryPort) {
            return new GetWikiAppreciationSummariesUseCase(queryPort);
        }

        @Bean
        public GetPublicWikiCatalogOverviewUseCase getPublicWikiCatalogOverviewUseCase(
                ListPublishedWikiArticlesUseCase listArticles,
                GetWikiAppreciationSummariesUseCase getSummaries,
                WikiPublicCoverImageDeliveryPort coverPort
        ) {
            return new GetPublicWikiCatalogOverviewUseCase(listArticles, getSummaries, coverPort);
        }
    }

    @Autowired
    private GetPublicWikiCatalogOverviewUseCase coordinator;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Connection connection;

    @Autowired
    private WikiArticleQueryPort articleQueryPort;

    @Autowired
    private WikiAppreciationQueryPort appreciationQueryPort;

    @Autowired
    private WikiPublicCoverImageDeliveryPort coverDeliveryPort;

    @BeforeEach
    void resetMocks() throws SQLException {
        reset(dataSource, connection, articleQueryPort, appreciationQueryPort, coverDeliveryPort);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
    }

    @Test
    @DisplayName("Single transaction boundary: Exactly 1 connection acquisition, 1 setAutoCommit(false), 1 commit, 1 close")
    void executesEntireOverviewInSingleTransactionBoundary() throws SQLException {
        PublishedWikiArticleListItemDTO item = new PublishedWikiArticleListItemDTO(
                ARTICLE_ID, "Title", "slug", ArticleType.CHARACTER.name(),
                "Summary", Instant.now(), Instant.now(), COVER_ASSET_ID
        );
        PublishedWikiArticlePageDTO pageDTO = new PublishedWikiArticlePageDTO(
                List.of(item), 0, 20, 1, 1, false, false
        );

        when(articleQueryPort.findPublishedPage(any(), any(), anyInt(), anyInt()))
                .thenReturn(pageDTO);
        when(appreciationQueryPort.findSummariesByWikiArticleIds(any()))
                .thenReturn(Map.of(ARTICLE_ID, new WikiAppreciationSummary(ARTICLE_ID, new BigDecimal("4.8"), 12L)));
        when(coverDeliveryPort.resolvePublicCoverUrls(any(), eq(300), eq(true)))
                .thenReturn(Map.of(COVER_ASSET_ID, "https://res.cloudinary.com/demo/image/upload/w_300/c.webp"));

        GetPublicWikiCatalogOverviewQuery query = GetPublicWikiCatalogOverviewQuery.of("test", ArticleType.CHARACTER, 0, 20);
        PublicWikiCatalogOverviewResult result = coordinator.execute(query);

        assertThat(result).isNotNull();
        assertThat(result.articlePage().items()).hasSize(1);
        assertThat(result.appreciationSummaries()).containsKey(ARTICLE_ID);
        assertThat(result.directCoverUrls()).containsKey(COVER_ASSET_ID);

        // Verification of real Spring TransactionInterceptor proxy participation:
        // Exactly 1 connection acquisition across all 3 read operations:
        verify(dataSource, times(1)).getConnection();
        // Exactly 1 setReadOnly(true) call:
        verify(connection, times(1)).setReadOnly(true);
        // Exactly 1 setAutoCommit(false) call:
        verify(connection, times(1)).setAutoCommit(false);
        // Exactly 1 commit call:
        verify(connection, times(1)).commit();
        // Zero rollbacks on success:
        verify(connection, never()).rollback();
        // Exactly 1 connection close / return to pool:
        verify(connection, times(1)).close();
    }

    @Test
    @DisplayName("Rollback on downstream failure: verifies transaction boundary triggers rollback")
    void triggersRollbackOnException() throws SQLException {
        when(articleQueryPort.findPublishedPage(any(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalStateException("Simulated query failure"));

        GetPublicWikiCatalogOverviewQuery query = GetPublicWikiCatalogOverviewQuery.of("fail", null, 0, 20);

        assertThatThrownBy(() -> coordinator.execute(query))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated query failure");

        // Exactly 1 connection acquisition:
        verify(dataSource, times(1)).getConnection();
        // Never committed:
        verify(connection, never()).commit();
        // Rollback invoked:
        verify(connection, times(1)).rollback();
        // Connection cleaned up:
        verify(connection, times(1)).close();
    }
}
