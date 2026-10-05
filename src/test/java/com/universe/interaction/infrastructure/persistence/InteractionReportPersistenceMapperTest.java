package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InteractionReportPersistenceMapperTest {

    private final InteractionReportPersistenceMapper mapper = new InteractionReportPersistenceMapper();

    private final UUID reportId = UUID.randomUUID();
    private final UUID commentId = UUID.randomUUID();
    private final UUID reporterUserId = UUID.randomUUID();
    private final UUID resolverUserId = UUID.randomUUID();
    private final Instant createdAt = Instant.parse("2026-09-19T10:00:00.123456Z");
    private final Instant resolvedAt = createdAt.plus(1, ChronoUnit.HOURS);
    private final String snapshot = "Original comment text";

    @Test
    @DisplayName("Maps PENDING domain report to JPA entity and back with full roundtrip fidelity")
    void shouldRoundTripPendingReport() {
        InteractionReport domain = InteractionReport.createPending(
                reportId,
                ReportTargetType.COMMENT,
                commentId,
                reporterUserId,
                ReportReason.SPAM,
                "Link spam in comment",
                snapshot,
                null,
                createdAt
        );

        InteractionReportJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(reportId.toString());
        assertThat(entity.getTargetType()).isEqualTo("COMMENT");
        assertThat(entity.getTargetId()).isEqualTo(commentId.toString());
        assertThat(entity.getReporterUserId()).isEqualTo(reporterUserId.toString());
        assertThat(entity.getReason()).isEqualTo("SPAM");
        assertThat(entity.getDescription()).isEqualTo("Link spam in comment");
        assertThat(entity.getContentSnapshot()).isEqualTo(snapshot);
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getResolvedByUserId()).isNull();
        assertThat(entity.getResolvedAt()).isNull();
        assertThat(entity.getModerationAction()).isNull();

        InteractionReport reconstituted = mapper.toDomain(entity);

        assertThat(reconstituted.getId()).isEqualTo(domain.getId());
        assertThat(reconstituted.getTargetType()).isEqualTo(domain.getTargetType());
        assertThat(reconstituted.getTargetId()).isEqualTo(domain.getTargetId());
        assertThat(reconstituted.getReporterUserId()).isEqualTo(domain.getReporterUserId());
        assertThat(reconstituted.getReason()).isEqualTo(domain.getReason());
        assertThat(reconstituted.getDescription()).isEqualTo(domain.getDescription());
        assertThat(reconstituted.getReportedContentSnapshot()).isEqualTo(domain.getReportedContentSnapshot());
        assertThat(reconstituted.getStatus()).isEqualTo(domain.getStatus());
        assertThat(reconstituted.getCreatedAt()).isEqualTo(domain.getCreatedAt());
        assertThat(reconstituted.getResolvedByUserId()).isNull();
        assertThat(reconstituted.getResolvedAt()).isNull();
        assertThat(reconstituted.getModerationAction()).isNull();
    }

    @Test
    @DisplayName("Maps RESOLVED_ACTION_TAKEN domain report to JPA entity and back with full roundtrip fidelity")
    void shouldRoundTripResolvedActionTakenReport() {
        InteractionReport domain = InteractionReport.reconstitute(
                reportId,
                ReportTargetType.COMMENT,
                commentId,
                reporterUserId,
                ReportReason.OTHER,
                "Explicit rule violation",
                snapshot,
                null,
                ReportStatus.RESOLVED_ACTION_TAKEN,
                createdAt,
                resolverUserId,
                resolvedAt,
                ReportModerationAction.DELETE_COMMENT,
                null
        );

        InteractionReportJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(reportId.toString());
        assertThat(entity.getStatus()).isEqualTo("RESOLVED_ACTION_TAKEN");
        assertThat(entity.getResolvedByUserId()).isEqualTo(resolverUserId.toString());
        assertThat(entity.getResolvedAt()).isEqualTo(resolvedAt);
        assertThat(entity.getModerationAction()).isEqualTo("DELETE_COMMENT");

        InteractionReport reconstituted = mapper.toDomain(entity);

        assertThat(reconstituted.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
        assertThat(reconstituted.getResolvedByUserId()).isEqualTo(resolverUserId);
        assertThat(reconstituted.getResolvedAt()).isEqualTo(resolvedAt);
        assertThat(reconstituted.getModerationAction()).isEqualTo(ReportModerationAction.DELETE_COMMENT);
    }

    @Test
    @DisplayName("Maps RESOLVED_NO_ACTION domain report to JPA entity and back with full roundtrip fidelity")
    void shouldRoundTripResolvedNoActionReport() {
        InteractionReport domain = InteractionReport.reconstitute(
                reportId,
                ReportTargetType.COMMENT,
                commentId,
                reporterUserId,
                ReportReason.HARASSMENT,
                "Report dismissed without action",
                snapshot,
                null,
                ReportStatus.RESOLVED_NO_ACTION,
                createdAt,
                resolverUserId,
                resolvedAt,
                ReportModerationAction.NO_ACTION,
                null
        );

        InteractionReportJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(reportId.toString());
        assertThat(entity.getStatus()).isEqualTo("RESOLVED_NO_ACTION");
        assertThat(entity.getResolvedByUserId()).isEqualTo(resolverUserId.toString());
        assertThat(entity.getResolvedAt()).isEqualTo(resolvedAt);
        assertThat(entity.getModerationAction()).isEqualTo("NO_ACTION");

        InteractionReport reconstituted = mapper.toDomain(entity);

        assertThat(reconstituted.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
        assertThat(reconstituted.getResolvedByUserId()).isEqualTo(resolverUserId);
        assertThat(reconstituted.getResolvedAt()).isEqualTo(resolvedAt);
        assertThat(reconstituted.getModerationAction()).isEqualTo(ReportModerationAction.NO_ACTION);
    }

    @Test
    @DisplayName("Verifies a null description survives domain -> JPA -> domain roundtrip intact")
    void shouldRoundTripNullDescriptionAcrossDomainAndJpa() {
        InteractionReport domain = InteractionReport.createPending(
                reportId,
                ReportTargetType.COMMENT,
                commentId,
                reporterUserId,
                ReportReason.SPAM,
                null,
                snapshot,
                null,
                createdAt
        );
        assertThat(domain.getDescription()).isNull();

        InteractionReportJpaEntity entity = mapper.toJpaEntity(domain);
        assertThat(entity.getDescription()).isNull();

        InteractionReport reconstituted = mapper.toDomain(entity);
        assertThat(reconstituted.getDescription()).isNull();
    }

    @Test
    @DisplayName("Throws on null input for toJpaEntity and toDomain")
    void shouldThrowOnNullInput() {
        assertThatThrownBy(() -> mapper.toJpaEntity(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Domain report cannot be null");

        assertThatThrownBy(() -> mapper.toDomain(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("InteractionReportJpaEntity cannot be null");
    }

    @Test
    @DisplayName("Throws on corrupt UUID, enum, or moderation action values during toDomain")
    void shouldThrowOnCorruptValuesInEntity() {
        InteractionReportJpaEntity corruptId = new InteractionReportJpaEntity(
                "invalid-uuid", "COMMENT", commentId.toString(), reporterUserId.toString(),
                "SPAM", null, snapshot, "PENDING", createdAt, null, null, null, null
        );
        assertThatThrownBy(() -> mapper.toDomain(corruptId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid UUID format for Report ID");

        InteractionReportJpaEntity corruptReason = new InteractionReportJpaEntity(
                reportId.toString(), "COMMENT", commentId.toString(), reporterUserId.toString(),
                "NON_EXISTENT_REASON", null, snapshot, "PENDING", createdAt, null, null, null, null
        );
        assertThatThrownBy(() -> mapper.toDomain(corruptReason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown report reason");

        InteractionReportJpaEntity corruptStatus = new InteractionReportJpaEntity(
                reportId.toString(), "COMMENT", commentId.toString(), reporterUserId.toString(),
                "SPAM", null, snapshot, "UNKNOWN_STATUS", createdAt, null, null, null, null
        );
        assertThatThrownBy(() -> mapper.toDomain(corruptStatus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown report status");

        InteractionReportJpaEntity corruptModerationAction = new InteractionReportJpaEntity(
                reportId.toString(), "COMMENT", commentId.toString(), reporterUserId.toString(),
                "SPAM", null, snapshot, "RESOLVED_ACTION_TAKEN", createdAt, resolverUserId.toString(), resolvedAt,
                "UNKNOWN_ACTION", null
        );
        assertThatThrownBy(() -> mapper.toDomain(corruptModerationAction))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown report moderation action");
    }

    @Test
    @DisplayName("Throws when entity status and moderation action combination violates domain invariants")
    void shouldThrowWhenEntityStatusAndModerationActionViolateInvariants() {
        // PENDING with non-null moderation action
        InteractionReportJpaEntity pendingWithAction = new InteractionReportJpaEntity(
                reportId.toString(), "COMMENT", commentId.toString(), reporterUserId.toString(),
                "SPAM", null, snapshot, "PENDING", createdAt, null, null, "DELETE_COMMENT", null
        );
        assertThatThrownBy(() -> mapper.toDomain(pendingWithAction))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ModerationAction must be null for a PENDING report.");

        // RESOLVED_ACTION_TAKEN with null action
        InteractionReportJpaEntity actionTakenWithNull = new InteractionReportJpaEntity(
                reportId.toString(), "COMMENT", commentId.toString(), reporterUserId.toString(),
                "SPAM", null, snapshot, "RESOLVED_ACTION_TAKEN", createdAt, resolverUserId.toString(), resolvedAt, null, null
        );
        assertThatThrownBy(() -> mapper.toDomain(actionTakenWithNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ModerationAction must be DELETE_COMMENT for RESOLVED_ACTION_TAKEN report.");

        // RESOLVED_NO_ACTION with wrong action (DELETE_COMMENT)
        InteractionReportJpaEntity noActionWithWrong = new InteractionReportJpaEntity(
                reportId.toString(), "COMMENT", commentId.toString(), reporterUserId.toString(),
                "SPAM", null, snapshot, "RESOLVED_NO_ACTION", createdAt, resolverUserId.toString(), resolvedAt, "DELETE_COMMENT", null
        );
        assertThatThrownBy(() -> mapper.toDomain(noActionWithWrong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ModerationAction must be NO_ACTION for RESOLVED_NO_ACTION report.");
    }

    @Test
    @DisplayName("Maps COMMUNITY_POST target report to JPA entity and back with full roundtrip fidelity")
    void shouldRoundTripCommunityPostReport() {
        UUID postId = UUID.randomUUID();
        InteractionReport domain = InteractionReport.createPending(
                reportId,
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporterUserId,
                ReportReason.HARASSMENT,
                "Inappropriate post caption",
                "Post caption snapshot",
                null,
                createdAt
        );

        InteractionReportJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(reportId.toString());
        assertThat(entity.getTargetType()).isEqualTo("COMMUNITY_POST");
        assertThat(entity.getTargetId()).isEqualTo(postId.toString());
        assertThat(entity.getContentSnapshot()).isEqualTo("Post caption snapshot");

        InteractionReport reconstituted = mapper.toDomain(entity);

        assertThat(reconstituted.getId()).isEqualTo(domain.getId());
        assertThat(reconstituted.getTargetType()).isEqualTo(com.universe.interaction.domain.report.ReportTargetType.COMMUNITY_POST);
        assertThat(reconstituted.getTargetId()).isEqualTo(postId);
        assertThat(reconstituted.getReportedContentSnapshot()).isEqualTo("Post caption snapshot");
        assertThat(reconstituted.getEvidenceMediaAssetId()).isNull();
    }

    @Test
    @DisplayName("Maps COMMUNITY_POST target report with evidenceMediaAssetId to JPA entity and back")
    void shouldRoundTripCommunityPostReportWithEvidenceMediaAssetId() {
        UUID postId = UUID.randomUUID();
        UUID mediaAssetId = UUID.randomUUID();
        InteractionReport domain = InteractionReport.createPending(
                reportId,
                com.universe.interaction.domain.report.ReportTargetType.COMMUNITY_POST,
                postId,
                reporterUserId,
                ReportReason.HARASSMENT,
                "Inappropriate image caption",
                "Post caption snapshot",
                mediaAssetId,
                createdAt
        );

        InteractionReportJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(reportId.toString());
        assertThat(entity.getTargetType()).isEqualTo("COMMUNITY_POST");
        assertThat(entity.getTargetId()).isEqualTo(postId.toString());
        assertThat(entity.getContentSnapshot()).isEqualTo("Post caption snapshot");
        assertThat(entity.getEvidenceMediaAssetId()).isEqualTo(mediaAssetId.toString());

        InteractionReport reconstituted = mapper.toDomain(entity);

        assertThat(reconstituted.getId()).isEqualTo(domain.getId());
        assertThat(reconstituted.getTargetType()).isEqualTo(com.universe.interaction.domain.report.ReportTargetType.COMMUNITY_POST);
        assertThat(reconstituted.getTargetId()).isEqualTo(postId);
        assertThat(reconstituted.getReportedContentSnapshot()).isEqualTo("Post caption snapshot");
        assertThat(reconstituted.getEvidenceMediaAssetId()).isEqualTo(mediaAssetId);
    }
}
