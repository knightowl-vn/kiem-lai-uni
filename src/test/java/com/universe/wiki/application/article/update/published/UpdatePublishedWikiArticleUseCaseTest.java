package com.universe.wiki.application.article.update.published;

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
class UpdatePublishedWikiArticleUseCaseTest {

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

    private static final Instant CONTENT_UPDATED_AT =
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

    private UpdatePublishedWikiArticleUseCase
            updatePublishedUseCase;

    @BeforeEach
    void setUp() {
        updatePublishedUseCase =
                new UpdatePublishedWikiArticleUseCase(
                        articleRepositoryPort,
                        revisionRepositoryPort,
                        idGeneratorPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Cập nhật bài PUBLISHED và lưu revision UPDATE_PUBLISHED"
    )
    void shouldUpdatePublishedArticleAndSaveRevision() {
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
                CONTENT_UPDATED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        WikiArticleDTO result =
                updatePublishedUseCase.execute(
                        createCommand()
                );

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

        assertThat(article.getSummary())
                .isEqualTo(
                        "Tóm tắt đã cập nhật."
                );

        assertThat(article.getContent())
                .isEqualTo(
                        "Bổ sung thông tin từ chương 150."
                );

        assertThat(article.getStatus())
                .isEqualTo(
                        ArticleStatus.PUBLISHED
                );

        assertThat(article.getUpdatedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(article.getUpdatedAt())
                .isEqualTo(
                        CONTENT_UPDATED_AT
                );

        /*
         * createDraft        = 1
         * updateDraft        = 2
         * publish            = 3
         * updatePublished    = 4
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
                        ArticleStatus.PUBLISHED
                );

        assertThat(revision.changeType())
                .isEqualTo(
                        RevisionChangeType.UPDATE_PUBLISHED
                );

        assertThat(revision.editSummary())
                .isEqualTo(
                        "Bổ sung dữ kiện từ chương 150"
                );

        assertThat(revision.editedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(revision.createdAt())
                .isEqualTo(
                        CONTENT_UPDATED_AT
                );

        assertThat(result.status())
                .isEqualTo(
                        "PUBLISHED"
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
                CONTENT_UPDATED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        UpdatePublishedWikiArticleCommand command =
                new UpdatePublishedWikiArticleCommand(
                        ARTICLE_ID,
                        "Tóm tắt mới.",
                        "Nội dung mới.",
                        "   ",
                        ADMIN_ID
                );

        updatePublishedUseCase.execute(
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
                        "Cập nhật nội dung bài viết đã xuất bản"
                );
    }

    @Test
    @DisplayName(
            "Từ chối cập nhật khi không tìm thấy bài viết"
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
                updatePublishedUseCase.execute(
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
            "Không cho cập nhật published đối với bài DRAFT"
    )
    void shouldRejectUpdatingDraftThroughPublishedFlow() {
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
                CONTENT_UPDATED_AT
        );

        assertThatThrownBy(() ->
                updatePublishedUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ bài viết ở trạng thái PUBLISHED "
                                + "mới được cập nhật theo luồng này."
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
            "Không cho cập nhật published đối với bài ARCHIVED"
    )
    void shouldRejectUpdatingArchivedArticle() {
        WikiArticle article =
                createPublishedArticle();

        article.archive(
                ADMIN_ID,
                CONTENT_UPDATED_AT.minusSeconds(60)
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
                CONTENT_UPDATED_AT
        );

        assertThatThrownBy(() ->
                updatePublishedUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ bài viết ở trạng thái PUBLISHED "
                                + "mới được cập nhật theo luồng này."
                );

        verify(
                articleRepositoryPort,
                never()
        ).save(any());

        verify(
                revisionRepositoryPort,
                never()
        ).save(any());
    }

    @Test
    @DisplayName(
            "Lỗi lưu revision làm update published thất bại"
    )
    void shouldFailWhenSavingRevisionFails() {
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
                CONTENT_UPDATED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        doThrow(
                new IllegalStateException(
                        "Không thể lưu update published revision."
                )
        )
                .when(revisionRepositoryPort)
                .save(
                        any(WikiArticleRevision.class)
                );

        assertThatThrownBy(() ->
                updatePublishedUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Không thể lưu update published revision."
                );

        verify(articleRepositoryPort)
                .save(article);

        verify(revisionRepositoryPort)
                .save(
                        any(WikiArticleRevision.class)
                );
    }

    private UpdatePublishedWikiArticleCommand
            createCommand() {

        return new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID,
                "Tóm tắt đã cập nhật.",
                "Bổ sung thông tin từ chương 150.",
                "Bổ sung dữ kiện từ chương 150",
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
                "Nội dung hoàn chỉnh ban đầu.",
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