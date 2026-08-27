package com.universe.wiki.infrastructure.persistence.article;

import com.universe.wiki.contracts.dto.WikiArticleDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.universe.wiki.contracts.dto.WikiArticlePageDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticleDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import org.mockito.ArgumentCaptor;


import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiArticleQueryAdapterTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID CREATOR_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID EDITOR_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID PUBLISHER_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-06T07:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-06T08:00:00Z"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-08-06T09:00:00Z"
            );

    @Mock
    private SpringDataWikiArticleJpaRepository
            repository;

    private WikiArticleQueryAdapter
            queryAdapter;

    @BeforeEach
    void setUp() {
        queryAdapter =
                new WikiArticleQueryAdapter(
                        repository
                );
    }

    @Test
    @DisplayName(
            "Đọc JPA entity và ánh xạ thành WikiArticleDTO"
    )
    void shouldMapJpaEntityToWikiArticleDTO() {
        WikiArticleJpaEntity entity =
                createPublishedEntity();

        when(
                repository.findById(
                        ARTICLE_ID.toString()
                )
        ).thenReturn(
                Optional.of(entity)
        );

        Optional<WikiArticleDTO> result =
                queryAdapter.findDetailById(
                        ARTICLE_ID
                );

        assertThat(result)
                .isPresent();

        WikiArticleDTO article =
                result.orElseThrow();

        assertThat(article.id())
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(article.title())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(article.slug())
                .isEqualTo(
                        "tran-binh-an"
                );

        assertThat(article.articleType())
                .isEqualTo(
                        "CHARACTER"
                );

        assertThat(article.summary())
                .isEqualTo(
                        "Nhân vật chính của Kiếm Lai."
                );

        assertThat(article.content())
                .isEqualTo(
                        "Nội dung chi tiết về Trần Bình An."
                );

        assertThat(article.status())
                .isEqualTo(
                        "PUBLISHED"
                );

        assertThat(article.createdBy())
                .isEqualTo(
                        CREATOR_ID
                );

        assertThat(article.updatedBy())
                .isEqualTo(
                        EDITOR_ID
                );

        assertThat(article.publishedBy())
                .isEqualTo(
                        PUBLISHER_ID
                );

        assertThat(article.archivedBy())
                .isNull();

        assertThat(article.createdAt())
                .isEqualTo(
                        CREATED_AT
                );

        assertThat(article.updatedAt())
                .isEqualTo(
                        UPDATED_AT
                );

        assertThat(article.publishedAt())
                .isEqualTo(
                        PUBLISHED_AT
                );

        assertThat(article.archivedAt())
                .isNull();

        assertThat(article.aggregateVersion())
                .isEqualTo(3L);
        
        

        verify(repository)
                .findById(
                        ARTICLE_ID.toString()
                );
    }

    @Test
    @DisplayName(
            "Trả Optional rỗng khi không tìm thấy bài viết"
    )
    void shouldReturnEmptyWhenArticleDoesNotExist() {
        when(
                repository.findById(
                        ARTICLE_ID.toString()
                )
        ).thenReturn(
                Optional.empty()
        );

        Optional<WikiArticleDTO> result =
                queryAdapter.findDetailById(
                        ARTICLE_ID
                );

        assertThat(result)
                .isEmpty();

        verify(repository)
                .findById(
                        ARTICLE_ID.toString()
                );
    }

    @Test
    @DisplayName(
            "Trả Optional rỗng khi article ID là null"
    )
    void shouldReturnEmptyWhenArticleIdIsNull() {
        Optional<WikiArticleDTO> result =
                queryAdapter.findDetailById(
                        null
                );

        assertThat(result)
                .isEmpty();

        verify(
                repository,
                never()
        ).findById(
                anyString()
        );
    }

    private WikiArticleJpaEntity
            createPublishedEntity() {

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

        entity.setSummary(
                "Nhân vật chính của Kiếm Lai."
        );

        entity.setContent(
                "Nội dung chi tiết về Trần Bình An."
        );

        entity.setStatus(
                "PUBLISHED"
        );

        entity.setCreatedBy(
                CREATOR_ID.toString()
        );

        entity.setUpdatedBy(
                EDITOR_ID.toString()
        );

        entity.setPublishedBy(
                PUBLISHER_ID.toString()
        );

        entity.setArchivedBy(null);

        entity.setAggregateVersion(3L);
        
        entity.setContentVersion(
                1L
        );

        entity.setCreatedAt(
                CREATED_AT
        );

        entity.setUpdatedAt(
                UPDATED_AT
        );

        entity.setPublishedAt(
                PUBLISHED_AT
        );

        entity.setArchivedAt(null);

        return entity;
    }
    
    @Test
    @DisplayName(
            "Lấy danh sách phân trang và ánh xạ thành list item DTO"
    )
    void shouldFindPageWithFilters() {
        WikiArticleJpaEntity entity =
                createPublishedEntity();

        Pageable expectedPageable =
                PageRequest.of(
                        0,
                        20,
                        org.springframework.data.domain.Sort
                                .by(
                                        org.springframework.data.domain.Sort
                                                .Direction.DESC,
                                        "updatedAt"
                                )
                                .and(
                                        org.springframework.data.domain.Sort
                                                .by(
                                                        org.springframework
                                                                .data
                                                                .domain
                                                                .Sort
                                                                .Direction
                                                                .DESC,
                                                        "createdAt"
                                                )
                                )
                );

        PageImpl<WikiArticleJpaEntity> entityPage =
                new PageImpl<>(
                        List.of(entity),
                        expectedPageable,
                        1L
                );

        when(
                repository.findPage(
                        "Trần Bình",
                        "CHARACTER",
                        "PUBLISHED",
                        expectedPageable
                )
        ).thenReturn(
                entityPage
        );

        WikiArticlePageDTO result =
                queryAdapter.findPage(
                        "Trần Bình",
                        ArticleType.CHARACTER,
                        ArticleStatus.PUBLISHED,
                        0,
                        20
                );

        assertThat(result.items())
                .hasSize(1);

        assertThat(result.items().get(0).id())
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(result.items().get(0).title())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(result.items().get(0).slug())
                .isEqualTo(
                        "tran-binh-an"
                );

        assertThat(result.items().get(0).articleType())
                .isEqualTo(
                        "CHARACTER"
                );

        assertThat(result.items().get(0).status())
                .isEqualTo(
                        "PUBLISHED"
                );

        assertThat(result.items().get(0).contentVersion())
                .isEqualTo(1L);

        assertThat(result.page())
                .isZero();

        assertThat(result.size())
                .isEqualTo(20);

        assertThat(result.totalElements())
                .isEqualTo(1L);

        assertThat(result.totalPages())
                .isEqualTo(1);

        assertThat(result.first())
                .isTrue();

        assertThat(result.last())
                .isTrue();

        verify(repository)
                .findPage(
                        "Trần Bình",
                        "CHARACTER",
                        "PUBLISHED",
                        expectedPageable
                );
    }
    
    @Test
    @DisplayName(
            "Truyền null xuống repository khi không có bộ lọc"
    )
    void shouldFindPageWithoutFilters() {
        Pageable pageable =
                PageRequest.of(
                        0,
                        20,
                        org.springframework.data.domain.Sort
                                .by(
                                        org.springframework.data.domain.Sort
                                                .Direction.DESC,
                                        "updatedAt"
                                )
                                .and(
                                        org.springframework.data.domain.Sort
                                                .by(
                                                        org.springframework
                                                                .data
                                                                .domain
                                                                .Sort
                                                                .Direction
                                                                .DESC,
                                                        "createdAt"
                                                )
                                )
                );

        PageImpl<WikiArticleJpaEntity> emptyPage =
                new PageImpl<>(
                        List.of(),
                        pageable,
                        0L
                );

        when(
                repository.findPage(
                        null,
                        null,
                        null,
                        pageable
                )
        ).thenReturn(
                emptyPage
        );

        WikiArticlePageDTO result =
                queryAdapter.findPage(
                        null,
                        null,
                        null,
                        0,
                        20
                );

        assertThat(result.items())
                .isEmpty();

        assertThat(result.totalElements())
                .isZero();

        assertThat(result.totalPages())
                .isZero();

        assertThat(result.first())
                .isTrue();

        assertThat(result.last())
                .isTrue();

        verify(repository)
                .findPage(
                        null,
                        null,
                        null,
                        pageable
                );
    }
    @Test
    @DisplayName(
            "Chỉ lấy bài PUBLISHED theo article type và slug"
    )
    void shouldFindPublishedArticleByTypeAndSlug() {
        WikiArticleJpaEntity entity =
                createPublishedEntity();

        when(
                repository
                        .findByArticleTypeAndSlugAndStatus(
                                "CHARACTER",
                                "tran-binh-an",
                                "PUBLISHED"
                        )
        ).thenReturn(
                Optional.of(entity)
        );

        Optional<PublishedWikiArticleDTO> result =
                queryAdapter
                        .findPublishedByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                new Slug(
                                        "tran-binh-an"
                                )
                        );

        assertThat(result)
                .isPresent();

        PublishedWikiArticleDTO article =
                result.orElseThrow();

        assertThat(article.id())
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(article.title())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(article.articleType())
                .isEqualTo(
                        "CHARACTER"
                );

        assertThat(article.publishedAt())
                .isEqualTo(
                        PUBLISHED_AT
                );

        verify(repository)
                .findByArticleTypeAndSlugAndStatus(
                        "CHARACTER",
                        "tran-binh-an",
                        "PUBLISHED"
                );
    }
    @Test
    @DisplayName(
            "Trả Optional rỗng khi không có bài PUBLISHED phù hợp"
    )
    void shouldReturnEmptyWhenPublishedArticleDoesNotExist() {
        when(
                repository
                        .findByArticleTypeAndSlugAndStatus(
                                "CHARACTER",
                                "tran-binh-an",
                                "PUBLISHED"
                        )
        ).thenReturn(
                Optional.empty()
        );

        Optional<PublishedWikiArticleDTO> result =
                queryAdapter
                        .findPublishedByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                new Slug(
                                        "tran-binh-an"
                                )
                        );

        assertThat(result)
                .isEmpty();
    }
    @Test
    @DisplayName(
            "Không gọi repository khi tham số public article không hợp lệ"
    )
    void shouldReturnEmptyForInvalidPublishedParameters() {
        Optional<PublishedWikiArticleDTO> nullTypeResult =
                queryAdapter
                        .findPublishedByArticleTypeAndSlug(
                                null,
                                new Slug(
                                        "tran-binh-an"
                                )
                        );

        Optional<PublishedWikiArticleDTO> nullSlugResult =
                queryAdapter
                        .findPublishedByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                null
                        );

        assertThat(nullTypeResult)
                .isEmpty();

        assertThat(nullSlugResult)
                .isEmpty();

        verify(
                repository,
                never()
        ).findByArticleTypeAndSlugAndStatus(
                anyString(),
                anyString(),
                anyString()
        );
    }
    @Test
    @DisplayName(
            "Lấy danh sách bài PUBLISHED có bộ lọc và ánh xạ DTO"
    )
    void shouldFindPublishedPageWithFilters() {
        WikiArticleJpaEntity entity =
                createPublishedEntity();

        Pageable pageable =
                PageRequest.of(
                        0,
                        20,
                        Sort.by(
                                Sort.Direction.DESC,
                                "updatedAt"
                        ).and(
                                Sort.by(
                                        Sort.Direction.DESC,
                                        "publishedAt"
                                )
                        )
                );

        PageImpl<WikiArticleJpaEntity> entityPage =
                new PageImpl<>(
                        List.of(entity),
                        pageable,
                        1L
                );

        when(
                repository.findPublishedPage(
                        "Trần Bình",
                        "CHARACTER",
                        "PUBLISHED",
                        pageable
                )
        ).thenReturn(
                entityPage
        );

        PublishedWikiArticlePageDTO result =
                queryAdapter.findPublishedPage(
                        "Trần Bình",
                        ArticleType.CHARACTER,
                        0,
                        20
                );

        assertThat(result.items())
                .hasSize(1);

        assertThat(result.page())
                .isZero();

        assertThat(result.size())
                .isEqualTo(20);

        assertThat(result.totalElements())
                .isEqualTo(1L);

        assertThat(result.totalPages())
                .isEqualTo(1);

        assertThat(result.first())
                .isTrue();

        assertThat(result.last())
                .isTrue();

        assertThat(result.items().get(0).id())
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(result.items().get(0).title())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(result.items().get(0).slug())
                .isEqualTo(
                        "tran-binh-an"
                );

        assertThat(result.items().get(0).articleType())
                .isEqualTo(
                        "CHARACTER"
                );

        assertThat(result.items().get(0).summary())
                .isEqualTo(
                        "Nhân vật chính của Kiếm Lai."
                );

        assertThat(result.items().get(0).publishedAt())
                .isEqualTo(
                        PUBLISHED_AT
                );

        assertThat(result.items().get(0).updatedAt())
                .isEqualTo(
                        UPDATED_AT
                );

        verify(repository)
                .findPublishedPage(
                        "Trần Bình",
                        "CHARACTER",
                        "PUBLISHED",
                        pageable
                );
    }
    @Test
    @DisplayName(
            "Lấy trang công khai rỗng khi không có bài PUBLISHED"
    )
    void shouldFindEmptyPublishedPageWithoutFilters() {
        Pageable pageable =
                PageRequest.of(
                        0,
                        20,
                        Sort.by(
                                Sort.Direction.DESC,
                                "updatedAt"
                        ).and(
                                Sort.by(
                                        Sort.Direction.DESC,
                                        "publishedAt"
                                )
                        )
                );

        PageImpl<WikiArticleJpaEntity> emptyPage =
                new PageImpl<>(
                        List.of(),
                        pageable,
                        0L
                );

        when(
                repository.findPublishedPage(
                        null,
                        null,
                        "PUBLISHED",
                        pageable
                )
        ).thenReturn(
                emptyPage
        );

        PublishedWikiArticlePageDTO result =
                queryAdapter.findPublishedPage(
                        null,
                        null,
                        0,
                        20
                );

        assertThat(result.items())
                .isEmpty();

        assertThat(result.page())
                .isZero();

        assertThat(result.size())
                .isEqualTo(20);

        assertThat(result.totalElements())
                .isZero();

        assertThat(result.totalPages())
                .isZero();

        assertThat(result.first())
                .isTrue();

        assertThat(result.last())
                .isTrue();

        verify(repository)
                .findPublishedPage(
                        null,
                        null,
                        "PUBLISHED",
                        pageable
                );
    }

    @Test
    @DisplayName("findPublishedContextualMatches: Escapes LIKE metacharacters and maps entities to DTOs")
    void shouldFindPublishedContextualMatchesAndEscapeLikeWildcards() {
        WikiArticleJpaEntity entity = createPublishedEntity();

        Pageable expectedPageable = PageRequest.of(0, 5);

        when(repository.findPublishedContextualMatches(
                "50%_discount\\test",
                "50\\%\\_discount\\\\test",
                expectedPageable
        )).thenReturn(List.of(entity));

        List<com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO> result =
                queryAdapter.findPublishedContextualMatches("  50%_discount\\test  ", 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(ARTICLE_ID);
        assertThat(result.get(0).title()).isEqualTo("Trần Bình An");

        verify(repository).findPublishedContextualMatches(
                "50%_discount\\test",
                "50\\%\\_discount\\\\test",
                expectedPageable
        );
    }

    @Test
    @DisplayName("findPublishedContextualMatches: Returns empty when query is null, blank, or maxResults <= 0")
    void shouldReturnEmptyWhenQueryIsBlankOrInvalidLimit() {
        assertThat(queryAdapter.findPublishedContextualMatches(null, 5)).isEmpty();
        assertThat(queryAdapter.findPublishedContextualMatches("   ", 5)).isEmpty();
        assertThat(queryAdapter.findPublishedContextualMatches("test", 0)).isEmpty();
        assertThat(queryAdapter.findPublishedContextualMatches("test", -1)).isEmpty();

        verify(repository, never()).findPublishedContextualMatches(anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("findPublishedArticlesByNormalizedAlias: Queries repository with Pageable and maps to DTO")
    void shouldFindPublishedArticlesByNormalizedAlias() {
        WikiArticleJpaEntity entity = createPublishedEntity();
        Pageable expectedPageable = PageRequest.of(0, 5);

        when(repository.findPublishedArticlesByNormalizedAlias("tiểu phu tử", expectedPageable))
                .thenReturn(List.of(entity));

        List<com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO> result =
                queryAdapter.findPublishedArticlesByNormalizedAlias("  tiểu phu tử  ", 5);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(ARTICLE_ID);
        assertThat(result.get(0).title()).isEqualTo("Trần Bình An");

        verify(repository).findPublishedArticlesByNormalizedAlias("tiểu phu tử", expectedPageable);
    }

    @Test
    @DisplayName("findPublishedArticlesByNormalizedAlias: Returns empty when alias is null, blank, or maxResults <= 0")
    void shouldReturnEmptyWhenAliasIsBlankOrInvalidLimit() {
        assertThat(queryAdapter.findPublishedArticlesByNormalizedAlias(null, 5)).isEmpty();
        assertThat(queryAdapter.findPublishedArticlesByNormalizedAlias("   ", 5)).isEmpty();
        assertThat(queryAdapter.findPublishedArticlesByNormalizedAlias("test", 0)).isEmpty();
        assertThat(queryAdapter.findPublishedArticlesByNormalizedAlias("test", -1)).isEmpty();

        verify(repository, never()).findPublishedArticlesByNormalizedAlias(anyString(), any(Pageable.class));
    }
}