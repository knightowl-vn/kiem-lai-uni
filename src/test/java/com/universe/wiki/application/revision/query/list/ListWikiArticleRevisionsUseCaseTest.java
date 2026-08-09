package com.universe.wiki.application.revision.query.list;

import com.universe.wiki.application.ports.WikiArticleRevisionQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleRevisionListItemDTO;
import com.universe.wiki.contracts.dto.WikiArticleRevisionPageDTO;

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
class ListWikiArticleRevisionsUseCaseTest {

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

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-06T10:00:00Z"
            );

    @Mock
    private WikiArticleRevisionQueryPort
            revisionQueryPort;

    private ListWikiArticleRevisionsUseCase
            listRevisionsUseCase;

    @BeforeEach
    void setUp() {
        listRevisionsUseCase =
                new ListWikiArticleRevisionsUseCase(
                        revisionQueryPort
                );
    }

    @Test
    @DisplayName(
            "Lấy danh sách revision của một bài Wiki"
    )
    void shouldListArticleRevisions() {
        WikiArticleRevisionPageDTO expectedPage =
                createPageDTO();

        when(
                revisionQueryPort
                        .findPageByArticleId(
                                ARTICLE_ID,
                                0,
                                20
                        )
        ).thenReturn(
                expectedPage
        );

        WikiArticleRevisionPageDTO result =
                listRevisionsUseCase.execute(
                        new ListWikiArticleRevisionsQuery(
                                ARTICLE_ID,
                                0,
                                20
                        )
                );

        assertThat(result)
                .isEqualTo(
                        expectedPage
                );

        assertThat(result.items())
                .hasSize(1);

        WikiArticleRevisionListItemDTO item =
                result.items().get(0);

        assertThat(item.articleId())
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(item.revisionNumber())
                .isEqualTo(4L);

        assertThat(item.changeType())
                .isEqualTo(
                        "UPDATE_PUBLISHED"
                );

        assertThat(item.status())
                .isEqualTo(
                        "PUBLISHED"
                );

        verify(revisionQueryPort)
                .findPageByArticleId(
                        ARTICLE_ID,
                        0,
                        20
                );
    }

    @Test
    @DisplayName(
            "Từ chối article ID null"
    )
    void shouldRejectNullArticleId() {
        ListWikiArticleRevisionsQuery query =
                new ListWikiArticleRevisionsQuery(
                        null,
                        0,
                        20
                );

        assertThatThrownBy(() ->
                listRevisionsUseCase.execute(query)
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
        ).findPageByArticleId(
                any(),
                anyInt(),
                anyInt()
        );
    }

    @Test
    @DisplayName(
            "Từ chối page âm"
    )
    void shouldRejectNegativePage() {
        ListWikiArticleRevisionsQuery query =
                new ListWikiArticleRevisionsQuery(
                        ARTICLE_ID,
                        -1,
                        20
                );

        assertThatThrownBy(() ->
                listRevisionsUseCase.execute(query)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Page không được nhỏ hơn 0."
                );

        verify(
                revisionQueryPort,
                never()
        ).findPageByArticleId(
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
        ListWikiArticleRevisionsQuery query =
                new ListWikiArticleRevisionsQuery(
                        ARTICLE_ID,
                        0,
                        101
                );

        assertThatThrownBy(() ->
                listRevisionsUseCase.execute(query)
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Page size không được vượt quá 100."
                );

        verify(
                revisionQueryPort,
                never()
        ).findPageByArticleId(
                any(),
                anyInt(),
                anyInt()
        );
    }

    @Test
    @DisplayName(
            "Từ chối query null"
    )
    void shouldRejectNullQuery() {
        assertThatThrownBy(() ->
                listRevisionsUseCase.execute(null)
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "List wiki article revisions query "
                                + "không được để trống."
                );

        verify(
                revisionQueryPort,
                never()
        ).findPageByArticleId(
                any(),
                anyInt(),
                anyInt()
        );
    }

    private WikiArticleRevisionPageDTO
            createPageDTO() {

        WikiArticleRevisionListItemDTO item =
                new WikiArticleRevisionListItemDTO(
                        REVISION_ID,
                        ARTICLE_ID,
                        4L,
                        "PUBLISHED",
                        "UPDATE_PUBLISHED",
                        "Bổ sung dữ kiện chương 150",
                        ADMIN_ID,
                        CREATED_AT
                );

        return new WikiArticleRevisionPageDTO(
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