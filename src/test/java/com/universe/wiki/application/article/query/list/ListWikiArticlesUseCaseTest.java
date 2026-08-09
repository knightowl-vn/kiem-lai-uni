package com.universe.wiki.application.article.query.list;

import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import com.universe.wiki.contracts.dto.WikiArticlePageDTO;
import com.universe.wiki.domain.article.ArticleStatus;
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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListWikiArticlesUseCaseTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-06T07:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-06T08:00:00Z"
            );

    @Mock
    private WikiArticleQueryPort
            articleQueryPort;

    private ListWikiArticlesUseCase
            listUseCase;

    @BeforeEach
    void setUp() {
        listUseCase =
                new ListWikiArticlesUseCase(
                        articleQueryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy danh sách bài Wiki có phân trang và bộ lọc"
    )
    void shouldListWikiArticlesWithFilters() {
        ListWikiArticlesQuery query =
                new ListWikiArticlesQuery(
                        "Trần Bình",
                        ArticleType.CHARACTER,
                        ArticleStatus.PUBLISHED,
                        0,
                        20
                );

        WikiArticlePageDTO expectedPage =
                createPageDTO();

        when(
                articleQueryPort.findPage(
                        "Trần Bình",
                        ArticleType.CHARACTER,
                        ArticleStatus.PUBLISHED,
                        0,
                        20
                )
        ).thenReturn(
                expectedPage
        );

        WikiArticlePageDTO result =
                listUseCase.execute(
                        query
                );

        assertThat(result)
                .isEqualTo(
                        expectedPage
                );

        assertThat(result.items())
                .hasSize(1);

        assertThat(result.items().get(0).title())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(result.totalElements())
                .isEqualTo(1L);

        assertThat(result.first())
                .isTrue();

        assertThat(result.last())
                .isTrue();

        verify(articleQueryPort)
                .findPage(
                        "Trần Bình",
                        ArticleType.CHARACTER,
                        ArticleStatus.PUBLISHED,
                        0,
                        20
                );
    }

    @Test
    @DisplayName(
            "Chuẩn hóa keyword rỗng thành null"
    )
    void shouldNormalizeBlankKeywordToNull() {
        ListWikiArticlesQuery query =
                new ListWikiArticlesQuery(
                        "   ",
                        null,
                        null,
                        0,
                        20
                );

        WikiArticlePageDTO emptyPage =
                new WikiArticlePageDTO(
                        List.of(),
                        0,
                        20,
                        0L,
                        0,
                        true,
                        true
                );

        when(
                articleQueryPort.findPage(
                        null,
                        null,
                        null,
                        0,
                        20
                )
        ).thenReturn(
                emptyPage
        );

        WikiArticlePageDTO result =
                listUseCase.execute(
                        query
                );

        assertThat(result.items())
                .isEmpty();

        verify(articleQueryPort)
                .findPage(
                        null,
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
        ListWikiArticlesQuery query =
                new ListWikiArticlesQuery(
                        null,
                        null,
                        null,
                        -1,
                        20
                );

        assertThatThrownBy(() ->
                listUseCase.execute(query)
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
        ).findPage(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    @DisplayName(
            "Từ chối page size vượt quá 100"
    )
    void shouldRejectTooLargePageSize() {
        ListWikiArticlesQuery query =
                new ListWikiArticlesQuery(
                        null,
                        null,
                        null,
                        0,
                        101
                );

        assertThatThrownBy(() ->
                listUseCase.execute(query)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Page size không được vượt quá 100."
                );

        verify(
                articleQueryPort,
                never()
        ).findPage(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()
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
                        "List wiki articles query không được để trống."
                );

        verify(
                articleQueryPort,
                never()
        ).findPage(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    private WikiArticlePageDTO createPageDTO() {
        WikiArticleListItemDTO item =
                new WikiArticleListItemDTO(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "tran-binh-an",
                        "CHARACTER",
                        "PUBLISHED",
                        ADMIN_ID,
                        CREATED_AT,
                        UPDATED_AT,
                        3L
                );

        return new WikiArticlePageDTO(
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