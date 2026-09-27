package com.universe.wiki.application.article.update.published;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.article.common.WikiArticleDTOMapper;
import com.universe.wiki.application.article.cover.WikiCoverIntent;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
import com.universe.wiki.application.exceptions.WikiCoverStaleMutationException;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;
import com.universe.wiki.application.ports.WikiCoverOrphanRepositoryPort;
import com.universe.wiki.contracts.dto.WikiArticleDTO;
import com.universe.wiki.domain.article.WikiArticle;
import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import com.universe.wiki.application.ports.WikiContributionRepositoryPort;
import com.universe.wiki.application.ports.WikiContributionWorkflowEventRepositoryPort;
import com.universe.wiki.domain.contribution.WikiContribution;
import com.universe.wiki.domain.contribution.WikiContributionStatus;
import com.universe.wiki.domain.contribution.WikiContributionWorkflowEvent;

@Service
public class UpdatePublishedWikiArticleUseCase {

    private static final String DEFAULT_EDIT_SUMMARY =
            "Cập nhật nội dung bài viết đã xuất bản";

    private final WikiArticleRepositoryPort articleRepositoryPort;
    private final WikiArticleRevisionRepositoryPort revisionRepositoryPort;
    private final WikiCoverOrphanRepositoryPort orphanRepositoryPort;
    private final WikiContributionRepositoryPort contributionRepositoryPort;
    private final WikiContributionWorkflowEventRepositoryPort workflowEventRepositoryPort;
    private final IdGeneratorPort idGeneratorPort;
    private final ClockPort clockPort;

    public UpdatePublishedWikiArticleUseCase(
            WikiArticleRepositoryPort articleRepositoryPort,
            WikiArticleRevisionRepositoryPort revisionRepositoryPort,
            WikiCoverOrphanRepositoryPort orphanRepositoryPort,
            WikiContributionRepositoryPort contributionRepositoryPort,
            WikiContributionWorkflowEventRepositoryPort workflowEventRepositoryPort,
            IdGeneratorPort idGeneratorPort,
            ClockPort clockPort
    ) {
        this.articleRepositoryPort = Objects.requireNonNull(articleRepositoryPort, "WikiArticleRepositoryPort không được để trống.");
        this.revisionRepositoryPort = Objects.requireNonNull(revisionRepositoryPort, "WikiArticleRevisionRepositoryPort không được để trống.");
        this.orphanRepositoryPort = Objects.requireNonNull(orphanRepositoryPort, "WikiCoverOrphanRepositoryPort không được để trống.");
        this.contributionRepositoryPort = Objects.requireNonNull(contributionRepositoryPort, "WikiContributionRepositoryPort không được để trống.");
        this.workflowEventRepositoryPort = Objects.requireNonNull(workflowEventRepositoryPort, "WikiContributionWorkflowEventRepositoryPort không được để trống.");
        this.idGeneratorPort = Objects.requireNonNull(idGeneratorPort, "IdGeneratorPort không được để trống.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort không được để trống.");
    }

    @Transactional
    public WikiArticleDTO execute(
            UpdatePublishedWikiArticleCommand command
    ) {
        Objects.requireNonNull(
                command,
                "Update published wiki article command "
                        + "không được để trống."
        );

        UUID articleId =
                Objects.requireNonNull(
                        command.articleId(),
                        "Article ID không được để trống."
                );

        WikiArticle article =
                articleRepositoryPort
                        .findById(articleId)
                        .orElseThrow(() ->
                                new WikiArticleNotFoundException(
                                        articleId
                                )
                        );

        if (command.sourceContributionId() != null) {
            WikiContribution contribution = contributionRepositoryPort.findById(command.sourceContributionId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đóng góp được liên kết."));
            if (!contribution.getArticleId().equals(articleId)) {
                throw new IllegalArgumentException("Đóng góp được liên kết không thuộc bài viết này.");
            }
            if (contribution.getStatus() != WikiContributionStatus.REVIEWING) {
                throw new IllegalStateException("Đóng góp được liên kết phải đang trong trạng thái xem xét (REVIEWING).");
            }
            if (contribution.getAssignedToUserId() == null) {
                throw new IllegalStateException("Đóng góp được liên kết chưa có người phụ trách xử lý. Vui lòng tiếp nhận (claim) trước khi chỉnh sửa bài viết.");
            }
        }

        Instant now =
                clockPort.now();

        UUID previousCoverId =
                article.getCoverMediaAssetId();

        UUID targetCoverMediaAssetId;
        Integer targetCoverPositionX;
        Integer targetCoverPositionY;

        WikiCoverIntent intent = command.coverIntent() != null
                ? command.coverIntent()
                : (!command.updateCover()
                        ? WikiCoverIntent.PRESERVE
                        : (command.coverMediaAssetId() == null ? WikiCoverIntent.REMOVE : WikiCoverIntent.ATTACH_NEW_ASSET));

        switch (intent) {
            case PRESERVE -> {
                targetCoverMediaAssetId = article.getCoverMediaAssetId();
                targetCoverPositionX = article.getCoverPositionX();
                targetCoverPositionY = article.getCoverPositionY();
            }
            case REMOVE -> {
                targetCoverMediaAssetId = null;
                targetCoverPositionX = 50;
                targetCoverPositionY = 50;
            }
            case FOCAL_ONLY -> {
                targetCoverMediaAssetId = article.getCoverMediaAssetId();
                if (targetCoverMediaAssetId == null) {
                    targetCoverPositionX = 50;
                    targetCoverPositionY = 50;
                } else {
                    targetCoverPositionX = command.coverPositionX() != null ? command.coverPositionX() : article.getCoverPositionX();
                    targetCoverPositionY = command.coverPositionY() != null ? command.coverPositionY() : article.getCoverPositionY();
                }
            }
            case REPLACE_EXISTING_BINARY -> {
                UUID expectedCoverId = command.expectedCoverMediaAssetId();
                if (!Objects.equals(article.getCoverMediaAssetId(), expectedCoverId)) {
                    throw new WikiCoverStaleMutationException(
                            "Ảnh bìa của bài viết đã bị thay đổi đồng thời trong lúc tải ảnh mới: " + article.getId());
                }
                targetCoverMediaAssetId = command.coverMediaAssetId();
                targetCoverPositionX = command.coverPositionX() != null ? command.coverPositionX() : article.getCoverPositionX();
                targetCoverPositionY = command.coverPositionY() != null ? command.coverPositionY() : article.getCoverPositionY();
            }
            case ATTACH_NEW_ASSET -> {
                targetCoverMediaAssetId = command.coverMediaAssetId();
                targetCoverPositionX = command.coverPositionX() != null ? command.coverPositionX() : 50;
                targetCoverPositionY = command.coverPositionY() != null ? command.coverPositionY() : 50;
            }
            default -> throw new IllegalStateException("Unknown cover intent: " + intent);
        }

        if (targetCoverMediaAssetId != null && !Objects.equals(previousCoverId, targetCoverMediaAssetId)) {
            orphanRepositoryPort.coordinateCoverAttachment(targetCoverMediaAssetId);
        }

        boolean changed =
                article.updatePublishedContent(
                        command.summary(),
                        command.content(),
                        targetCoverMediaAssetId,
                        targetCoverPositionX,
                        targetCoverPositionY,
                        command.actorId(),
                        now
                );

        UUID finalCoverId =
                article.getCoverMediaAssetId();

        if (!Objects.equals(previousCoverId, finalCoverId)) {
            lockCoverReferenceKeys(previousCoverId, finalCoverId);
        }

        if (changed) {
            articleRepositoryPort.save(
                    article
            );

            articleRepositoryPort.flush();

            saveRevision(
                    article,
                    resolveEditSummary(
                            command.editSummary()
                    ),
                    command.sourceContributionId()
            );
        }

        reconcileCoverOrphanState(
                previousCoverId,
                finalCoverId
        );

        return WikiArticleDTOMapper.toDTO(
                article
        );
    }

    private void saveRevision(
            WikiArticle article,
            String editSummary,
            UUID sourceContributionId
    ) {
        UUID revisionId =
                idGeneratorPort.generate();

        WikiArticleRevision revision =
                WikiArticleRevision.createSnapshot(
                        revisionId,
                        article,
                        RevisionChangeType.UPDATE_PUBLISHED,
                        editSummary,
                        sourceContributionId
                );

        revisionRepositoryPort.save(
                revision
        );

        if (sourceContributionId != null) {
            WikiContributionWorkflowEvent event = WikiContributionWorkflowEvent.createArticleUpdateLinked(
                    idGeneratorPort.generate(),
                    sourceContributionId,
                    article.getUpdatedBy(),
                    article.getContentVersion(),
                    editSummary,
                    clockPort.now()
            );
            workflowEventRepositoryPort.save(event);
        }
    }

    private String resolveEditSummary(
            String editSummary
    ) {
        if (editSummary == null
                || editSummary.isBlank()) {

            return DEFAULT_EDIT_SUMMARY;
        }

        return editSummary.trim();
    }

    private void lockCoverReferenceKeys(UUID... assetIds) {
        Stream.of(assetIds)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(Comparator.comparing(UUID::toString))
                .forEach(articleRepositoryPort::lockCoverReferenceKey);
    }

    private void reconcileCoverOrphanState(
            UUID previousCoverId,
            UUID finalCoverId
    ) {
        if (previousCoverId != null && !previousCoverId.equals(finalCoverId)) {
            if (!articleRepositoryPort.hasCoverReference(previousCoverId)) {
                orphanRepositoryPort.recordOrphanObservation(
                        previousCoverId,
                        clockPort.now()
                );
            } else {
                orphanRepositoryPort.deleteByMediaAssetId(previousCoverId);
            }
        }
        if (finalCoverId != null) {
            orphanRepositoryPort.deleteByMediaAssetId(finalCoverId);
        }
    }
}