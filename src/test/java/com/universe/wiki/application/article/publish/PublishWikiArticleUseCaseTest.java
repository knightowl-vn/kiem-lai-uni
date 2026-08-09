package com.universe.wiki.application.article.publish;

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
class PublishWikiArticleUseCaseTest {

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

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-06T08:00:00Z"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-08-06T09:00:00Z"
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

    private PublishWikiArticleUseCase
            publishUseCase;

    @BeforeEach
    void setUp() {
        publishUseCase =
                new PublishWikiArticleUseCase(
                        articleRepositoryPort,
                        revisionRepositoryPort,
                        idGeneratorPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Xuất bản bài DRAFT và lưu revision PUBLISH"
    )
    void shouldPublishDraftAndSaveRevision() {
        WikiArticle article =
                createCompleteDraft();

        PublishWikiArticleCommand command =
                createCommand();

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
                PUBLISHED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        WikiArticleDTO result =
                publishUseCase.execute(
                        command
                );

        assertThat(article.getStatus())
                .isEqualTo(
                        ArticleStatus.PUBLISHED
                );

        assertThat(article.getPublishedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(article.getPublishedAt())
                .isEqualTo(
                        PUBLISHED_AT
                );

        assertThat(article.getUpdatedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(article.getUpdatedAt())
                .isEqualTo(
                        PUBLISHED_AT
                );

        /*
         * createDraft = version 1
         * updateDraft = version 2
         * publish = version 3
         */
        assertThat(article.getAggregateVersion())
                .isEqualTo(3L);

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
                .isEqualTo(3L);

        assertThat(revision.status())
                .isEqualTo(
                        ArticleStatus.PUBLISHED
                );

        assertThat(revision.changeType())
                .isEqualTo(
                        RevisionChangeType.PUBLISH
                );

        assertThat(revision.editSummary())
                .isEqualTo(
                        "Xuất bản lần đầu"
                );

        assertThat(revision.editedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(revision.createdAt())
                .isEqualTo(
                        PUBLISHED_AT
                );

        assertThat(result.status())
                .isEqualTo(
                        "PUBLISHED"
                );

        assertThat(result.publishedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(result.publishedAt())
                .isEqualTo(
                        PUBLISHED_AT
                );

        assertThat(result.aggregateVersion())
                .isEqualTo(3L);
    }

    @Test
    @DisplayName(
            "Dùng mô tả mặc định khi edit summary để trống"
    )
    void shouldUseDefaultEditSummaryWhenBlank() {
        WikiArticle article =
                createCompleteDraft();

        PublishWikiArticleCommand command =
                new PublishWikiArticleCommand(
                        ARTICLE_ID,
                        "   ",
                        ADMIN_ID
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
                PUBLISHED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        publishUseCase.execute(
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
                        "Xuất bản bài viết"
                );
    }

    @Test
    @DisplayName(
            "Từ chối xuất bản khi không tìm thấy bài viết"
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
                publishUseCase.execute(
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
            "Từ chối xuất bản bài DRAFT chưa có nội dung"
    )
    void shouldRejectPublishingDraftWithoutContent() {
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
                PUBLISHED_AT
        );

        assertThatThrownBy(() ->
                publishUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Bài viết phải có nội dung trước khi xuất bản."
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
            "Không cho xuất bản lại bài đã PUBLISHED"
    )
    void shouldRejectPublishingAlreadyPublishedArticle() {
        WikiArticle article =
                createCompleteDraft();

        article.publish(
                ADMIN_ID,
                PUBLISHED_AT.minusSeconds(60)
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
                PUBLISHED_AT
        );

        assertThatThrownBy(() ->
                publishUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Chỉ bài viết ở trạng thái DRAFT mới được xuất bản."
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
            "Lỗi lưu revision làm publish use case thất bại"
    )
    void shouldFailWhenSavingPublishRevisionFails() {
        WikiArticle article =
                createCompleteDraft();

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
                PUBLISHED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        doThrow(
                new IllegalStateException(
                        "Không thể lưu publish revision."
                )
        )
                .when(revisionRepositoryPort)
                .save(
                        any(WikiArticleRevision.class)
                );

        assertThatThrownBy(() ->
                publishUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Không thể lưu publish revision."
                );

        verify(articleRepositoryPort)
                .save(article);

        verify(revisionRepositoryPort)
                .save(
                        any(WikiArticleRevision.class)
                );
    }

    private PublishWikiArticleCommand createCommand() {
        return new PublishWikiArticleCommand(
                ARTICLE_ID,
                "Xuất bản lần đầu",
                ADMIN_ID
        );
    }

    private WikiArticle createCompleteDraft() {
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
                "Nội dung hoàn chỉnh về Trần Bình An.",
                ADMIN_ID,
                UPDATED_AT
        );

        return article;
    }
}