package com.universe.wiki.application.article.query.published;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.PublishedWikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.PublishedWikiArticlePageDTO;
import com.universe.wiki.domain.article.ArticleType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListPublishedWikiArticlesUseCaseTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-08-06T09:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-06T10:00:00Z"
            );

    @Mock
    private WikiArticleQueryPort
            articleQueryPort;

    private ListPublishedWikiArticlesUseCase
            listUseCase;

    @BeforeEach
    void setUp() {
        listUseCase =
                new ListPublishedWikiArticlesUseCase(
                        articleQueryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy danh sách bài PUBLISHED có tìm kiếm và bộ lọc"
    )
    void shouldListPublishedArticlesWithFilters() {
        PublishedWikiArticlePageDTO expectedPage =
                createPageDTO();

        when(
                articleQueryPort.findPublishedPage(
                        "Trần Bình",
                        ArticleType.CHARACTER,
                        0,
                        20
                )
        ).thenReturn(
                expectedPage
        );

        PublishedWikiArticlePageDTO result =
                listUseCase.execute(
                        new ListPublishedWikiArticlesQuery(
                                "  Trần Bình  ",
                                ArticleType.CHARACTER,
                                0,
                                20
                        )
                );

        assertThat(result)
                .isEqualTo(expectedPage);

        assertThat(result.items())
                .hasSize(1);

        assertThat(result.items().get(0).title())
                .isEqualTo("Trần Bình An");

        verify(articleQueryPort)
                .findPublishedPage(
                        "Trần Bình",
                        ArticleType.CHARACTER,
                        0,
                        20
                );
    }

    @Test
    @DisplayName(
            "Chuẩn hóa keyword rỗng thành null"
    )
    void shouldNormalizeBlankKeywordToNull() {
        PublishedWikiArticlePageDTO emptyPage =
                new PublishedWikiArticlePageDTO(
                        List.of(),
                        0,
                        20,
                        0L,
                        0,
                        true,
                        true
                );

        when(
                articleQueryPort.findPublishedPage(
                        null,
                        null,
                        0,
                        20
                )
        ).thenReturn(emptyPage);

        PublishedWikiArticlePageDTO result =
                listUseCase.execute(
                        new ListPublishedWikiArticlesQuery(
                                "   ",
                                null,
                                0,
                                20
                        )
                );

        assertThat(result.items())
                .isEmpty();

        verify(articleQueryPort)
                .findPublishedPage(
                        null,
                        null,
                        0,
                        20
                );
    }

    @Test
    @DisplayName(
            "Từ chối page âm"
    )
    void shouldRejectNegativePage() {
        assertThatThrownBy(() ->
                listUseCase.execute(
                        new ListPublishedWikiArticlesQuery(
                                null,
                                null,
                                -1,
                                20
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Page không được nhỏ hơn 0."
                );

        verify(
                articleQueryPort,
                never()
        ).findPublishedPage(
                any(),
                any(),
                anyInt(),
                anyInt()
        );
    }

    @Test
    @DisplayName(
            "Từ chối page size vượt quá 100"
    )
    void shouldRejectTooLargePageSize() {
        assertThatThrownBy(() ->
                listUseCase.execute(
                        new ListPublishedWikiArticlesQuery(
                                null,
                                null,
                                0,
                                101
                        )
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Page size không được vượt quá 100."
                );
    }

    @Test
    @DisplayName(
            "Từ chối query null"
    )
    void shouldRejectNullQuery() {
        assertThatThrownBy(() ->
                listUseCase.execute(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "List published wiki articles query "
                                + "không được để trống."
                );
    }

    private PublishedWikiArticlePageDTO createPageDTO() {
        PublishedWikiArticleListItemDTO item =
                new PublishedWikiArticleListItemDTO(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "tran-binh-an",
                        "CHARACTER",
                        "Nhân vật chính của Kiếm Lai.",
                        PUBLISHED_AT,
                        UPDATED_AT
                );

        return new PublishedWikiArticlePageDTO(
                List.of(item),
                0,
                20,
                1L,
                1,
                true,
                true
        );
    }
}