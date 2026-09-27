package com.universe.wiki.domain.contribution;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Bản ghi bất biến (Append-Only Audit Event) ghi nhận từng bước trong quy trình xử lý đóng góp Wiki.
 */
public record WikiContributionWorkflowEvent(
        UUID id,
        UUID contributionId,
        WikiContributionEventType eventType,
        UUID actorUserId,
        UUID targetUserId,
        WikiContributionStatus fromStatus,
        WikiContributionStatus toStatus,
        Long articleContentVersion,
        WikiContributionResolutionOutcome resolutionOutcome,
        String note,
        Instant createdAt
) {

    public WikiContributionWorkflowEvent {
        Objects.requireNonNull(id, "ID sự kiện không được để trống.");
        Objects.requireNonNull(contributionId, "ID đóng góp không được để trống.");
        Objects.requireNonNull(eventType, "Loại sự kiện không được để trống.");
        Objects.requireNonNull(actorUserId, "ID người thực hiện không được để trống.");
        Objects.requireNonNull(createdAt, "Thời gian tạo sự kiện không được để trống.");

        if (note != null && note.length() > 2000) {
            throw new IllegalArgumentException("Ghi chú sự kiện không được vượt quá 2000 ký tự.");
        }
    }

    public static WikiContributionWorkflowEvent createReviewStarted(
            UUID id,
            UUID contributionId,
            UUID actorUserId,
            Long articleContentVersion,
            Instant now
    ) {
        return new WikiContributionWorkflowEvent(
                id,
                contributionId,
                WikiContributionEventType.REVIEW_STARTED,
                actorUserId,
                null,
                WikiContributionStatus.NEW,
                WikiContributionStatus.REVIEWING,
                articleContentVersion,
                null,
                null,
                now
        );
    }

    public static WikiContributionWorkflowEvent createClaimed(
            UUID id,
            UUID contributionId,
            UUID actorUserId,
            Instant now
    ) {
        return new WikiContributionWorkflowEvent(
                id,
                contributionId,
                WikiContributionEventType.CLAIMED,
                actorUserId,
                null,
                WikiContributionStatus.REVIEWING,
                WikiContributionStatus.REVIEWING,
                null,
                null,
                "Tiếp nhận xử lý đóng góp",
                now
        );
    }

    public static WikiContributionWorkflowEvent createReassigned(
            UUID id,
            UUID contributionId,
            UUID actorUserId,
            UUID targetUserId,
            String reason,
            Instant now
    ) {
        return new WikiContributionWorkflowEvent(
                id,
                contributionId,
                WikiContributionEventType.REASSIGNED,
                actorUserId,
                targetUserId,
                WikiContributionStatus.REVIEWING,
                WikiContributionStatus.REVIEWING,
                null,
                null,
                reason,
                now
        );
    }

    public static WikiContributionWorkflowEvent createReassigned(
            UUID id,
            UUID contributionId,
            UUID actorUserId,
            UUID targetUserId,
            Instant now
    ) {
        return createReassigned(id, contributionId, actorUserId, targetUserId, null, now);
    }

    public static WikiContributionWorkflowEvent createArticleUpdateLinked(
            UUID id,
            UUID contributionId,
            UUID updaterUserId,
            long articleContentVersion,
            String note,
            Instant now
    ) {
        return new WikiContributionWorkflowEvent(
                id,
                contributionId,
                WikiContributionEventType.ARTICLE_UPDATE_LINKED,
                updaterUserId,
                null,
                WikiContributionStatus.REVIEWING,
                WikiContributionStatus.REVIEWING,
                articleContentVersion,
                null,
                note,
                now
        );
    }

    public static WikiContributionWorkflowEvent createResolved(
            UUID id,
            UUID contributionId,
            UUID actorUserId,
            WikiContributionResolutionOutcome outcome,
            Long resolvedArticleContentVersion,
            String resolutionNote,
            Instant now
    ) {
        return new WikiContributionWorkflowEvent(
                id,
                contributionId,
                WikiContributionEventType.RESOLVED,
                actorUserId,
                null,
                WikiContributionStatus.REVIEWING,
                WikiContributionStatus.RESOLVED,
                resolvedArticleContentVersion,
                outcome,
                resolutionNote,
                now
        );
    }

    public static WikiContributionWorkflowEvent createRejected(
            UUID id,
            UUID contributionId,
            UUID actorUserId,
            String rejectionNote,
            Instant now
    ) {
        return new WikiContributionWorkflowEvent(
                id,
                contributionId,
                WikiContributionEventType.REJECTED,
                actorUserId,
                null,
                WikiContributionStatus.REVIEWING,
                WikiContributionStatus.REJECTED,
                null,
                null,
                rejectionNote,
                now
        );
    }

    public static WikiContributionWorkflowEvent create(
            UUID id,
            UUID contributionId,
            WikiContributionEventType eventType,
            UUID actorUserId,
            UUID targetUserId,
            Long articleContentVersion,
            WikiContributionResolutionOutcome resolutionOutcome,
            String note,
            Instant createdAt
    ) {
        WikiContributionStatus fromStatus = resolveDefaultFromStatus(eventType);
        WikiContributionStatus toStatus = resolveDefaultToStatus(eventType);
        return new WikiContributionWorkflowEvent(
                id,
                contributionId,
                eventType,
                actorUserId,
                targetUserId,
                fromStatus,
                toStatus,
                articleContentVersion,
                resolutionOutcome,
                note,
                createdAt
        );
    }

    private static WikiContributionStatus resolveDefaultFromStatus(WikiContributionEventType eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case REVIEW_STARTED -> WikiContributionStatus.NEW;
            case CLAIMED, REASSIGNED, ARTICLE_UPDATE_LINKED, RESOLVED, REJECTED -> WikiContributionStatus.REVIEWING;
        };
    }

    private static WikiContributionStatus resolveDefaultToStatus(WikiContributionEventType eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case REVIEW_STARTED, CLAIMED, REASSIGNED, ARTICLE_UPDATE_LINKED -> WikiContributionStatus.REVIEWING;
            case RESOLVED -> WikiContributionStatus.RESOLVED;
            case REJECTED -> WikiContributionStatus.REJECTED;
        };
    }

    public UUID getId() { return id; }
    public UUID getContributionId() { return contributionId; }
    public WikiContributionEventType getEventType() { return eventType; }
    public UUID getActorId() { return actorUserId; }
    public UUID getActorUserId() { return actorUserId; }
    public UUID getTargetUserId() { return targetUserId; }
    public WikiContributionStatus getFromStatus() { return fromStatus; }
    public WikiContributionStatus getToStatus() { return toStatus; }
    public Long getArticleContentVersion() { return articleContentVersion; }
    public WikiContributionResolutionOutcome getResolutionOutcome() { return resolutionOutcome; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
