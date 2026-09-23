package com.universe.wiki.application.article.cover;

import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateAndPublishWikiArticleUseCase;
import com.universe.wiki.application.article.create.CreateWikiArticleCommand;
import com.universe.wiki.application.article.create.CreateWikiArticleUseCase;
import com.universe.wiki.application.article.delete.DeleteWikiArticleCommand;
import com.universe.wiki.application.article.delete.DeleteWikiArticleUseCase;
import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailQuery;
import com.universe.wiki.application.article.query.detail.GetWikiArticleDetailUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftAndPublishWikiArticleCommand;
import com.universe.wiki.application.article.update.draft.UpdateDraftAndPublishWikiArticleUseCase;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleCommand;
import com.universe.wiki.application.article.update.draft.UpdateDraftWikiArticleUseCase;
import com.universe.wiki.application.article.update.published.UpdatePublishedWikiArticleCommand;
import com.universe.wiki.application.article.update.published.UpdatePublishedWikiArticleUseCase;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.ArticleType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WikiArticleCoverOrchestratorTest {

    private static final UUID ARTICLE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private WikiCoverMediaCoordinator mediaCoordinator;
    @Mock
    private CreateWikiArticleUseCase createWikiArticleUseCase;
    @Mock
    private CreateAndPublishWikiArticleUseCase createAndPublishWikiArticleUseCase;
    @Mock
    private UpdateDraftWikiArticleUseCase updateDraftWikiArticleUseCase;
    @Mock
    private UpdateDraftAndPublishWikiArticleUseCase updateDraftAndPublishWikiArticleUseCase;
    @Mock
    private UpdatePublishedWikiArticleUseCase updatePublishedWikiArticleUseCase;
    @Mock
    private DeleteWikiArticleUseCase deleteWikiArticleUseCase;
    @Mock
    private GetWikiArticleDetailUseCase getWikiArticleDetailUseCase;

    private WikiArticleCoverOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new WikiArticleCoverOrchestrator(
                mediaCoordinator,
                createWikiArticleUseCase,
                createAndPublishWikiArticleUseCase,
                updateDraftWikiArticleUseCase,
                updateDraftAndPublishWikiArticleUseCase,
                updatePublishedWikiArticleUseCase,
                deleteWikiArticleUseCase,
                getWikiArticleDetailUseCase
        );
    }

    private WikiArticleDTO createSampleDTO(UUID coverAssetId) {
        return new WikiArticleDTO(
                ARTICLE_ID,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "Tóm tắt",
                "Nội dung",
                "DRAFT",
                ACTOR_ID,
                ACTOR_ID,
                null,
                null,
                Instant.now(),
                Instant.now(),
                null,
                null,
                1L,
                1L,
                coverAssetId
        );
    }

    @Test
    @DisplayName("createDraft không có ảnh: gọi thẳng createWikiArticleUseCase")
    void shouldCreateDraftWithoutCover() {
        CreateWikiArticleCommand command = new CreateWikiArticleCommand(
                "Trần Bình An", ArticleType.CHARACTER, "Tóm tắt", "Nội dung", "Khởi tạo", ACTOR_ID
        );
        WikiArticleDTO expected = createSampleDTO(null);
        when(createWikiArticleUseCase.execute(command)).thenReturn(expected);

        WikiArticleDTO result = orchestrator.createDraft(command, null);

        assertThat(result).isSameAs(expected);
        verify(mediaCoordinator, never()).uploadInitialCover(any());
        verify(createWikiArticleUseCase).execute(command);
    }

    @Test
    @DisplayName("createDraft có ảnh: upload trước, lưu DB kèm coverMediaAssetId")
    void shouldCreateDraftWithCover() {
        UUID newAssetId = UUID.randomUUID();
        WikiCoverUpload upload = new WikiCoverUpload(new ByteArrayInputStream(new byte[]{1, 2}), 2, "image/jpeg", "cover.jpg");
        CreateWikiArticleCommand command = new CreateWikiArticleCommand(
                "Trần Bình An", ArticleType.CHARACTER, "Tóm tắt", "Nội dung", "Khởi tạo", ACTOR_ID
        );
        WikiArticleDTO expected = createSampleDTO(newAssetId);

        when(mediaCoordinator.uploadInitialCover(upload)).thenReturn(newAssetId);
        when(createWikiArticleUseCase.execute(any(CreateWikiArticleCommand.class))).thenReturn(expected);

        WikiArticleDTO result = orchestrator.createDraft(command, upload);

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<CreateWikiArticleCommand> captor = ArgumentCaptor.forClass(CreateWikiArticleCommand.class);
        verify(createWikiArticleUseCase).execute(captor.capture());
        assertThat(captor.getValue().coverMediaAssetId()).isEqualTo(newAssetId);
    }

    @Test
    @DisplayName("createDraft có ảnh nhưng lưu DB lỗi: đền bù xóa asset vừa upload")
    void shouldCompensateWhenCreateDraftDbFails() {
        UUID newAssetId = UUID.randomUUID();
        WikiCoverUpload upload = new WikiCoverUpload(new ByteArrayInputStream(new byte[]{1, 2}), 2, "image/jpeg", "cover.jpg");
        CreateWikiArticleCommand command = new CreateWikiArticleCommand(
                "Trần Bình An", ArticleType.CHARACTER, "Tóm tắt", "Nội dung", "Khởi tạo", ACTOR_ID
        );
        RuntimeException dbError = new RuntimeException("DB unique index failure");

        when(mediaCoordinator.uploadInitialCover(upload)).thenReturn(newAssetId);
        when(createWikiArticleUseCase.execute(any(CreateWikiArticleCommand.class))).thenThrow(dbError);

        assertThatThrownBy(() -> orchestrator.createDraft(command, upload))
                .isSameAs(dbError);

        verify(mediaCoordinator).compensateInitialCover(newAssetId, dbError);
    }

    @Test
    @DisplayName("updateDraft: từ chối nếu vừa chọn removeCover vừa gửi file upload")
    void shouldRejectSimultaneousRemoveAndUpload() {
        WikiCoverUpload upload = new WikiCoverUpload(new ByteArrayInputStream(new byte[]{1}), 1, "image/png", "c.png");
        UpdateDraftWikiArticleCommand command = new UpdateDraftWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER, "Tóm tắt", "Nội dung", "Update", ACTOR_ID
        );

        assertThatThrownBy(() -> orchestrator.updateDraft(command, upload, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Không thể đồng thời vừa xóa ảnh bìa vừa tải lên ảnh bìa mới.");
    }

    @Test
    @DisplayName("updateDraft tải lên ảnh mới cho bài chưa có cover: upload initial, gán assetId mới")
    void shouldUploadInitialCoverForDraftWithoutPriorCover() {
        UUID newAssetId = UUID.randomUUID();
        WikiCoverUpload upload = new WikiCoverUpload(new ByteArrayInputStream(new byte[]{1}), 1, "image/png", "c.png");
        UpdateDraftWikiArticleCommand command = new UpdateDraftWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER, "Tóm tắt", "Nội dung", "Update", ACTOR_ID
        );

        when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
                .thenReturn(createSampleDTO(null));
        when(mediaCoordinator.uploadInitialCover(upload)).thenReturn(newAssetId);
        when(updateDraftWikiArticleUseCase.execute(any(UpdateDraftWikiArticleCommand.class)))
                .thenReturn(createSampleDTO(newAssetId));

        WikiArticleDTO result = orchestrator.updateDraft(command, upload, false);

        assertThat(result.coverMediaAssetId()).isEqualTo(newAssetId);
        verify(mediaCoordinator).uploadInitialCover(upload);
        verify(mediaCoordinator, never()).replaceCoverVersion(any(), any());
    }

    @Test
    @DisplayName("updateDraft thay thế ảnh bìa khi đã có cover: gọi replaceCoverVersion")
    void shouldReplaceCoverVersionForDraftWithExistingCover() {
        UUID existingCoverId = UUID.randomUUID();
        WikiCoverUpload upload = new WikiCoverUpload(new ByteArrayInputStream(new byte[]{1}), 1, "image/png", "c.png");
        UpdateDraftWikiArticleCommand command = new UpdateDraftWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER, "Tóm tắt", "Nội dung", "Update", ACTOR_ID
        );

        when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
                .thenReturn(createSampleDTO(existingCoverId));
        when(updateDraftWikiArticleUseCase.execute(any(UpdateDraftWikiArticleCommand.class)))
                .thenReturn(createSampleDTO(existingCoverId));

        orchestrator.updateDraft(command, upload, false);

        verify(mediaCoordinator).replaceCoverVersion(existingCoverId, upload);
        verify(mediaCoordinator, never()).uploadInitialCover(any());
    }

    @Test
    @DisplayName("updateDraft gỡ ảnh bìa: cập nhật null trong DB, gọi deleteCover SAU KHI commit")
    void shouldRemoveCoverInDraft() {
        UUID existingCoverId = UUID.randomUUID();
        UpdateDraftWikiArticleCommand command = new UpdateDraftWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER, "Tóm tắt", "Nội dung", "Remove cover", ACTOR_ID
        );

        when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
                .thenReturn(createSampleDTO(existingCoverId));
        when(updateDraftWikiArticleUseCase.execute(any(UpdateDraftWikiArticleCommand.class)))
                .thenReturn(createSampleDTO(null));

        orchestrator.updateDraft(command, null, true);

        ArgumentCaptor<UpdateDraftWikiArticleCommand> captor = ArgumentCaptor.forClass(UpdateDraftWikiArticleCommand.class);
        verify(updateDraftWikiArticleUseCase).execute(captor.capture());
        assertThat(captor.getValue().coverMediaAssetId()).isNull();
        assertThat(captor.getValue().updateCover()).isTrue();

        verify(mediaCoordinator).deleteCover(existingCoverId);
    }

    @Test
    @DisplayName("deleteArticle: xóa bài viết Wiki trước, xóa Media Asset sau")
    void shouldDeleteArticleAndThenDeleteCover() {
        UUID coverId = UUID.randomUUID();
        when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
                .thenReturn(createSampleDTO(coverId));

        orchestrator.deleteArticle(ARTICLE_ID);

        verify(deleteWikiArticleUseCase).execute(new DeleteWikiArticleCommand(ARTICLE_ID));
        verify(mediaCoordinator).deleteCover(coverId);
    }

    @Test
    @DisplayName("deleteArticle cho bài không có ảnh bìa: chỉ xóa bài viết Wiki")
    void shouldDeleteArticleWithoutCover() {
        when(getWikiArticleDetailUseCase.execute(new GetWikiArticleDetailQuery(ARTICLE_ID)))
                .thenReturn(createSampleDTO(null));

        orchestrator.deleteArticle(ARTICLE_ID);

        verify(deleteWikiArticleUseCase).execute(new DeleteWikiArticleCommand(ARTICLE_ID));
        verify(mediaCoordinator, never()).deleteCover(any());
    }
}
