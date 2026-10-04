package com.universe.interaction.domain.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteractionReportTest {

    private final UUID reportId = UUID.randomUUID();
    private final UUID commentId = UUID.randomUUID();
    private final UUID reporterUserId = UUID.randomUUID();
    private final UUID resolverUserId = UUID.randomUUID();
    private final Instant now = Instant.now();
    private final String snapshot = "This is a comment snapshot text.";

    @Nested
    @DisplayName("Creation & Invariants")
    class CreationTests {

        @Test
        @DisplayName("Successfully creates PENDING report with valid fields")
        void shouldCreatePendingReport() {
            InteractionReport report = InteractionReport.createPending(
                    reportId,
                    ReportTargetType.COMMENT,
                    commentId,
                    reporterUserId,
                    ReportReason.SPAM,
                    "Spam link detected",
                    snapshot,
                    null,
                    now
            );

            assertThat(report.getId()).isEqualTo(reportId);
            assertThat(report.getTargetType()).isEqualTo(ReportTargetType.COMMENT);
            assertThat(report.getTargetId()).isEqualTo(commentId);
            assertThat(report.getReporterUserId()).isEqualTo(reporterUserId);
            assertThat(report.getReason()).isEqualTo(ReportReason.SPAM);
            assertThat(report.getDescription()).isEqualTo("Spam link detected");
            assertThat(report.getReportedContentSnapshot()).isEqualTo(snapshot);
            assertThat(report.getEvidenceMediaAssetId()).isNull();
            assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
            assertThat(report.getCreatedAt()).isEqualTo(now);
            assertThat(report.getResolvedByUserId()).isNull();
            assertThat(report.getResolvedAt()).isNull();
            assertThat(report.getModerationAction()).isNull();
            assertThat(report.isPending()).isTrue();
            assertThat(report.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("Successfully creates PENDING community post report with evidenceMediaAssetId")
        void shouldCreatePendingPostReportWithEvidenceMediaAssetId() {
            UUID postId = UUID.randomUUID();
            UUID mediaAssetId = UUID.randomUUID();
            InteractionReport report = InteractionReport.createPending(
                    reportId,
                    ReportTargetType.COMMUNITY_POST,
                    postId,
                    reporterUserId,
                    ReportReason.HARASSMENT,
                    "Inappropriate image",
                    snapshot,
                    mediaAssetId,
                    now
            );

            assertThat(report.getTargetType()).isEqualTo(ReportTargetType.COMMUNITY_POST);
            assertThat(report.getTargetId()).isEqualTo(postId);
            assertThat(report.getEvidenceMediaAssetId()).isEqualTo(mediaAssetId);
        }

        @Test
        @DisplayName("Normalizes optional description: blank strings become null for non-OTHER reasons")
        void shouldNormalizeBlankDescriptionToNull() {
            InteractionReport report = InteractionReport.createPending(
                    reportId,
                    ReportTargetType.COMMENT,
                    commentId,
                    reporterUserId,
                    ReportReason.HARASSMENT,
                    "   ",
                    snapshot,
                    null,
                    now
            );

            assertThat(report.getDescription()).isNull();
        }

        @Test
        @DisplayName("Allows null description for non-OTHER reasons")
        void shouldAllowNullDescriptionForNonOther() {
            InteractionReport report = InteractionReport.createPending(
                    reportId,
                    ReportTargetType.COMMENT,
                    commentId,
                    reporterUserId,
                    ReportReason.SPOILER,
                    null,
                    snapshot,
                    null,
                    now
            );

            assertThat(report.getDescription()).isNull();
        }

        @Test
        @DisplayName("Requires non-blank description when reason is OTHER")
        void shouldRequireNonBlankDescriptionForOther() {
            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.OTHER, null, snapshot, null, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Description is required when report reason is OTHER");

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.OTHER, "   ", snapshot, null, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Description is required when report reason is OTHER");

            InteractionReport valid = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.OTHER, "Specific policy violation", snapshot, null, now
            );
            assertThat(valid.getDescription()).isEqualTo("Specific policy violation");
        }

        @Test
        @DisplayName("Enforces maximum description length of 500 characters")
        void shouldEnforceMaxDescriptionLength() {
            String exact500 = "a".repeat(500);
            InteractionReport report = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.OTHER, exact500, snapshot, null, now
            );
            assertThat(report.getDescription()).hasSize(500);

            String over500 = "a".repeat(501);
            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.OTHER, over500, snapshot, null, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Report description exceeds maximum length of 500 characters");
        }

        @Test
        @DisplayName("Rejects null, empty, or whitespace-only snapshot")
        void shouldRejectInvalidSnapshot() {
            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, null, null, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reported content snapshot cannot be null");

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, "   ", null, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reported content snapshot cannot be blank");
        }

        @Test
        @DisplayName("Rejects null mandatory fields")
        void shouldRejectNullMandatoryFields() {
            assertThatThrownBy(() -> InteractionReport.createPending(
                    null, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, null, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, null, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, null, ReportReason.SPAM, null, snapshot, null, now
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, null, null, snapshot, null, now
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, null
            )).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Lifecycle Transitions")
    class LifecycleTests {

        @Test
        @DisplayName("Transitions PENDING to RESOLVED_ACTION_TAKEN")
        void shouldResolveActionTaken() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );

            Instant resolvedAt = now.plus(5, ChronoUnit.MINUTES);
            report.resolveActionTaken(resolverUserId, resolvedAt);

            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
            assertThat(report.getResolvedByUserId()).isEqualTo(resolverUserId);
            assertThat(report.getResolvedAt()).isEqualTo(resolvedAt);
            assertThat(report.getModerationAction()).isEqualTo(ReportModerationAction.DELETE_COMMENT);
            assertThat(report.isPending()).isFalse();
            assertThat(report.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Transitions PENDING to RESOLVED_NO_ACTION")
        void shouldResolveNoAction() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );

            Instant resolvedAt = now.plus(10, ChronoUnit.MINUTES);
            report.resolveNoAction(resolverUserId, resolvedAt);

            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
            assertThat(report.getResolvedByUserId()).isEqualTo(resolverUserId);
            assertThat(report.getResolvedAt()).isEqualTo(resolvedAt);
            assertThat(report.getModerationAction()).isEqualTo(ReportModerationAction.NO_ACTION);
            assertThat(report.isPending()).isFalse();
            assertThat(report.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Transitions COMMUNITY_POST report to RESOLVED_ACTION_TAKEN with CONTENT_HIDDEN")
        void shouldResolveContentHiddenForCommunityPost() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMUNITY_POST, UUID.randomUUID(), reporterUserId, ReportReason.HARASSMENT, null, snapshot, null, now
            );

            Instant resolvedAt = now.plus(5, ChronoUnit.MINUTES);
            report.resolveContentHidden(resolverUserId, resolvedAt);

            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
            assertThat(report.getResolvedByUserId()).isEqualTo(resolverUserId);
            assertThat(report.getResolvedAt()).isEqualTo(resolvedAt);
            assertThat(report.getModerationAction()).isEqualTo(ReportModerationAction.CONTENT_HIDDEN);
            assertThat(report.isPending()).isFalse();
            assertThat(report.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Rejects CONTENT_HIDDEN on COMMENT report and DELETE_COMMENT on COMMUNITY_POST report")
        void shouldEnforceActionTargetTypeConsistency() {
            InteractionReport commentReport = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );
            assertThatThrownBy(() -> commentReport.resolveActionTaken(resolverUserId, now.plusSeconds(60), ReportModerationAction.CONTENT_HIDDEN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CONTENT_HIDDEN action is not supported for target type COMMENT");

            InteractionReport postReport = InteractionReport.createPending(
                    UUID.randomUUID(), ReportTargetType.COMMUNITY_POST, UUID.randomUUID(), reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );
            assertThatThrownBy(() -> postReport.resolveActionTaken(resolverUserId, now.plusSeconds(60), ReportModerationAction.DELETE_COMMENT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DELETE_COMMENT action is not supported for target type COMMUNITY_POST");

            assertThatThrownBy(() -> postReport.resolveActionTaken(resolverUserId, now.plusSeconds(60)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot default resolution action for non-COMMENT target");
        }

        @Test
        @DisplayName("Rejects resolution on already terminal report")
        void shouldRejectResolutionWhenAlreadyTerminal() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );

            report.resolveActionTaken(resolverUserId, now.plusSeconds(60));

            assertThatThrownBy(() -> report.resolveActionTaken(resolverUserId, now.plusSeconds(120)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot resolve report in terminal status");

            assertThatThrownBy(() -> report.resolveNoAction(resolverUserId, now.plusSeconds(120)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Cannot resolve report in terminal status");
        }

        @Test
        @DisplayName("Rejects resolution with null resolverUserId or null resolvedAt")
        void shouldRejectNullResolutionParams() {
            InteractionReport report1 = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );
            assertThatThrownBy(() -> report1.resolveActionTaken(null, now.plusSeconds(60)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Resolver user ID cannot be null");

            InteractionReport report2 = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );
            assertThatThrownBy(() -> report2.resolveActionTaken(resolverUserId, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedAt timestamp cannot be null");
        }

        @Test
        @DisplayName("Rejects resolution when resolvedAt is before createdAt")
        void shouldRejectResolvedAtBeforeCreatedAt() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );

            Instant pastTime = now.minus(1, ChronoUnit.SECONDS);
            assertThatThrownBy(() -> report.resolveActionTaken(resolverUserId, pastTime))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedAt timestamp cannot be before createdAt timestamp");
        }
    }

    @Nested
    @DisplayName("Reconstitution Invariants")
    class ReconstitutionTests {

        @Test
        @DisplayName("Case D: Successfully reconstitutes valid PENDING report with null moderationAction")
        void shouldSuccessfullyReconstituteValidPendingReport() {
            InteractionReport report = InteractionReport.reconstitute(
                    reportId,
                    ReportTargetType.COMMENT,
                    commentId,
                    reporterUserId,
                    ReportReason.SPAM,
                    "Spam comment details",
                    snapshot,
                    null,
                    ReportStatus.PENDING,
                    now,
                    null,
                    null,
                    null,
                    null
            );

            assertThat(report.getId()).isEqualTo(reportId);
            assertThat(report.getTargetType()).isEqualTo(ReportTargetType.COMMENT);
            assertThat(report.getTargetId()).isEqualTo(commentId);
            assertThat(report.getReporterUserId()).isEqualTo(reporterUserId);
            assertThat(report.getReason()).isEqualTo(ReportReason.SPAM);
            assertThat(report.getDescription()).isEqualTo("Spam comment details");
            assertThat(report.getReportedContentSnapshot()).isEqualTo(snapshot);
            assertThat(report.getEvidenceMediaAssetId()).isNull();
            assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
            assertThat(report.getCreatedAt()).isEqualTo(now);
            assertThat(report.getResolvedByUserId()).isNull();
            assertThat(report.getResolvedAt()).isNull();
            assertThat(report.getModerationAction()).isNull();
            assertThat(report.isPending()).isTrue();
            assertThat(report.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("Case E: Rejects PENDING report with DELETE_COMMENT action")
        void shouldRejectPendingWithDeleteComment() {
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    null, ReportStatus.PENDING, now, null, null, ReportModerationAction.DELETE_COMMENT, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ModerationAction must be null for a PENDING report");
        }

        @Test
        @DisplayName("Case F: Rejects PENDING report with NO_ACTION action")
        void shouldRejectPendingWithNoAction() {
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    null, ReportStatus.PENDING, now, null, null, ReportModerationAction.NO_ACTION, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ModerationAction must be null for a PENDING report");
        }

        @Test
        @DisplayName("Case G: Successfully reconstitutes valid RESOLVED_ACTION_TAKEN with DELETE_COMMENT")
        void shouldSuccessfullyReconstituteValidResolvedActionTakenReport() {
            Instant resolvedAt = now.plus(30, ChronoUnit.MINUTES);
            InteractionReport report = InteractionReport.reconstitute(
                    reportId,
                    ReportTargetType.COMMENT,
                    commentId,
                    reporterUserId,
                    ReportReason.HARASSMENT,
                    "Harassment verified",
                    snapshot,
                    null,
                    ReportStatus.RESOLVED_ACTION_TAKEN,
                    now,
                    resolverUserId,
                    resolvedAt,
                    ReportModerationAction.DELETE_COMMENT,
                    null
            );

            assertThat(report.getId()).isEqualTo(reportId);
            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
            assertThat(report.getResolvedByUserId()).isEqualTo(resolverUserId);
            assertThat(report.getResolvedAt()).isEqualTo(resolvedAt);
            assertThat(report.getModerationAction()).isEqualTo(ReportModerationAction.DELETE_COMMENT);
            assertThat(report.isPending()).isFalse();
            assertThat(report.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Case H: Rejects RESOLVED_ACTION_TAKEN report with null moderationAction")
        void shouldRejectActionTakenWithNullModerationAction() {
            Instant resolvedAt = now.plus(30, ChronoUnit.MINUTES);
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.HARASSMENT, null, snapshot,
                    null, ReportStatus.RESOLVED_ACTION_TAKEN, now, resolverUserId, resolvedAt, null, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ModerationAction must be DELETE_COMMENT for RESOLVED_ACTION_TAKEN report");
        }

        @Test
        @DisplayName("Case I: Rejects RESOLVED_ACTION_TAKEN report with NO_ACTION moderationAction")
        void shouldRejectActionTakenWithNoAction() {
            Instant resolvedAt = now.plus(30, ChronoUnit.MINUTES);
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.HARASSMENT, null, snapshot,
                    null, ReportStatus.RESOLVED_ACTION_TAKEN, now, resolverUserId, resolvedAt, ReportModerationAction.NO_ACTION, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ModerationAction must be DELETE_COMMENT for RESOLVED_ACTION_TAKEN report");
        }

        @Test
        @DisplayName("Case J: Successfully reconstitutes valid RESOLVED_NO_ACTION with NO_ACTION")
        void shouldSuccessfullyReconstituteValidResolvedNoActionReport() {
            Instant resolvedAt = now.plus(45, ChronoUnit.MINUTES);
            InteractionReport report = InteractionReport.reconstitute(
                    reportId,
                    ReportTargetType.COMMENT,
                    commentId,
                    reporterUserId,
                    ReportReason.OTHER,
                    "No rule violation found",
                    snapshot,
                    null,
                    ReportStatus.RESOLVED_NO_ACTION,
                    now,
                    resolverUserId,
                    resolvedAt,
                    ReportModerationAction.NO_ACTION,
                    null
            );

            assertThat(report.getId()).isEqualTo(reportId);
            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
            assertThat(report.getResolvedByUserId()).isEqualTo(resolverUserId);
            assertThat(report.getResolvedAt()).isEqualTo(resolvedAt);
            assertThat(report.getModerationAction()).isEqualTo(ReportModerationAction.NO_ACTION);
            assertThat(report.isPending()).isFalse();
            assertThat(report.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Case K: Rejects RESOLVED_NO_ACTION report with null moderationAction")
        void shouldRejectNoActionWithNullModerationAction() {
            Instant resolvedAt = now.plus(45, ChronoUnit.MINUTES);
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.OTHER, "No violation", snapshot,
                    null, ReportStatus.RESOLVED_NO_ACTION, now, resolverUserId, resolvedAt, null, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ModerationAction must be NO_ACTION for RESOLVED_NO_ACTION report");
        }

        @Test
        @DisplayName("Case L: Rejects RESOLVED_NO_ACTION report with DELETE_COMMENT moderationAction")
        void shouldRejectNoActionWithDeleteComment() {
            Instant resolvedAt = now.plus(45, ChronoUnit.MINUTES);
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.OTHER, "No violation", snapshot,
                    null, ReportStatus.RESOLVED_NO_ACTION, now, resolverUserId, resolvedAt, ReportModerationAction.DELETE_COMMENT, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ModerationAction must be NO_ACTION for RESOLVED_NO_ACTION report");
        }

        @Test
        @DisplayName("Rejects PENDING status with non-null resolved fields")
        void shouldRejectPendingWithResolvedFields() {
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    null, ReportStatus.PENDING, now, resolverUserId, null, null, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedByUserId must be null for a PENDING report");

            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    null, ReportStatus.PENDING, now, null, now, null, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedAt must be null for a PENDING report");
        }

        @Test
        @DisplayName("Rejects terminal status with missing resolved fields")
        void shouldRejectTerminalWithMissingResolvedFields() {
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    null, ReportStatus.RESOLVED_ACTION_TAKEN, now, null, now, ReportModerationAction.DELETE_COMMENT, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedByUserId cannot be null for a resolved report");

            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    null, ReportStatus.RESOLVED_NO_ACTION, now, resolverUserId, null, ReportModerationAction.NO_ACTION, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedAt cannot be null for a resolved report");
        }

        @Test
        @DisplayName("Rejects terminal status with resolvedAt before createdAt")
        void shouldRejectTerminalWithResolvedAtBeforeCreatedAt() {
            Instant earlier = now.minusSeconds(10);
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    null, ReportStatus.RESOLVED_ACTION_TAKEN, now, resolverUserId, earlier, ReportModerationAction.DELETE_COMMENT, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedAt timestamp cannot be before createdAt timestamp");
        }
    }

    @Nested
    @DisplayName("Security & Utility")
    class SecurityAndUtilityTests {

        @Test
        @DisplayName("toString() masks description and reportedBodySnapshot to prevent log leakage")
        void shouldMaskSensitiveContentInToString() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.OTHER, "Sensitive user explanation",
                    "Secret leaked text in snapshot", null, now
            );

            String str = report.toString();

            assertThat(str).contains("[PROTECTED]");
            assertThat(str).doesNotContain("Sensitive user explanation");
            assertThat(str).doesNotContain("Secret leaked text in snapshot");
        }

        @Test
        @DisplayName("equals and hashCode adhere to identity contract by id")
        void shouldImplementEqualsAndHashCodeBasedOnId() {
            InteractionReport report1 = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );
            InteractionReport report2 = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, UUID.randomUUID(), ReportReason.HARASSMENT, null, snapshot, null, now
            );
            InteractionReport report3 = InteractionReport.createPending(
                    UUID.randomUUID(), ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );

            assertThat(report1).isEqualTo(report2);
            assertThat(report1.hashCode()).isEqualTo(report2.hashCode());
            assertThat(report1).isNotEqualTo(report3);
        }

        @Test
        @DisplayName("COMMUNITY_POST report rejects resolveActionTaken with DELETE_COMMENT")
        void shouldRejectDeleteCommentActionOnCommunityPostReport() {
            UUID postId = UUID.randomUUID();
            InteractionReport report = InteractionReport.createPending(
                    reportId,
                    ReportTargetType.COMMUNITY_POST,
                    postId,
                    reporterUserId,
                    ReportReason.HARASSMENT,
                    null,
                    "Post caption",
                    null,
                    now
            );

            assertThatThrownBy(() -> report.resolveActionTaken(resolverUserId, now.plusSeconds(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot default resolution action for non-COMMENT target: COMMUNITY_POST");

            assertThatThrownBy(() -> report.resolveActionTaken(resolverUserId, now.plusSeconds(10), ReportModerationAction.DELETE_COMMENT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DELETE_COMMENT action is not supported for target type COMMUNITY_POST");
        }

        @Test
        @DisplayName("COMMUNITY_POST report resolves successfully with resolveNoAction")
        void shouldResolveNoActionOnCommunityPostReport() {
            UUID postId = UUID.randomUUID();
            InteractionReport report = InteractionReport.createPending(
                    reportId,
                    ReportTargetType.COMMUNITY_POST,
                    postId,
                    reporterUserId,
                    ReportReason.HARASSMENT,
                    null,
                    "Post caption",
                    null,
                    now
            );

            Instant resolvedAt = now.plusSeconds(30);
            report.resolveNoAction(resolverUserId, resolvedAt);

            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
            assertThat(report.getModerationAction()).isEqualTo(ReportModerationAction.NO_ACTION);
            assertThat(report.getResolvedByUserId()).isEqualTo(resolverUserId);
            assertThat(report.getResolvedAt()).isEqualTo(resolvedAt);
        }

        @Test
        @DisplayName("markTargetDeleted sets targetDeletedAt timestamp")
        void shouldSetTargetDeletedAtTimestamp() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, ReportTargetType.COMMENT, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null, now
            );
            assertThat(report.getTargetDeletedAt()).isNull();

            Instant deletedAt = now.plusSeconds(60);
            report.markTargetDeleted(deletedAt);

            assertThat(report.getTargetDeletedAt()).isEqualTo(deletedAt);

            assertThatThrownBy(() -> report.markTargetDeleted(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TargetDeletedAt cannot be null");
        }
    }
}
