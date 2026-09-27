package com.universe.wiki.application.article.update.published;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionWorkflowEventRepositoryPort;
import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionEventType;
import com.universe.wiki.domain.contribution.WikiContributionType;
import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;
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
    private WikiCoverOrphanRepositoryPort
            orphanRepositoryPort;

    @Mock
    private WikiContributionRepositoryPort
            contributionRepositoryPort;

    @Mock
    private WikiContributionWorkflowEventRepositoryPort
            workflowEventRepositoryPort;

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
                        orphanRepositoryPort,
                        contributionRepositoryPort,
                        workflowEventRepositoryPort,
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

    /*
     * =====================================================
     * COVER TRI-STATE SEMANTICS
     * =====================================================
     */

    @Test
    @DisplayName("Tri-state: Legacy update published command (without cover info) preserves existing cover")
    void shouldPreserveExistingCoverWhenLegacyCommandUsed() {
        UUID existingCoverId = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();
        article.changeCoverMediaAssetId(existingCoverId, ADMIN_ID, PUBLISHED_AT.plusSeconds(10));

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        // Legacy 5-arg constructor (updateCover = false)
        UpdatePublishedWikiArticleCommand legacyCmd = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt mới", "Nội dung cập nhật mới", "Sửa tóm tắt", ADMIN_ID
        );

        WikiArticleDTO result = updatePublishedUseCase.execute(legacyCmd);

        assertThat(result.coverMediaAssetId()).isEqualTo(existingCoverId);
        assertThat(article.getCoverMediaAssetId()).isEqualTo(existingCoverId);
    }

    @Test
    @DisplayName("Tri-state: Explicit same UUID preserves cover without fake cover mutation")
    void shouldPreserveCoverWithoutMutationWhenSameUuidProvided() {
        UUID existingCoverId = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();
        article.changeCoverMediaAssetId(existingCoverId, ADMIN_ID, PUBLISHED_AT.plusSeconds(10));

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);

        long aggBefore = article.getAggregateVersion();
        long contentBefore = article.getContentVersion();

        // 7-arg constructor with same UUID, updateCover = true, and identical content
        UpdatePublishedWikiArticleCommand sameCmd = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID,
                article.getSummary(),
                article.getContent(),
                "Không thay đổi gì",
                ADMIN_ID,
                existingCoverId,
                true
        );

        WikiArticleDTO result = updatePublishedUseCase.execute(sameCmd);

        assertThat(result.coverMediaAssetId()).isEqualTo(existingCoverId);
        assertThat(article.getAggregateVersion()).isEqualTo(aggBefore);
        assertThat(article.getContentVersion()).isEqualTo(contentBefore);
        verify(articleRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Tri-state: Explicit new UUID changes cover, increments aggregateVersion, keeps contentVersion")
    void shouldChangeCoverWhenNewUuidProvided() {
        UUID oldCoverId = UUID.randomUUID();
        UUID newCoverId = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();
        article.changeCoverMediaAssetId(oldCoverId, ADMIN_ID, PUBLISHED_AT.plusSeconds(10));

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        long aggBefore = article.getAggregateVersion();
        long contentBefore = article.getContentVersion();

        // Identical summary and content, but new cover UUID with updateCover = true
        UpdatePublishedWikiArticleCommand newCoverCmd = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID,
                article.getSummary(),
                article.getContent(),
                "Đổi ảnh bìa bài xuất bản",
                ADMIN_ID,
                newCoverId,
                true
        );

        WikiArticleDTO result = updatePublishedUseCase.execute(newCoverCmd);

        assertThat(result.coverMediaAssetId()).isEqualTo(newCoverId);
        assertThat(article.getCoverMediaAssetId()).isEqualTo(newCoverId);
        assertThat(article.getAggregateVersion()).isEqualTo(aggBefore + 1);
        assertThat(article.getContentVersion()).isEqualTo(contentBefore);
        verify(articleRepositoryPort).save(article);
    }

    @Test
    @DisplayName("Tri-state: Explicit null removes cover, increments aggregateVersion, keeps contentVersion")
    void shouldRemoveCoverWhenNullProvidedWithUpdateFlagTrue() {
        UUID oldCoverId = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();
        article.changeCoverMediaAssetId(oldCoverId, ADMIN_ID, PUBLISHED_AT.plusSeconds(10));

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        long aggBefore = article.getAggregateVersion();
        long contentBefore = article.getContentVersion();

        // Identical summary and content, explicit null with updateCover = true
        UpdatePublishedWikiArticleCommand removeCoverCmd = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID,
                article.getSummary(),
                article.getContent(),
                "Gỡ ảnh bìa bài xuất bản",
                ADMIN_ID,
                null,
                true
        );

        WikiArticleDTO result = updatePublishedUseCase.execute(removeCoverCmd);

        assertThat(result.coverMediaAssetId()).isNull();
        assertThat(article.getCoverMediaAssetId()).isNull();
        assertThat(article.getAggregateVersion()).isEqualTo(aggBefore + 1);
        assertThat(article.getContentVersion()).isEqualTo(contentBefore);
        verify(articleRepositoryPort).save(article);
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

    @Test
    @DisplayName("Case A: Gỡ ảnh bìa bài đã xuất bản (previous=A, final=null) -> Ghi nhận orphan observation cho A")
    void shouldRecordOrphanObservationWhenCoverRemovedOnPublishedArticle() {
        UUID coverA = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();
        article.changeCoverMediaAssetId(coverA, ADMIN_ID, PUBLISHED_AT.plusSeconds(5));

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt mới", "Nội dung mới",
                "Remove cover", ADMIN_ID, null, 50, 50, true
        );

        updatePublishedUseCase.execute(command);

        verify(orphanRepositoryPort).recordOrphanObservation(coverA, CONTENT_UPDATED_AT);
        verify(orphanRepositoryPort, never()).deleteByMediaAssetId(any());
    }

    @Test
    @DisplayName("Case B: Giữ nguyên ảnh bìa bài đã xuất bản (previous=A, final=A) -> Không ghi nhận orphan, xóa stale orphan cho A")
    void shouldClearStaleOrphanWhenCoverUnchangedOnPublishedArticle() {
        UUID coverA = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();
        article.changeCoverMediaAssetId(coverA, ADMIN_ID, PUBLISHED_AT.plusSeconds(5));

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt mới", "Nội dung mới",
                "Keep cover", ADMIN_ID, coverA, 50, 50, true
        );

        updatePublishedUseCase.execute(command);

        verify(orphanRepositoryPort, never()).recordOrphanObservation(any(), any());
        verify(orphanRepositoryPort).deleteByMediaAssetId(coverA);
    }

    @Test
    @DisplayName("Case C: Gán ảnh bìa cho bài đã xuất bản chưa có cover (previous=null, final=B) -> Xóa stale orphan cho B")
    void shouldClearStaleOrphanWhenCoverAddedToCoverlessPublishedArticle() {
        UUID coverB = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt mới", "Nội dung mới",
                "Add cover", ADMIN_ID, coverB, 50, 50, true
        );

        updatePublishedUseCase.execute(command);

        verify(orphanRepositoryPort, never()).recordOrphanObservation(any(), any());
        verify(orphanRepositoryPort).deleteByMediaAssetId(coverB);
    }

    @Test
    @DisplayName("Case D: Thay thế ảnh bìa bài đã xuất bản (previous=A, final=B) -> Ghi nhận orphan cho A, xóa stale orphan cho B")
    void shouldRecordOrphanForPreviousAndClearStaleOrphanForFinalOnPublishedArticle() {
        UUID coverA = UUID.randomUUID();
        UUID coverB = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();
        article.changeCoverMediaAssetId(coverA, ADMIN_ID, PUBLISHED_AT.plusSeconds(5));

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt mới", "Nội dung mới",
                "Replace cover", ADMIN_ID, coverB, 50, 50, true
        );

        updatePublishedUseCase.execute(command);

        verify(orphanRepositoryPort).recordOrphanObservation(coverA, CONTENT_UPDATED_AT);
        verify(orphanRepositoryPort).deleteByMediaAssetId(coverB);
    }

    @Test
    @DisplayName("Lưu revision chứa sourceContributionId và phát sinh sự kiện ARTICLE_UPDATE_LINKED vào sổ nhật ký quy trình")
    void shouldSaveRevisionWithSourceContributionIdAndEmitWorkflowEvent() {
        UUID sourceContributionId = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();

        WikiContribution contribution = WikiContribution.createGeneral(
                sourceContributionId,
                ARTICLE_ID,
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                WikiContributionType.MISSING_INFORMATION,
                "Nội dung đóng góp bổ sung thông tin.",
                CONTENT_UPDATED_AT
        );
        contribution.startReview(ADMIN_ID, 1L, CONTENT_UPDATED_AT);

        when(contributionRepositoryPort.findById(sourceContributionId)).thenReturn(Optional.of(contribution));
        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID).thenReturn(UUID.randomUUID());

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt cập nhật", "Nội dung cập nhật mới",
                "Áp dụng đóng góp của độc giả", ADMIN_ID, 50, 50, sourceContributionId
        );

        updatePublishedUseCase.execute(command);

        ArgumentCaptor<WikiArticleRevision> revisionCaptor = ArgumentCaptor.forClass(WikiArticleRevision.class);
        verify(revisionRepositoryPort).save(revisionCaptor.capture());
        WikiArticleRevision savedRevision = revisionCaptor.getValue();
        assertThat(savedRevision.sourceContributionId()).isEqualTo(sourceContributionId);

        ArgumentCaptor<WikiContributionWorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WikiContributionWorkflowEvent.class);
        verify(workflowEventRepositoryPort).save(eventCaptor.capture());
        WikiContributionWorkflowEvent savedEvent = eventCaptor.getValue();
        assertThat(savedEvent.contributionId()).isEqualTo(sourceContributionId);
        assertThat(savedEvent.eventType()).isEqualTo(WikiContributionEventType.ARTICLE_UPDATE_LINKED);
        assertThat(savedEvent.actorUserId()).isEqualTo(ADMIN_ID);
        assertThat(savedEvent.note()).isEqualTo("Áp dụng đóng góp của độc giả");
    }

    @Test
    @DisplayName("Từ chối liên kết đóng góp khi không tìm thấy đóng góp trong cơ sở dữ liệu")
    void shouldRejectWhenLinkedContributionNotFound() {
        UUID sourceContributionId = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(contributionRepositoryPort.findById(sourceContributionId)).thenReturn(Optional.empty());

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt cập nhật", "Nội dung cập nhật mới",
                "Áp dụng đóng góp", ADMIN_ID, 50, 50, sourceContributionId
        );

        assertThatThrownBy(() -> updatePublishedUseCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không tìm thấy đóng góp được liên kết");
    }

    @Test
    @DisplayName("Từ chối liên kết đóng góp khi đóng góp thuộc bài viết khác")
    void shouldRejectWhenContributionBelongsToDifferentArticle() {
        UUID sourceContributionId = UUID.randomUUID();
        UUID otherArticleId = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();

        WikiContribution contribution = WikiContribution.createGeneral(
                sourceContributionId,
                otherArticleId,
                "CHARACTER",
                "Ninh Dao",
                "ninh-dao",
                1L,
                UUID.randomUUID(),
                WikiContributionType.MISSING_INFORMATION,
                "Nội dung đóng góp hợp lệ về bài viết của nhân vật khác.",
                CONTENT_UPDATED_AT
        );

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(contributionRepositoryPort.findById(sourceContributionId)).thenReturn(Optional.of(contribution));

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt cập nhật", "Nội dung cập nhật mới",
                "Áp dụng đóng góp", ADMIN_ID, 50, 50, sourceContributionId
        );

        assertThatThrownBy(() -> updatePublishedUseCase.execute(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không thuộc bài viết này");
    }

    @Test
    @DisplayName("Từ chối liên kết đóng góp khi đóng góp chưa ở trạng thái REVIEWING")
    void shouldRejectWhenContributionIsNotInReviewingStatus() {
        UUID sourceContributionId = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();

        WikiContribution contribution = WikiContribution.createGeneral(
                sourceContributionId,
                ARTICLE_ID,
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                WikiContributionType.MISSING_INFORMATION,
                "Nội dung đóng góp mới",
                CONTENT_UPDATED_AT
        ); // Status is NEW

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(contributionRepositoryPort.findById(sourceContributionId)).thenReturn(Optional.of(contribution));

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt cập nhật", "Nội dung cập nhật mới",
                "Áp dụng đóng góp", ADMIN_ID, 50, 50, sourceContributionId
        );

        assertThatThrownBy(() -> updatePublishedUseCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REVIEWING");
    }

    @Test
    @DisplayName("Từ chối liên kết đóng góp khi đóng góp đang ở trạng thái REVIEWING nhưng chưa được phân công (chưa claim)")
    void shouldRejectWhenContributionIsInReviewingStatusButUnassigned() {
        UUID sourceContributionId = UUID.randomUUID();
        WikiArticle article = createPublishedArticle();

        WikiContribution contribution = WikiContribution.reconstitute(
                sourceContributionId,
                ARTICLE_ID,
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                com.universe.wiki.domain.contribution.WikiContributionContextType.GENERAL,
                com.universe.wiki.domain.contribution.WikiContributionType.MISSING_INFORMATION,
                "Nội dung đóng góp mới",
                null,
                null,
                null,
                null,
                com.universe.wiki.domain.contribution.WikiContributionStatus.REVIEWING,
                0L,
                CONTENT_UPDATED_AT,
                CONTENT_UPDATED_AT,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(contributionRepositoryPort.findById(sourceContributionId)).thenReturn(Optional.of(contribution));

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt cập nhật", "Nội dung cập nhật mới",
                "Áp dụng đóng góp", ADMIN_ID, 50, 50, sourceContributionId
        );

        assertThatThrownBy(() -> updatePublishedUseCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chưa có người phụ trách xử lý");
    }

    @Test
    @DisplayName("Cho phép người cập nhật bài viết khác với người được phân công xử lý đóng góp (hợp tác đa quản trị viên)")
    void shouldAllowDifferentWikiUpdaterThanContributionAssignee() {
        UUID sourceContributionId = UUID.randomUUID();
        UUID otherAdminId = UUID.randomUUID(); // Admin A = Assignee, ADMIN_ID = Wiki updater
        WikiArticle article = createPublishedArticle();

        WikiContribution contribution = WikiContribution.createGeneral(
                sourceContributionId,
                ARTICLE_ID,
                "CHARACTER",
                "Trần Bình An",
                "tran-binh-an",
                1L,
                UUID.randomUUID(),
                WikiContributionType.MISSING_INFORMATION,
                "Nội dung đóng góp mới",
                CONTENT_UPDATED_AT
        );
        contribution.startReview(otherAdminId, 1L, CONTENT_UPDATED_AT);

        when(contributionRepositoryPort.findById(sourceContributionId)).thenReturn(Optional.of(contribution));
        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID).thenReturn(UUID.randomUUID());

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt cập nhật", "Nội dung cập nhật mới",
                "Áp dụng đóng góp của độc giả", ADMIN_ID, 50, 50, sourceContributionId
        );

        WikiArticleDTO result = updatePublishedUseCase.execute(command);
        assertThat(result).isNotNull();

        ArgumentCaptor<WikiContributionWorkflowEvent> eventCaptor = ArgumentCaptor.forClass(WikiContributionWorkflowEvent.class);
        verify(workflowEventRepositoryPort).save(eventCaptor.capture());
        WikiContributionWorkflowEvent savedEvent = eventCaptor.getValue();
        assertThat(savedEvent.actorUserId()).isEqualTo(ADMIN_ID); // Wiki updater is the actor
        assertThat(savedEvent.fromStatus()).isEqualTo(com.universe.wiki.domain.contribution.WikiContributionStatus.REVIEWING);
        assertThat(savedEvent.toStatus()).isEqualTo(com.universe.wiki.domain.contribution.WikiContributionStatus.REVIEWING);
    }

    @Test
    @DisplayName("Không phát sinh sự kiện quy trình khi sourceContributionId là null")
    void shouldOmitWorkflowEventWhenSourceContributionIdIsNull() {
        WikiArticle article = createPublishedArticle();

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(clockPort.now()).thenReturn(CONTENT_UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        UpdatePublishedWikiArticleCommand command = new UpdatePublishedWikiArticleCommand(
                ARTICLE_ID, "Tóm tắt cập nhật", "Nội dung cập nhật mới",
                "Cập nhật thông thường", ADMIN_ID, 50, 50, null
        );

        updatePublishedUseCase.execute(command);

        verify(workflowEventRepositoryPort, never()).save(any());
    }
}