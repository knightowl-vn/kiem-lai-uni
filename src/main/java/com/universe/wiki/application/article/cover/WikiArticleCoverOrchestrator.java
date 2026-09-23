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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Non-transactional application orchestrator coordinating Wiki cover media operations
 * with existing transactional Wiki article use cases.
 *
 * <p><strong>Transaction Isolation:</strong> This service is intentionally NOT {@code @Transactional}.
 * Media uploads, version replacements, and cleanup operations are executed outside active Wiki
 * database transactions to prevent storage I/O from stalling database connection pools.
 */
@Service
public class WikiArticleCoverOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikiArticleCoverOrchestrator.class);

    private final WikiCoverMediaCoordinator mediaCoordinator;
    private final CreateWikiArticleUseCase createWikiArticleUseCase;
    private final CreateAndPublishWikiArticleUseCase createAndPublishWikiArticleUseCase;
    private final UpdateDraftWikiArticleUseCase updateDraftWikiArticleUseCase;
    private final UpdateDraftAndPublishWikiArticleUseCase updateDraftAndPublishWikiArticleUseCase;
    private final UpdatePublishedWikiArticleUseCase updatePublishedWikiArticleUseCase;
    private final DeleteWikiArticleUseCase deleteWikiArticleUseCase;
    private final GetWikiArticleDetailUseCase getWikiArticleDetailUseCase;

    public WikiArticleCoverOrchestrator(
            WikiCoverMediaCoordinator mediaCoordinator,
            CreateWikiArticleUseCase createWikiArticleUseCase,
            CreateAndPublishWikiArticleUseCase createAndPublishWikiArticleUseCase,
            UpdateDraftWikiArticleUseCase updateDraftWikiArticleUseCase,
            UpdateDraftAndPublishWikiArticleUseCase updateDraftAndPublishWikiArticleUseCase,
            UpdatePublishedWikiArticleUseCase updatePublishedWikiArticleUseCase,
            DeleteWikiArticleUseCase deleteWikiArticleUseCase,
            GetWikiArticleDetailUseCase getWikiArticleDetailUseCase
    ) {
        this.mediaCoordinator = Objects.requireNonNull(mediaCoordinator, "WikiCoverMediaCoordinator cannot be null.");
        this.createWikiArticleUseCase = Objects.requireNonNull(createWikiArticleUseCase, "CreateWikiArticleUseCase cannot be null.");
        this.createAndPublishWikiArticleUseCase = Objects.requireNonNull(createAndPublishWikiArticleUseCase, "CreateAndPublishWikiArticleUseCase cannot be null.");
        this.updateDraftWikiArticleUseCase = Objects.requireNonNull(updateDraftWikiArticleUseCase, "UpdateDraftWikiArticleUseCase cannot be null.");
        this.updateDraftAndPublishWikiArticleUseCase = Objects.requireNonNull(updateDraftAndPublishWikiArticleUseCase, "UpdateDraftAndPublishWikiArticleUseCase cannot be null.");
        this.updatePublishedWikiArticleUseCase = Objects.requireNonNull(updatePublishedWikiArticleUseCase, "UpdatePublishedWikiArticleUseCase cannot be null.");
        this.deleteWikiArticleUseCase = Objects.requireNonNull(deleteWikiArticleUseCase, "DeleteWikiArticleUseCase cannot be null.");
        this.getWikiArticleDetailUseCase = Objects.requireNonNull(getWikiArticleDetailUseCase, "GetWikiArticleDetailUseCase cannot be null.");
    }

    public WikiArticleDTO createDraft(CreateWikiArticleCommand command, WikiCoverUpload upload) {
        Objects.requireNonNull(command, "CreateWikiArticleCommand cannot be null.");

        if (upload == null) {
            return createWikiArticleUseCase.execute(command);
        }

        UUID newAssetId = mediaCoordinator.uploadInitialCover(upload);

        CreateWikiArticleCommand commandWithCover = new CreateWikiArticleCommand(
                command.title(),
                command.articleType(),
                command.summary(),
                command.content(),
                command.editSummary(),
                command.actorId(),
                newAssetId,
                command.coverPositionX(),
                command.coverPositionY()
        );

        try {
            return createWikiArticleUseCase.execute(commandWithCover);
        } catch (RuntimeException ex) {
            mediaCoordinator.compensateInitialCover(newAssetId, ex);
            throw ex;
        }
    }

    public WikiArticleDTO createAndPublish(CreateAndPublishWikiArticleCommand command, WikiCoverUpload upload) {
        Objects.requireNonNull(command, "CreateAndPublishWikiArticleCommand cannot be null.");

        if (upload == null) {
            return createAndPublishWikiArticleUseCase.execute(command);
        }

        UUID newAssetId = mediaCoordinator.uploadInitialCover(upload);

        CreateAndPublishWikiArticleCommand commandWithCover = new CreateAndPublishWikiArticleCommand(
                command.title(),
                command.articleType(),
                command.summary(),
                command.content(),
                command.editSummary(),
                command.actorId(),
                newAssetId,
                command.coverPositionX(),
                command.coverPositionY()
        );

        try {
            return createAndPublishWikiArticleUseCase.execute(commandWithCover);
        } catch (RuntimeException ex) {
            mediaCoordinator.compensateInitialCover(newAssetId, ex);
            throw ex;
        }
    }

    public WikiArticleDTO updateDraft(UpdateDraftWikiArticleCommand command, WikiCoverUpload upload, boolean removeCover) {
        Objects.requireNonNull(command, "UpdateDraftWikiArticleCommand cannot be null.");

        if (removeCover && upload != null) {
            throw new IllegalArgumentException("Không thể đồng thời vừa xóa ảnh bìa vừa tải lên ảnh bìa mới.");
        }

        WikiArticleDTO existing = getWikiArticleDetailUseCase.execute(
                new GetWikiArticleDetailQuery(command.articleId())
        );
        UUID existingCoverId = existing.coverMediaAssetId();

        UUID targetCoverId = existingCoverId;
        boolean isInitialUpload = false;
        UUID newlyCreatedAssetId = null;

        if (removeCover) {
            targetCoverId = null;
        } else if (upload != null) {
            if (existingCoverId == null) {
                newlyCreatedAssetId = mediaCoordinator.uploadInitialCover(upload);
                targetCoverId = newlyCreatedAssetId;
                isInitialUpload = true;
            } else {
                mediaCoordinator.replaceCoverVersion(existingCoverId, upload);
                targetCoverId = existingCoverId;
            }
        }

        Integer targetPositionX = removeCover
                ? 50
                : (command.coverPositionX() != null ? command.coverPositionX() : existing.coverPositionX());
        Integer targetPositionY = removeCover
                ? 50
                : (command.coverPositionY() != null ? command.coverPositionY() : existing.coverPositionY());

        UpdateDraftWikiArticleCommand updateCmd = new UpdateDraftWikiArticleCommand(
                command.articleId(),
                command.title(),
                command.articleType(),
                command.summary(),
                command.content(),
                command.editSummary(),
                command.actorId(),
                targetCoverId,
                targetPositionX,
                targetPositionY,
                true
        );

        WikiArticleDTO updated;
        try {
            updated = updateDraftWikiArticleUseCase.execute(updateCmd);
        } catch (RuntimeException ex) {
            if (isInitialUpload && newlyCreatedAssetId != null) {
                mediaCoordinator.compensateInitialCover(newlyCreatedAssetId, ex);
            }
            throw ex;
        }

        if (removeCover && existingCoverId != null) {
            try {
                mediaCoordinator.deleteCover(existingCoverId);
            } catch (RuntimeException ex) {
                LOGGER.error(
                        "Không thể xóa Media Asset [{}] của ảnh bìa đã gỡ sau khi bài viết [{}] đã commit cập nhật.",
                        existingCoverId,
                        command.articleId(),
                        ex
                );
            }
        }

        return updated;
    }

    public WikiArticleDTO updateDraftAndPublish(UpdateDraftAndPublishWikiArticleCommand command, WikiCoverUpload upload, boolean removeCover) {
        Objects.requireNonNull(command, "UpdateDraftAndPublishWikiArticleCommand cannot be null.");

        if (removeCover && upload != null) {
            throw new IllegalArgumentException("Không thể đồng thời vừa xóa ảnh bìa vừa tải lên ảnh bìa mới.");
        }

        WikiArticleDTO existing = getWikiArticleDetailUseCase.execute(
                new GetWikiArticleDetailQuery(command.articleId())
        );
        UUID existingCoverId = existing.coverMediaAssetId();

        UUID targetCoverId = existingCoverId;
        boolean isInitialUpload = false;
        UUID newlyCreatedAssetId = null;

        if (removeCover) {
            targetCoverId = null;
        } else if (upload != null) {
            if (existingCoverId == null) {
                newlyCreatedAssetId = mediaCoordinator.uploadInitialCover(upload);
                targetCoverId = newlyCreatedAssetId;
                isInitialUpload = true;
            } else {
                mediaCoordinator.replaceCoverVersion(existingCoverId, upload);
                targetCoverId = existingCoverId;
            }
        }

        Integer targetPositionX = removeCover
                ? 50
                : (command.coverPositionX() != null ? command.coverPositionX() : existing.coverPositionX());
        Integer targetPositionY = removeCover
                ? 50
                : (command.coverPositionY() != null ? command.coverPositionY() : existing.coverPositionY());

        UpdateDraftAndPublishWikiArticleCommand updateCmd = new UpdateDraftAndPublishWikiArticleCommand(
                command.articleId(),
                command.title(),
                command.articleType(),
                command.summary(),
                command.content(),
                command.editSummary(),
                command.actorId(),
                targetCoverId,
                targetPositionX,
                targetPositionY,
                true
        );

        WikiArticleDTO updated;
        try {
            updated = updateDraftAndPublishWikiArticleUseCase.execute(updateCmd);
        } catch (RuntimeException ex) {
            if (isInitialUpload && newlyCreatedAssetId != null) {
                mediaCoordinator.compensateInitialCover(newlyCreatedAssetId, ex);
            }
            throw ex;
        }

        if (removeCover && existingCoverId != null) {
            try {
                mediaCoordinator.deleteCover(existingCoverId);
            } catch (RuntimeException ex) {
                LOGGER.error(
                        "Không thể xóa Media Asset [{}] của ảnh bìa đã gỡ sau khi bài viết [{}] đã commit xuất bản.",
                        existingCoverId,
                        command.articleId(),
                        ex
                );
            }
        }

        return updated;
    }

    public WikiArticleDTO updatePublished(UpdatePublishedWikiArticleCommand command, WikiCoverUpload upload, boolean removeCover) {
        Objects.requireNonNull(command, "UpdatePublishedWikiArticleCommand cannot be null.");

        if (removeCover && upload != null) {
            throw new IllegalArgumentException("Không thể đồng thời vừa xóa ảnh bìa vừa tải lên ảnh bìa mới.");
        }

        WikiArticleDTO existing = getWikiArticleDetailUseCase.execute(
                new GetWikiArticleDetailQuery(command.articleId())
        );
        UUID existingCoverId = existing.coverMediaAssetId();

        UUID targetCoverId = existingCoverId;
        boolean isInitialUpload = false;
        UUID newlyCreatedAssetId = null;

        if (removeCover) {
            targetCoverId = null;
        } else if (upload != null) {
            if (existingCoverId == null) {
                newlyCreatedAssetId = mediaCoordinator.uploadInitialCover(upload);
                targetCoverId = newlyCreatedAssetId;
                isInitialUpload = true;
            } else {
                mediaCoordinator.replaceCoverVersion(existingCoverId, upload);
                targetCoverId = existingCoverId;
            }
        }

        Integer targetPositionX = removeCover
                ? 50
                : (command.coverPositionX() != null ? command.coverPositionX() : existing.coverPositionX());
        Integer targetPositionY = removeCover
                ? 50
                : (command.coverPositionY() != null ? command.coverPositionY() : existing.coverPositionY());

        UpdatePublishedWikiArticleCommand updateCmd = new UpdatePublishedWikiArticleCommand(
                command.articleId(),
                command.summary(),
                command.content(),
                command.editSummary(),
                command.actorId(),
                targetCoverId,
                targetPositionX,
                targetPositionY,
                true
        );

        WikiArticleDTO updated;
        try {
            updated = updatePublishedWikiArticleUseCase.execute(updateCmd);
        } catch (RuntimeException ex) {
            if (isInitialUpload && newlyCreatedAssetId != null) {
                mediaCoordinator.compensateInitialCover(newlyCreatedAssetId, ex);
            }
            throw ex;
        }

        if (removeCover && existingCoverId != null) {
            try {
                mediaCoordinator.deleteCover(existingCoverId);
            } catch (RuntimeException ex) {
                LOGGER.error(
                        "Không thể xóa Media Asset [{}] của ảnh bìa đã gỡ sau khi bài viết [{}] đã commit cập nhật.",
                        existingCoverId,
                        command.articleId(),
                        ex
                );
            }
        }

        return updated;
    }

    public void deleteArticle(UUID articleId) {
        Objects.requireNonNull(articleId, "Article ID cannot be null.");

        WikiArticleDTO article = getWikiArticleDetailUseCase.execute(
                new GetWikiArticleDetailQuery(articleId)
        );
        UUID coverAssetId = article.coverMediaAssetId();

        deleteWikiArticleUseCase.execute(new DeleteWikiArticleCommand(articleId));

        if (coverAssetId != null) {
            try {
                mediaCoordinator.deleteCover(coverAssetId);
            } catch (RuntimeException ex) {
                LOGGER.error(
                        "Không thể xóa Media Asset [{}] sau khi bài viết Wiki [{}] đã bị xóa. Asset có thể rơi vào trạng thái ACTIVE mồ côi.",
                        coverAssetId,
                        articleId,
                        ex
                );
            }
        }
    }
}
