package com.universe.wiki.infrastructure.persistence.article;

import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiArticlePersistenceAdapterTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-06T06:00:00Z"
            );

    @Mock
    private SpringDataWikiArticleJpaRepository
            repository;

    private WikiArticlePersistenceAdapter
            persistenceAdapter;

    @BeforeEach
    void setUp() {
        persistenceAdapter =
                new WikiArticlePersistenceAdapter(
                        repository
                );
    }

    @Test
    @DisplayName(
            "Kiểm tra slug tồn tại theo loại bài"
    )
    void shouldCheckExistenceByTypeAndSlug() {
        Slug slug =
                new Slug(
                        "tran-binh-an"
                );

        when(
                repository
                        .existsByArticleTypeAndSlug(
                                "CHARACTER",
                                "tran-binh-an"
                        )
        ).thenReturn(true);

        boolean result =
                persistenceAdapter
                        .existsByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                slug
                        );

        assertThat(result)
                .isTrue();

        verify(repository)
                .existsByArticleTypeAndSlug(
                        "CHARACTER",
                        "tran-binh-an"
                );
    }

    @Test
    @DisplayName(
            "Lưu WikiArticle mới thành JPA entity"
    )
    void shouldSaveNewArticleAsJpaEntity() {
        WikiArticle article =
                WikiArticle.createDraft(
                        ARTICLE_ID,
                        "Trần Bình An",
                        new Slug(
                                "tran-binh-an"
                        ),
                        ArticleType.CHARACTER,
                        ADMIN_ID,
                        NOW
                );

        when(
                repository.findById(
                        ARTICLE_ID.toString()
                )
        ).thenReturn(
                Optional.empty()
        );

        ArgumentCaptor<WikiArticleJpaEntity>
                entityCaptor =
                ArgumentCaptor.forClass(
                        WikiArticleJpaEntity.class
                );

        persistenceAdapter.save(
                article
        );

        verify(repository)
                .save(
                        entityCaptor.capture()
                );

        WikiArticleJpaEntity entity =
                entityCaptor.getValue();

        assertThat(entity.getId())
                .isEqualTo(
                        ARTICLE_ID.toString()
                );

        assertThat(entity.getTitle())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(entity.getSlug())
                .isEqualTo(
                        "tran-binh-an"
                );

        assertThat(entity.getArticleType())
                .isEqualTo(
                        "CHARACTER"
                );

        assertThat(entity.getStatus())
                .isEqualTo(
                        "DRAFT"
                );

        assertThat(entity.getCreatedBy())
                .isEqualTo(
                        ADMIN_ID.toString()
                );

        assertThat(entity.getAggregateVersion())
                .isEqualTo(1L);

        assertThat(entity.getCreatedAt())
                .isEqualTo(NOW);

        assertThat(entity.getUpdatedAt())
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName(
            "Đọc JPA entity và khôi phục WikiArticle"
    )
    void shouldRestoreDomainArticleFromJpaEntity() {
        WikiArticleJpaEntity entity =
                createDraftEntity();

        when(
                repository.findById(
                        ARTICLE_ID.toString()
                )
        ).thenReturn(
                Optional.of(entity)
        );

        Optional<WikiArticle> result =
                persistenceAdapter.findById(
                        ARTICLE_ID
                );

        assertThat(result)
                .isPresent();

        WikiArticle article =
                result.orElseThrow();

        assertThat(article.getId())
                .isEqualTo(ARTICLE_ID);

        assertThat(article.getTitle())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(article.getSlug().value())
                .isEqualTo(
                        "tran-binh-an"
                );

        assertThat(article.getArticleType())
                .isEqualTo(
                        ArticleType.CHARACTER
                );

        assertThat(article.getStatus())
                .isEqualTo(
                        ArticleStatus.DRAFT
                );

        assertThat(article.getCreatedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(article.getAggregateVersion())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "Từ chối lưu WikiArticle null"
    )
    void shouldRejectNullArticle() {
        assertThatThrownBy(() ->
                persistenceAdapter.save(null)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Wiki article không được để trống."
                );

        verify(
                repository,
                never()
        ).save(
                org.mockito.ArgumentMatchers.any()
        );
    }

    private WikiArticleJpaEntity
            createDraftEntity() {

        WikiArticleJpaEntity entity =
                new WikiArticleJpaEntity();

        entity.setId(
                ARTICLE_ID.toString()
        );

        entity.setTitle(
                "Trần Bình An"
        );

        entity.setSlug(
                "tran-binh-an"
        );

        entity.setArticleType(
                "CHARACTER"
        );

        entity.setSummary("");

        entity.setContent("");

        entity.setStatus(
                "DRAFT"
        );

        entity.setCreatedBy(
                ADMIN_ID.toString()
        );

        entity.setUpdatedBy(
                ADMIN_ID.toString()
        );

        entity.setAggregateVersion(1L);

        entity.setCreatedAt(NOW);

        entity.setUpdatedAt(NOW);

        return entity;
    }
}