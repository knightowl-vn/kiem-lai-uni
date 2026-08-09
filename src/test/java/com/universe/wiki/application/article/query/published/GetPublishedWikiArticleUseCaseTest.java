package com.universe.wiki.application.article.query.published;

import com.universe.wiki.application.exceptions
        .PublishedWikiArticleNotFoundException;
import com.universe.wiki.application.ports
        .WikiArticleQueryPort;
import com.universe.wiki.contracts.dto
        .PublishedWikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetPublishedWikiArticleUseCaseTest {

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

    private GetPublishedWikiArticleUseCase
            getPublishedUseCase;

    @BeforeEach
    void setUp() {
        getPublishedUseCase =
                new GetPublishedWikiArticleUseCase(
                        articleQueryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy bài Wiki đã xuất bản bằng article type và slug"
    )
    void shouldGetPublishedArticleByTypeAndSlug() {
        Slug slug =
                new Slug(
                        "tran-binh-an"
                );

        PublishedWikiArticleDTO expectedArticle =
                createPublishedDTO();

        when(
                articleQueryPort
                        .findPublishedByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                slug
                        )
        ).thenReturn(
                Optional.of(expectedArticle)
        );

        PublishedWikiArticleDTO result =
                getPublishedUseCase.execute(
                        new GetPublishedWikiArticleQuery(
                                ArticleType.CHARACTER,
                                "tran-binh-an"
                        )
                );

        assertThat(result)
                .isEqualTo(
                        expectedArticle
                );

        assertThat(result.title())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(result.articleType())
                .isEqualTo(
                        "CHARACTER"
                );

        assertThat(result.content())
                .isEqualTo(
                        "Nội dung công khai."
                );

        verify(articleQueryPort)
                .findPublishedByArticleTypeAndSlug(
                        ArticleType.CHARACTER,
                        slug
                );
    }

    @Test
    @DisplayName(
            "Từ chối khi không tìm thấy bài đã xuất bản"
    )
    void shouldRejectWhenPublishedArticleDoesNotExist() {
        Slug slug =
                new Slug(
                        "tran-binh-an"
                );

        when(
                articleQueryPort
                        .findPublishedByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                slug
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                getPublishedUseCase.execute(
                        new GetPublishedWikiArticleQuery(
                                ArticleType.CHARACTER,
                                "tran-binh-an"
                        )
                )
        )
                .isInstanceOf(
                        PublishedWikiArticleNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy bài Wiki đã xuất bản "
                                + "thuộc loại CHARACTER với slug: "
                                + "tran-binh-an"
                );
    }

    @Test
    @DisplayName(
            "Từ chối article type null"
    )
    void shouldRejectNullArticleType() {
        assertThatThrownBy(() ->
                getPublishedUseCase.execute(
                        new GetPublishedWikiArticleQuery(
                                null,
                                "tran-binh-an"
                        )
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Article type không được để trống."
                );

        verify(
                articleQueryPort,
                never()
        ).findPublishedByArticleTypeAndSlug(
                any(),
                any()
        );
    }

    @Test
    @DisplayName(
            "Từ chối query null"
    )
    void shouldRejectNullQuery() {
        assertThatThrownBy(() ->
                getPublishedUseCase.execute(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Get published wiki article query "
                                + "không được để trống."
                );

        verify(
                articleQueryPort,
                never()
        ).findPublishedByArticleTypeAndSlug(
                any(),
                any()
        );
    }

    private PublishedWikiArticleDTO
            createPublishedDTO() {

        return new PublishedWikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính của Kiếm Lai.",
                "Nội dung công khai.",
                PUBLISHED_AT,
                UPDATED_AT
        );
    }
}