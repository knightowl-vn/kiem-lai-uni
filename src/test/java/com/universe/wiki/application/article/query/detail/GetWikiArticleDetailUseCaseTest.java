package com.universe.wiki.application.article.query.detail;

import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;

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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetWikiArticleDetailUseCaseTest {

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

    @Mock
    private WikiArticleQueryPort
            articleQueryPort;

    private GetWikiArticleDetailUseCase
            getDetailUseCase;

    @BeforeEach
    void setUp() {
        getDetailUseCase =
                new GetWikiArticleDetailUseCase(
                        articleQueryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy chi tiết bài Wiki theo ID"
    )
    void shouldGetWikiArticleDetailById() {
        WikiArticleDTO expectedArticle =
                createArticleDTO();

        when(
                articleQueryPort.findDetailById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(expectedArticle)
        );

        WikiArticleDTO result =
                getDetailUseCase.execute(
                        new GetWikiArticleDetailQuery(
                                ARTICLE_ID
                        )
                );

        assertThat(result)
                .isEqualTo(
                        expectedArticle
                );

        assertThat(result.id())
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(result.title())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(result.status())
                .isEqualTo(
                        "PUBLISHED"
                );

        verify(articleQueryPort)
                .findDetailById(
                        ARTICLE_ID
                );
    }

    @Test
    @DisplayName(
            "Từ chối khi không tìm thấy bài Wiki"
    )
    void shouldRejectWhenArticleDoesNotExist() {
        when(
                articleQueryPort.findDetailById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                getDetailUseCase.execute(
                        new GetWikiArticleDetailQuery(
                                ARTICLE_ID
                        )
                )
        )
                .isInstanceOf(
                        WikiArticleNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy bài viết Wiki: "
                                + ARTICLE_ID
                );
    }

    @Test
    @DisplayName(
            "Từ chối query null"
    )
    void shouldRejectNullQuery() {
        assertThatThrownBy(() ->
                getDetailUseCase.execute(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Get wiki article detail query "
                                + "không được để trống."
                );

        verify(
                articleQueryPort,
                never()
        ).findDetailById(
                org.mockito.ArgumentMatchers.any()
        );
    }

    private WikiArticleDTO createArticleDTO() {
        return new WikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Nhân vật chính của Kiếm Lai.",
                "Nội dung chi tiết.",
                "PUBLISHED",
                ADMIN_ID,
                ADMIN_ID,
                ADMIN_ID,
                null,
                CREATED_AT,
                CREATED_AT,
                CREATED_AT,
                null,
                3L
        );
    }
}