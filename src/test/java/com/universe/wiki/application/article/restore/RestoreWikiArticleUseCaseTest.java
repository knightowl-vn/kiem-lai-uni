package com.universe.wiki.application.article.restore;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.ArticleSlugAlreadyExistsException;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.exceptions.WikiArticleRevisionNotFoundException;
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
class RestoreWikiArticleUseCaseTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID SOURCE_REVISION_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID NEW_REVISION_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    private static final UUID OTHER_ARTICLE_ID =
            UUID.fromString(
                    "55555555-5555-5555-5555-555555555555"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-06T07:00:00Z"
            );

    private static final Instant RESTORED_AT =
            Instant.parse(
                    "2026-08-06T12:00:00Z"
            );

    private static final long SOURCE_REVISION_NUMBER =
            2L;

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

    private RestoreWikiArticleUseCase
            restoreUseCase;

    @BeforeEach
    void setUp() {
        restoreUseCase =
                new RestoreWikiArticleUseCase(
                        articleRepositoryPort,
                        revisionRepositoryPort,
                        idGeneratorPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Khôi phục revision cũ thành DRAFT và lưu revision RESTORE"
    )
    void shouldRestoreRevisionAsDraftAndSaveNewRevision() {
        WikiArticle currentArticle =
                createArchivedArticle();

        WikiArticleRevision sourceRevision =
                createSourceRevision();

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(currentArticle)
        );

        when(
                revisionRepositoryPort
                        .findByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                SOURCE_REVISION_NUMBER
                        )
        ).thenReturn(
                Optional.of(sourceRevision)
        );

        when(
                articleRepositoryPort
                        .findByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                new Slug("tran-binh-an-ban-cu")
                        )
        ).thenReturn(
                Optional.empty()
        );

        when(
                clockPort.now()
        ).thenReturn(
                RESTORED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                NEW_REVISION_ID
        );

        WikiArticleDTO result =
                restoreUseCase.execute(
                        createCommand()
                );

        assertThat(currentArticle.getTitle())
                .isEqualTo(
                        "Trần Bình An bản cũ"
                );

        assertThat(currentArticle.getSlug().value())
                .isEqualTo(
                        "tran-binh-an-ban-cu"
                );

        assertThat(currentArticle.getSummary())
                .isEqualTo(
                        "Tóm tắt ở revision cũ."
                );

        assertThat(currentArticle.getContent())
                .isEqualTo(
                        "Nội dung ở revision cũ."
                );

        assertThat(currentArticle.getStatus())
                .isEqualTo(
                        ArticleStatus.DRAFT
                );

        assertThat(currentArticle.getPublishedBy())
                .isNull();

        assertThat(currentArticle.getPublishedAt())
                .isNull();

        assertThat(currentArticle.getArchivedBy())
                .isNull();

        assertThat(currentArticle.getArchivedAt())
                .isNull();

        assertThat(currentArticle.getUpdatedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(currentArticle.getUpdatedAt())
                .isEqualTo(
                        RESTORED_AT
                );

        /*
         * createDraft = 1
         * updateDraft = 2
         * publish     = 3
         * archive     = 4
         * restore     = 5
         */
        assertThat(currentArticle.getAggregateVersion())
                .isEqualTo(5L);

        verify(articleRepositoryPort)
                .save(currentArticle);

        ArgumentCaptor<WikiArticleRevision>
                revisionCaptor =
                ArgumentCaptor.forClass(
                        WikiArticleRevision.class
                );

        verify(revisionRepositoryPort)
                .save(
                        revisionCaptor.capture()
                );

        WikiArticleRevision restoredRevision =
                revisionCaptor.getValue();

        assertThat(restoredRevision.id())
                .isEqualTo(
                        NEW_REVISION_ID
                );

        assertThat(restoredRevision.articleId())
                .isEqualTo(
                        ARTICLE_ID
                );

        assertThat(restoredRevision.revisionNumber())
                .isEqualTo(5L);

        assertThat(restoredRevision.status())
                .isEqualTo(
                        ArticleStatus.DRAFT
                );

        assertThat(restoredRevision.changeType())
                .isEqualTo(
                        RevisionChangeType.RESTORE
                );

        assertThat(restoredRevision.editSummary())
                .isEqualTo(
                        "Khôi phục dữ liệu chính xác"
                );

        assertThat(restoredRevision.editedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(restoredRevision.createdAt())
                .isEqualTo(
                        RESTORED_AT
                );

        assertThat(result.status())
                .isEqualTo("DRAFT");

        assertThat(result.aggregateVersion())
                .isEqualTo(5L);
    }

    @Test
    @DisplayName(
            "Dùng ghi chú mặc định khi edit summary để trống"
    )
    void shouldUseDefaultEditSummaryWhenBlank() {
        WikiArticle article =
                createArchivedArticle();

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        when(
                revisionRepositoryPort
                        .findByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                SOURCE_REVISION_NUMBER
                        )
        ).thenReturn(
                Optional.of(
                        createSourceRevision()
                )
        );

        when(
                articleRepositoryPort
                        .findByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                new Slug("tran-binh-an-ban-cu")
                        )
        ).thenReturn(
                Optional.empty()
        );

        when(
                clockPort.now()
        ).thenReturn(
                RESTORED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                NEW_REVISION_ID
        );

        RestoreWikiArticleCommand command =
                new RestoreWikiArticleCommand(
                        ARTICLE_ID,
                        SOURCE_REVISION_NUMBER,
                        "   ",
                        ADMIN_ID
                );

        restoreUseCase.execute(
                command
        );

        ArgumentCaptor<WikiArticleRevision>
                captor =
                ArgumentCaptor.forClass(
                        WikiArticleRevision.class
                );

        verify(revisionRepositoryPort)
                .save(
                        captor.capture()
                );

        assertThat(
                captor.getValue()
                        .editSummary()
        )
                .isEqualTo(
                        "Khôi phục từ revision 2"
                );
    }

    @Test
    @DisplayName(
            "Từ chối khi không tìm thấy bài viết"
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
                restoreUseCase.execute(
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
                revisionRepositoryPort,
                never()
        ).findByArticleIdAndRevisionNumber(
                any(),
                any(Long.class)
        );

        verify(
                articleRepositoryPort,
                never()
        ).save(any());
    }

    @Test
    @DisplayName(
            "Từ chối khi không tìm thấy revision nguồn"
    )
    void shouldRejectWhenSourceRevisionDoesNotExist() {
        WikiArticle article =
                createArchivedArticle();

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        when(
                revisionRepositoryPort
                        .findByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                SOURCE_REVISION_NUMBER
                        )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                restoreUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        WikiArticleRevisionNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy revision 2 "
                                + "của bài viết Wiki: "
                                + ARTICLE_ID
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
            "Từ chối slug của revision đang thuộc bài khác"
    )
    void shouldRejectRestoredSlugOwnedByAnotherArticle() {
        WikiArticle currentArticle =
                createArchivedArticle();

        WikiArticle anotherArticle =
                WikiArticle.createDraft(
                        OTHER_ARTICLE_ID,
                        "Bài viết khác",
                        new Slug(
                                "tran-binh-an-ban-cu"
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
                Optional.of(currentArticle)
        );

        when(
                revisionRepositoryPort
                        .findByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                SOURCE_REVISION_NUMBER
                        )
        ).thenReturn(
                Optional.of(
                        createSourceRevision()
                )
        );

        when(
                articleRepositoryPort
                        .findByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                new Slug(
                                        "tran-binh-an-ban-cu"
                                )
                        )
        ).thenReturn(
                Optional.of(anotherArticle)
        );

        assertThatThrownBy(() ->
                restoreUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        ArticleSlugAlreadyExistsException.class
                )
                .hasMessage(
                        "Slug bài viết đã tồn tại trong loại "
                                + "CHARACTER: tran-binh-an-ban-cu"
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
            "Lỗi lưu revision RESTORE làm use case thất bại"
    )
    void shouldFailWhenSavingRestoreRevisionFails() {
        WikiArticle article =
                createArchivedArticle();

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        when(
                revisionRepositoryPort
                        .findByArticleIdAndRevisionNumber(
                                ARTICLE_ID,
                                SOURCE_REVISION_NUMBER
                        )
        ).thenReturn(
                Optional.of(
                        createSourceRevision()
                )
        );

        when(
                articleRepositoryPort
                        .findByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                new Slug(
                                        "tran-binh-an-ban-cu"
                                )
                        )
        ).thenReturn(
                Optional.empty()
        );

        when(
                clockPort.now()
        ).thenReturn(
                RESTORED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                NEW_REVISION_ID
        );

        doThrow(
                new IllegalStateException(
                        "Không thể lưu restore revision."
                )
        )
                .when(revisionRepositoryPort)
                .save(
                        any(WikiArticleRevision.class)
                );

        assertThatThrownBy(() ->
                restoreUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Không thể lưu restore revision."
                );

        verify(articleRepositoryPort)
                .save(article);

        verify(revisionRepositoryPort)
                .save(
                        any(WikiArticleRevision.class)
                );
    }

    private RestoreWikiArticleCommand createCommand() {
        return new RestoreWikiArticleCommand(
                ARTICLE_ID,
                SOURCE_REVISION_NUMBER,
                "Khôi phục dữ liệu chính xác",
                ADMIN_ID
        );
    }

    private WikiArticle createArchivedArticle() {
        WikiArticle article =
                WikiArticle.createDraft(
                        ARTICLE_ID,
                        "Trần Bình An hiện tại",
                        new Slug(
                                "tran-binh-an-hien-tai"
                        ),
                        ArticleType.CHARACTER,
                        ADMIN_ID,
                        CREATED_AT
                );

        article.updateDraft(
                "Trần Bình An hiện tại",
                new Slug(
                        "tran-binh-an-hien-tai"
                ),
                ArticleType.CHARACTER,
                "Tóm tắt hiện tại.",
                "Nội dung hiện tại.",
                ADMIN_ID,
                CREATED_AT.plusSeconds(60)
        );

        article.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(120)
        );

        article.archive(
                ADMIN_ID,
                CREATED_AT.plusSeconds(180)
        );

        return article;
    }

    private WikiArticleRevision createSourceRevision() {
        return new WikiArticleRevision(
                SOURCE_REVISION_ID,
                ARTICLE_ID,
                SOURCE_REVISION_NUMBER,
                "Trần Bình An bản cũ",
                new Slug(
                        "tran-binh-an-ban-cu"
                ),
                ArticleType.CHARACTER,
                "Tóm tắt ở revision cũ.",
                "Nội dung ở revision cũ.",
                ArticleStatus.DRAFT,
                RevisionChangeType.UPDATE_DRAFT,
                "Bản nội dung cũ",
                ADMIN_ID,
                CREATED_AT.plusSeconds(60)
        );
    }
}