package com.universe.wiki.application.revision.query.detail;

import com.universe.wiki.application.exceptions
        .WikiArticleRevisionNotFoundException;
import com.universe.wiki.application.ports
        .WikiArticleRevisionQueryPort;
import com.universe.wiki.contracts.dto
        .WikiArticleRevisionDetailDTO;

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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetWikiArticleRevisionDetailUseCaseTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID REVISION_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final long REVISION_NUMBER =
            4L;

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-06T10:00:00Z"
            );

    @Mock
    private WikiArticleRevisionQueryPort
            revisionQueryPort;

    private GetWikiArticleRevisionDetailUseCase
            getDetailUseCase;

    @BeforeEach
    void setUp() {
        getDetailUseCase =
                new GetWikiArticleRevisionDetailUseCase(
                        revisionQueryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy chi tiết revision theo article ID và revision number"
    )
    void shouldGetRevisionDetail() {
        WikiArticleRevisionDetailDTO expectedRevision =
                createRevisionDTO();

        when(
                revisionQueryPort
                        .findDetailByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                REVISION_NUMBER
                        )
        ).thenReturn(
                Optional.of(expectedRevision)
        );

        WikiArticleRevisionDetailDTO result =
                getDetailUseCase.execute(
                        new GetWikiArticleRevisionDetailQuery(
                                ARTICLE_ID,
                                REVISION_NUMBER
                        )
                );

        assertThat(result)
                .isEqualTo(
                        expectedRevision
                );

        assertThat(result.articleId())
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(result.revisionNumber())
                .isEqualTo(
                        REVISION_NUMBER
                );

        assertThat(result.title())
                .isEqualTo(
                        "Trần Bình An"
                );

        assertThat(result.content())
                .isEqualTo(
                        "Nội dung tại revision số 4."
                );

        assertThat(result.changeType())
                .isEqualTo(
                        "UPDATE_PUBLISHED"
                );

        verify(revisionQueryPort)
                .findDetailByArticleIdAndRevisionNumber(
                        ARTICLE_ID,
                        REVISION_NUMBER
                );
    }

    @Test
    @DisplayName(
            "Từ chối khi không tìm thấy revision"
    )
    void shouldRejectWhenRevisionDoesNotExist() {
        when(
                revisionQueryPort
                        .findDetailByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                REVISION_NUMBER
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                getDetailUseCase.execute(
                        new GetWikiArticleRevisionDetailQuery(
                                ARTICLE_ID,
                                REVISION_NUMBER
                        )
                )
        )
                .isInstanceOf(
                        WikiArticleRevisionNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy revision 4 "
                                + "của bài viết Wiki: "
                                + ARTICLE_ID
                );
    }

    @Test
    @DisplayName(
            "Từ chối article ID null"
    )
    void shouldRejectNullArticleId() {
        GetWikiArticleRevisionDetailQuery query =
                new GetWikiArticleRevisionDetailQuery(
                        null,
                        REVISION_NUMBER
                );

        assertThatThrownBy(() ->
                getDetailUseCase.execute(query)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Article ID không được để trống."
                );

        verify(
                revisionQueryPort,
                never()
        ).findDetailByArticleIdAndRevisionNumber(
                any(),
                anyLong()
        );
    }

    @Test
    @DisplayName(
            "Từ chối revision number nhỏ hơn 1"
    )
    void shouldRejectInvalidRevisionNumber() {
        GetWikiArticleRevisionDetailQuery query =
                new GetWikiArticleRevisionDetailQuery(
                        ARTICLE_ID,
                        0L
                );

        assertThatThrownBy(() ->
                getDetailUseCase.execute(query)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Revision number phải lớn hơn hoặc bằng 1."
                );

        verify(
                revisionQueryPort,
                never()
        ).findDetailByArticleIdAndRevisionNumber(
                any(),
                anyLong()
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
                        "Get wiki article revision detail query "
                                + "không được để trống."
                );

        verify(
                revisionQueryPort,
                never()
        ).findDetailByArticleIdAndRevisionNumber(
                any(),
                anyLong()
        );
    }

    private WikiArticleRevisionDetailDTO
            createRevisionDTO() {

        return new WikiArticleRevisionDetailDTO(
                REVISION_ID,
                ARTICLE_ID,
                REVISION_NUMBER,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Tóm tắt tại revision số 4.",
                "Nội dung tại revision số 4.",
                "PUBLISHED",
                "UPDATE_PUBLISHED",
                "Bổ sung dữ kiện chương 150",
                ADMIN_ID,
                CREATED_AT
        );
    }
}