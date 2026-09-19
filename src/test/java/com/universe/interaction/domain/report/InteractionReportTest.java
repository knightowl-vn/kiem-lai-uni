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
                    commentId,
                    reporterUserId,
                    ReportReason.SPAM,
                    "Spam link detected",
                    snapshot,
                    now
            );

            assertThat(report.getId()).isEqualTo(reportId);
            assertThat(report.getCommentId()).isEqualTo(commentId);
            assertThat(report.getReporterUserId()).isEqualTo(reporterUserId);
            assertThat(report.getReason()).isEqualTo(ReportReason.SPAM);
            assertThat(report.getDescription()).isEqualTo("Spam link detected");
            assertThat(report.getReportedBodySnapshot()).isEqualTo(snapshot);
            assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
            assertThat(report.getCreatedAt()).isEqualTo(now);
            assertThat(report.getResolvedByUserId()).isNull();
            assertThat(report.getResolvedAt()).isNull();
            assertThat(report.isPending()).isTrue();
            assertThat(report.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("Normalizes optional description: blank strings become null for non-OTHER reasons")
        void shouldNormalizeBlankDescriptionToNull() {
            InteractionReport report = InteractionReport.createPending(
                    reportId,
                    commentId,
                    reporterUserId,
                    ReportReason.HARASSMENT,
                    "   ",
                    snapshot,
                    now
            );

            assertThat(report.getDescription()).isNull();
        }

        @Test
        @DisplayName("Allows null description for non-OTHER reasons")
        void shouldAllowNullDescriptionForNonOther() {
            InteractionReport report = InteractionReport.createPending(
                    reportId,
                    commentId,
                    reporterUserId,
                    ReportReason.SPOILER,
                    null,
                    snapshot,
                    now
            );

            assertThat(report.getDescription()).isNull();
        }

        @Test
        @DisplayName("Requires non-blank description when reason is OTHER")
        void shouldRequireNonBlankDescriptionForOther() {
            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.OTHER, null, snapshot, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Description is required when report reason is OTHER");

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.OTHER, "   ", snapshot, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Description is required when report reason is OTHER");

            InteractionReport valid = InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.OTHER, "Specific policy violation", snapshot, now
            );
            assertThat(valid.getDescription()).isEqualTo("Specific policy violation");
        }

        @Test
        @DisplayName("Enforces maximum description length of 500 characters")
        void shouldEnforceMaxDescriptionLength() {
            String exact500 = "a".repeat(500);
            InteractionReport report = InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.OTHER, exact500, snapshot, now
            );
            assertThat(report.getDescription()).hasSize(500);

            String over500 = "a".repeat(501);
            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.OTHER, over500, snapshot, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Report description exceeds maximum length of 500 characters");
        }

        @Test
        @DisplayName("Rejects null, empty, or whitespace-only snapshot")
        void shouldRejectInvalidSnapshot() {
            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, null, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reported body snapshot cannot be null");

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, "   ", now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Reported body snapshot cannot be blank");
        }

        @Test
        @DisplayName("Rejects null mandatory fields")
        void shouldRejectNullMandatoryFields() {
            assertThatThrownBy(() -> InteractionReport.createPending(
                    null, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, now
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, null, reporterUserId, ReportReason.SPAM, null, snapshot, now
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, commentId, null, ReportReason.SPAM, null, snapshot, now
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, commentId, reporterUserId, null, null, snapshot, now
            )).isInstanceOf(NullPointerException.class);

            assertThatThrownBy(() -> InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, null
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
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, now
            );

            Instant resolvedAt = now.plus(5, ChronoUnit.MINUTES);
            report.resolveActionTaken(resolverUserId, resolvedAt);

            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
            assertThat(report.getResolvedByUserId()).isEqualTo(resolverUserId);
            assertThat(report.getResolvedAt()).isEqualTo(resolvedAt);
            assertThat(report.isPending()).isFalse();
            assertThat(report.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Transitions PENDING to RESOLVED_NO_ACTION")
        void shouldResolveNoAction() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, now
            );

            Instant resolvedAt = now.plus(10, ChronoUnit.MINUTES);
            report.resolveNoAction(resolverUserId, resolvedAt);

            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
            assertThat(report.getResolvedByUserId()).isEqualTo(resolverUserId);
            assertThat(report.getResolvedAt()).isEqualTo(resolvedAt);
            assertThat(report.isPending()).isFalse();
            assertThat(report.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Rejects resolution on already terminal report")
        void shouldRejectResolutionWhenAlreadyTerminal() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, now
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
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, now
            );
            assertThatThrownBy(() -> report1.resolveActionTaken(null, now.plusSeconds(60)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Resolver user ID cannot be null");

            InteractionReport report2 = InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, now
            );
            assertThatThrownBy(() -> report2.resolveActionTaken(resolverUserId, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedAt timestamp cannot be null");
        }

        @Test
        @DisplayName("Rejects resolution when resolvedAt is before createdAt")
        void shouldRejectResolvedAtBeforeCreatedAt() {
            InteractionReport report = InteractionReport.createPending(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, now
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
        @DisplayName("Successfully reconstitutes valid PENDING report")
        void shouldSuccessfullyReconstituteValidPendingReport() {
            InteractionReport report = InteractionReport.reconstitute(
                    reportId,
                    commentId,
                    reporterUserId,
                    ReportReason.SPAM,
                    "Spam comment details",
                    snapshot,
                    ReportStatus.PENDING,
                    now,
                    null,
                    null
            );

            assertThat(report.getId()).isEqualTo(reportId);
            assertThat(report.getCommentId()).isEqualTo(commentId);
            assertThat(report.getReporterUserId()).isEqualTo(reporterUserId);
            assertThat(report.getReason()).isEqualTo(ReportReason.SPAM);
            assertThat(report.getDescription()).isEqualTo("Spam comment details");
            assertThat(report.getReportedBodySnapshot()).isEqualTo(snapshot);
            assertThat(report.getStatus()).isEqualTo(ReportStatus.PENDING);
            assertThat(report.getCreatedAt()).isEqualTo(now);
            assertThat(report.getResolvedByUserId()).isNull();
            assertThat(report.getResolvedAt()).isNull();
            assertThat(report.isPending()).isTrue();
            assertThat(report.isTerminal()).isFalse();
        }

        @Test
        @DisplayName("Successfully reconstitutes valid RESOLVED_ACTION_TAKEN report")
        void shouldSuccessfullyReconstituteValidResolvedActionTakenReport() {
            Instant resolvedAt = now.plus(30, ChronoUnit.MINUTES);
            InteractionReport report = InteractionReport.reconstitute(
                    reportId,
                    commentId,
                    reporterUserId,
                    ReportReason.HARASSMENT,
                    "Harassment verified",
                    snapshot,
                    ReportStatus.RESOLVED_ACTION_TAKEN,
                    now,
                    resolverUserId,
                    resolvedAt
            );

            assertThat(report.getId()).isEqualTo(reportId);
            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
            assertThat(report.getResolvedByUserId()).isEqualTo(resolverUserId);
            assertThat(report.getResolvedAt()).isEqualTo(resolvedAt);
            assertThat(report.isPending()).isFalse();
            assertThat(report.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Successfully reconstitutes valid RESOLVED_NO_ACTION report")
        void shouldSuccessfullyReconstituteValidResolvedNoActionReport() {
            Instant resolvedAt = now.plus(45, ChronoUnit.MINUTES);
            InteractionReport report = InteractionReport.reconstitute(
                    reportId,
                    commentId,
                    reporterUserId,
                    ReportReason.OTHER,
                    "No rule violation found",
                    snapshot,
                    ReportStatus.RESOLVED_NO_ACTION,
                    now,
                    resolverUserId,
                    resolvedAt
            );

            assertThat(report.getId()).isEqualTo(reportId);
            assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
            assertThat(report.getResolvedByUserId()).isEqualTo(resolverUserId);
            assertThat(report.getResolvedAt()).isEqualTo(resolvedAt);
            assertThat(report.isPending()).isFalse();
            assertThat(report.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("Rejects PENDING status with non-null resolved fields")
        void shouldRejectPendingWithResolvedFields() {
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    ReportStatus.PENDING, now, resolverUserId, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedByUserId must be null for a PENDING report");

            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    ReportStatus.PENDING, now, null, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedAt must be null for a PENDING report");
        }

        @Test
        @DisplayName("Rejects terminal status with missing resolved fields")
        void shouldRejectTerminalWithMissingResolvedFields() {
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    ReportStatus.RESOLVED_ACTION_TAKEN, now, null, now
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedByUserId cannot be null for a resolved report");

            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    ReportStatus.RESOLVED_NO_ACTION, now, resolverUserId, null
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ResolvedAt cannot be null for a resolved report");
        }

        @Test
        @DisplayName("Rejects terminal status with resolvedAt before createdAt")
        void shouldRejectTerminalWithResolvedAtBeforeCreatedAt() {
            Instant earlier = now.minusSeconds(10);
            assertThatThrownBy(() -> InteractionReport.reconstitute(
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot,
                    ReportStatus.RESOLVED_ACTION_TAKEN, now, resolverUserId, earlier
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
                    reportId, commentId, reporterUserId, ReportReason.OTHER, "Sensitive user explanation",
                    "Secret leaked text in snapshot", now
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
                    reportId, commentId, reporterUserId, ReportReason.SPAM, null, snapshot, now
            );
            InteractionReport report2 = InteractionReport.createPending(
                    reportId, commentId, UUID.randomUUID(), ReportReason.HARASSMENT, null, snapshot, now
            );
            InteractionReport report3 = InteractionReport.createPending(
                    UUID.randomUUID(), commentId, reporterUserId, ReportReason.SPAM, null, snapshot, now
            );

            assertThat(report1).isEqualTo(report2);
            assertThat(report1.hashCode()).isEqualTo(report2.hashCode());
            assertThat(report1).isNotEqualTo(report3);
        }
    }
}
