package com.universe.wiki.application.article.archive;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;

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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchiveWikiArticleUseCaseTest {

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
                    "2026-08-06T07:00:00Z"
            );

    private static final Instant DRAFT_UPDATED_AT =
            Instant.parse(
                    "2026-08-06T08:00:00Z"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-08-06T09:00:00Z"
            );

    private static final Instant ARCHIVED_AT =
            Instant.parse(
                    "2026-08-06T10:00:00Z"
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

    private ArchiveWikiArticleUseCase
            archiveUseCase;

    @BeforeEach
    void setUp() {
        archiveUseCase =
                new ArchiveWikiArticleUseCase(
                        articleRepositoryPort,
                        revisionRepositoryPort,
                        idGeneratorPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Lưu trữ bài PUBLISHED và tạo revision ARCHIVE"
    )
    void shouldArchivePublishedArticleAndSaveRevision() {
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
                ARCHIVED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        WikiArticleDTO result =
                archiveUseCase.execute(
                        createCommand()
                );

        assertThat(article.getStatus())
                .isEqualTo(
                        ArticleStatus.ARCHIVED
                );

        assertThat(article.getArchivedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(article.getArchivedAt())
                .isEqualTo(
                        ARCHIVED_AT
                );

        assertThat(article.getUpdatedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(article.getUpdatedAt())
                .isEqualTo(
                        ARCHIVED_AT
                );

        /*
         * createDraft = 1
         * updateDraft = 2
         * publish     = 3
         * archive     = 4
         */
        assertThat(article.getAggregateVersion())
                .isEqualTo(4L);

        verify(articleRepositoryPort)
                .save(article);

        ArgumentCaptor<WikiArticleRevision>
                revisionCaptor =
                ArgumentCaptor.forClass(
                        WikiArticleRevision.class
                );

        verify(revisionRepositoryPort)
                .save(
                        revisionCaptor.capture()
                );

        WikiArticleRevision revision =
                revisionCaptor.getValue();

        assertThat(revision.id())
                .isEqualTo(
                        REVISION_ID
                );

        assertThat(revision.articleId())
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(revision.revisionNumber())
                .isEqualTo(4L);

        assertThat(revision.status())
                .isEqualTo(
                        ArticleStatus.ARCHIVED
                );

        assertThat(revision.changeType())
                .isEqualTo(
                        RevisionChangeType.ARCHIVE
                );

        assertThat(revision.editSummary())
                .isEqualTo(
                        "Nội dung không còn được sử dụng"
                );

        assertThat(revision.editedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(revision.createdAt())
                .isEqualTo(
                        ARCHIVED_AT
                );

        assertThat(result.status())
                .isEqualTo(
                        "ARCHIVED"
                );

        assertThat(result.archivedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(result.archivedAt())
                .isEqualTo(
                        ARCHIVED_AT
                );

        assertThat(result.aggregateVersion())
                .isEqualTo(4L);
    }

    @Test
    @DisplayName(
            "Dùng ghi chú mặc định khi edit summary để trống"
    )
    void shouldUseDefaultEditSummaryWhenBlank() {
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
                ARCHIVED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        ArchiveWikiArticleCommand command =
                new ArchiveWikiArticleCommand(
                        ARTICLE_ID,
                        "   ",
                        ADMIN_ID
                );

        archiveUseCase.execute(
                command
        );

        ArgumentCaptor<WikiArticleRevision>
                revisionCaptor =
                ArgumentCaptor.forClass(
                        WikiArticleRevision.class
                );

        verify(revisionRepositoryPort)
                .save(
                        revisionCaptor.capture()
                );

        assertThat(
                revisionCaptor
                        .getValue()
                        .editSummary()
        )
                .isEqualTo(
                        "Lưu trữ bài viết"
                );
    }

    @Test
    @DisplayName(
            "Từ chối lưu trữ khi không tìm thấy bài viết"
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
                archiveUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        WikiArticleNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy bài viết Wiki: "
                                + ARTICLE_ID
                );

        verify(
                clockPort,
                never()
        ).now();

        verify(
                articleRepositoryPort,
                never()
        ).save(any());

        verify(
                revisionRepositoryPort,
                never()
        ).save(any());

        verify(
                idGeneratorPort,
                never()
        ).generate();
    }

    @Test
    @DisplayName(
            "Không cho lưu trữ lại bài đã ARCHIVED"
    )
    void shouldRejectArchivingAlreadyArchivedArticle() {
        WikiArticle article =
                createPublishedArticle();

        article.archive(
                ADMIN_ID,
                ARCHIVED_AT.minusSeconds(60)
        );

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
                ARCHIVED_AT
        );

        assertThatThrownBy(() ->
                archiveUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Bài viết đã ở trạng thái ARCHIVED."
                );

        verify(
                articleRepositoryPort,
                never()
        ).save(any());

        verify(
                revisionRepositoryPort,
                never()
        ).save(any());

        verify(
                idGeneratorPort,
                never()
        ).generate();
    }

    @Test
    @DisplayName(
            "Cho phép lưu trữ bài viết đang DRAFT"
    )
    void shouldAllowArchivingDraftArticle() {
        WikiArticle article =
                WikiArticle.createDraft(
                        ARTICLE_ID,
                        "Trần Bình An",
                        new Slug(
                                "tran-binh-an"
                        ),
                        ArticleType.CHARACTER,
                        ADMIN_ID,
                        CREATED_AT
                );

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
                ARCHIVED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        archiveUseCase.execute(
                createCommand()
        );

        assertThat(article.getStatus())
                .isEqualTo(
                        ArticleStatus.ARCHIVED
                );

        assertThat(article.getAggregateVersion())
                .isEqualTo(2L);

        verify(articleRepositoryPort)
                .save(article);

        verify(revisionRepositoryPort)
                .save(
                        any(WikiArticleRevision.class)
                );
    }

    @Test
    @DisplayName(
            "Lỗi lưu revision làm archive use case thất bại"
    )
    void shouldFailWhenSavingArchiveRevisionFails() {
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
                ARCHIVED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        doThrow(
                new IllegalStateException(
                        "Không thể lưu archive revision."
                )
        )
                .when(revisionRepositoryPort)
                .save(
                        any(WikiArticleRevision.class)
                );

        assertThatThrownBy(() ->
                archiveUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Không thể lưu archive revision."
                );

        verify(articleRepositoryPort)
                .save(article);

        verify(revisionRepositoryPort)
                .save(
                        any(WikiArticleRevision.class)
                );
    }

    private ArchiveWikiArticleCommand createCommand() {
        return new ArchiveWikiArticleCommand(
                ARTICLE_ID,
                "Nội dung không còn được sử dụng",
                ADMIN_ID
        );
    }

    private WikiArticle createPublishedArticle() {
        WikiArticle article =
                WikiArticle.createDraft(
                        ARTICLE_ID,
                        "Trần Bình An",
                        new Slug(
                                "tran-binh-an"
                        ),
                        ArticleType.CHARACTER,
                        ADMIN_ID,
                        CREATED_AT
                );

        article.updateDraft(
                "Trần Bình An",
                new Slug(
                        "tran-binh-an"
                ),
                ArticleType.CHARACTER,
                "Nhân vật chính của Kiếm Lai.",
                "Nội dung hoàn chỉnh.",
                ADMIN_ID,
                DRAFT_UPDATED_AT
        );

        article.publish(
                ADMIN_ID,
                PUBLISHED_AT
        );

        return article;
    }
}