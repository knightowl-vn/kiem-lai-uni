package com.universe.wiki.application.article.unpublish;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import com.universe.wiki.application.exceptions
        .WikiArticleNotFoundException;

import com.universe.wiki.application.ports
        .WikiArticleRepositoryPort;

import com.universe.wiki.application.ports
        .WikiArticleRevisionRepositoryPort;

import com.universe.wiki.contracts.dto
        .WikiArticleDTO;

import com.universe.wiki.domain.article
        .ArticleStatus;

import com.universe.wiki.domain.article
        .ArticleType;

import com.universe.wiki.domain.article
        .Slug;

import com.universe.wiki.domain.article
        .WikiArticle;

import com.universe.wiki.domain.revision
        .RevisionChangeType;

import com.universe.wiki.domain.revision
        .WikiArticleRevision;

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

import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnpublishWikiArticleUseCaseTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID REVISION_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-09T01:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-09T02:00:00Z"
            );

    private static final Instant UNPUBLISHED_AT =
            Instant.parse(
                    "2026-08-09T03:00:00Z"
            );

    @Mock
    private WikiArticleRepositoryPort
            articleRepositoryPort;

    @Mock
    private WikiArticleRevisionRepositoryPort
            revisionRepositoryPort;

    @Mock
    private IdGeneratorPort
            idGeneratorPort;

    @Mock
    private ClockPort
            clockPort;

    private UnpublishWikiArticleUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new UnpublishWikiArticleUseCase(
                        articleRepositoryPort,
                        revisionRepositoryPort,
                        idGeneratorPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Gỡ xuất bản bài PUBLISHED và lưu revision UNPUBLISH"
    )
    void shouldUnpublishPublishedArticleAndSaveRevision() {
        WikiArticle article =
                createPublishedArticle();

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        when(
                clockPort.now()
        ).thenReturn(
                UNPUBLISHED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        long versionBefore =
                article.getAggregateVersion();

        WikiArticleDTO result =
                useCase.execute(
                        new UnpublishWikiArticleCommand(
                                ARTICLE_ID,
                                "Tạm gỡ để chỉnh sửa",
                                ADMIN_ID
                        )
                );

        assertThat(
                article.getStatus()
        ).isEqualTo(
                ArticleStatus.DRAFT
        );

        assertThat(
                article.getPublishedBy()
        ).isNull();

        assertThat(
                article.getPublishedAt()
        ).isNull();

        assertThat(
                article.getAggregateVersion()
        ).isEqualTo(
                versionBefore + 1
        );

        verify(
                articleRepositoryPort
        ).save(
                article
        );

        ArgumentCaptor<WikiArticleRevision>
                revisionCaptor =
                ArgumentCaptor.forClass(
                        WikiArticleRevision.class
                );

        verify(
                revisionRepositoryPort
        ).save(
                revisionCaptor.capture()
        );

        WikiArticleRevision revision =
                revisionCaptor.getValue();

        assertThat(
                revision.changeType()
        ).isEqualTo(
                RevisionChangeType.UNPUBLISH
        );

        assertThat(
                revision.status()
        ).isEqualTo(
                ArticleStatus.DRAFT
        );

        assertThat(
                revision.editSummary()
        ).isEqualTo(
                "Tạm gỡ để chỉnh sửa"
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "DRAFT"
        );
    }


    @Test
    @DisplayName(
            "Dùng ghi chú mặc định khi gỡ xuất bản"
    )
    void shouldUseDefaultEditSummary() {
        WikiArticle article =
                createPublishedArticle();

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        when(
                clockPort.now()
        ).thenReturn(
                UNPUBLISHED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        useCase.execute(
                new UnpublishWikiArticleCommand(
                        ARTICLE_ID,
                        "   ",
                        ADMIN_ID
                )
        );

        ArgumentCaptor<WikiArticleRevision>
                revisionCaptor =
                ArgumentCaptor.forClass(
                        WikiArticleRevision.class
                );

        verify(
                revisionRepositoryPort
        ).save(
                revisionCaptor.capture()
        );

        assertThat(
                revisionCaptor
                        .getValue()
                        .editSummary()
        ).isEqualTo(
                "Gỡ xuất bản bài viết"
        );
    }


    @Test
    @DisplayName(
            "Từ chối gỡ xuất bản khi không tìm thấy bài"
    )
    void shouldRejectWhenArticleDoesNotExist() {
        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new UnpublishWikiArticleCommand(
                                ARTICLE_ID,
                                null,
                                ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        WikiArticleNotFoundException.class
                );

        verify(
                articleRepositoryPort,
                never()
        ).save(
                any()
        );

        verify(
                revisionRepositoryPort,
                never()
        ).save(
                any()
        );

        verify(
                idGeneratorPort,
                never()
        ).generate();
    }


    @Test
    @DisplayName(
            "Không cho gỡ xuất bản bài đang DRAFT"
    )
    void shouldRejectUnpublishingDraft() {
        WikiArticle draft =
                createDraft();

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(draft)
        );

        when(
                clockPort.now()
        ).thenReturn(
                UNPUBLISHED_AT
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new UnpublishWikiArticleCommand(
                                ARTICLE_ID,
                                null,
                                ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ bài viết ở trạng thái PUBLISHED mới được gỡ xuất bản."
                );

        verify(
                articleRepositoryPort,
                never()
        ).save(
                any()
        );

        verify(
                revisionRepositoryPort,
                never()
        ).save(
                any()
        );
    }


    private WikiArticle createPublishedArticle() {
        WikiArticle article =
                createDraft();

        article.updateDraft(
                "Trần Bình An",
                new Slug(
                        "tran-binh-an"
                ),
                ArticleType.CHARACTER,
                "",
                "Nội dung bài viết.",
                ADMIN_ID,
                UPDATED_AT
        );

        article.publish(
                ADMIN_ID,
                UPDATED_AT.plusSeconds(60)
        );

        return article;
    }


    private WikiArticle createDraft() {
        return WikiArticle.createDraft(
                ARTICLE_ID,
                "Trần Bình An",
                new Slug(
                        "tran-binh-an"
                ),
                ArticleType.CHARACTER,
                ADMIN_ID,
                CREATED_AT
        );
    }
}