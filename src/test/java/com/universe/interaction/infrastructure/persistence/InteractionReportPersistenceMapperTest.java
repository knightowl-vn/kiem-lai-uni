package com.universe.interaction.infrastructure.persistence;

import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
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
                commentId,
                reporterUserId,
                ReportReason.SPAM,
                "Link spam in comment",
                snapshot,
                createdAt
        );

        InteractionReportJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(reportId.toString());
        assertThat(entity.getCommentId()).isEqualTo(commentId.toString());
        assertThat(entity.getReporterUserId()).isEqualTo(reporterUserId.toString());
        assertThat(entity.getReason()).isEqualTo("SPAM");
        assertThat(entity.getDescription()).isEqualTo("Link spam in comment");
        assertThat(entity.getReportedBodySnapshot()).isEqualTo(snapshot);
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getResolvedByUserId()).isNull();
        assertThat(entity.getResolvedAt()).isNull();

        InteractionReport reconstituted = mapper.toDomain(entity);

        assertThat(reconstituted.getId()).isEqualTo(domain.getId());
        assertThat(reconstituted.getCommentId()).isEqualTo(domain.getCommentId());
        assertThat(reconstituted.getReporterUserId()).isEqualTo(domain.getReporterUserId());
        assertThat(reconstituted.getReason()).isEqualTo(domain.getReason());
        assertThat(reconstituted.getDescription()).isEqualTo(domain.getDescription());
        assertThat(reconstituted.getReportedBodySnapshot()).isEqualTo(domain.getReportedBodySnapshot());
        assertThat(reconstituted.getStatus()).isEqualTo(domain.getStatus());
        assertThat(reconstituted.getCreatedAt()).isEqualTo(domain.getCreatedAt());
        assertThat(reconstituted.getResolvedByUserId()).isNull();
        assertThat(reconstituted.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("Maps RESOLVED domain report to JPA entity and back with full roundtrip fidelity")
    void shouldRoundTripResolvedReport() {
        InteractionReport domain = InteractionReport.reconstitute(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.OTHER,
                "Explicit rule violation",
                snapshot,
                ReportStatus.RESOLVED_ACTION_TAKEN,
                createdAt,
                resolverUserId,
                resolvedAt
        );

        InteractionReportJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(reportId.toString());
        assertThat(entity.getStatus()).isEqualTo("RESOLVED_ACTION_TAKEN");
        assertThat(entity.getResolvedByUserId()).isEqualTo(resolverUserId.toString());
        assertThat(entity.getResolvedAt()).isEqualTo(resolvedAt);

        InteractionReport reconstituted = mapper.toDomain(entity);

        assertThat(reconstituted.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
        assertThat(reconstituted.getResolvedByUserId()).isEqualTo(resolverUserId);
        assertThat(reconstituted.getResolvedAt()).isEqualTo(resolvedAt);
    }

    @Test
    @DisplayName("Maps RESOLVED_NO_ACTION domain report to JPA entity and back with full roundtrip fidelity")
    void shouldRoundTripResolvedNoActionReport() {
        InteractionReport domain = InteractionReport.reconstitute(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.HARASSMENT,
                "Report dismissed without action",
                snapshot,
                ReportStatus.RESOLVED_NO_ACTION,
                createdAt,
                resolverUserId,
                resolvedAt
        );

        InteractionReportJpaEntity entity = mapper.toJpaEntity(domain);

        assertThat(entity.getId()).isEqualTo(reportId.toString());
        assertThat(entity.getStatus()).isEqualTo("RESOLVED_NO_ACTION");
        assertThat(entity.getResolvedByUserId()).isEqualTo(resolverUserId.toString());
        assertThat(entity.getResolvedAt()).isEqualTo(resolvedAt);

        InteractionReport reconstituted = mapper.toDomain(entity);

        assertThat(reconstituted.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
        assertThat(reconstituted.getResolvedByUserId()).isEqualTo(resolverUserId);
        assertThat(reconstituted.getResolvedAt()).isEqualTo(resolvedAt);
    }

    @Test
    @DisplayName("Verifies a null description survives domain -> JPA -> domain roundtrip intact")
    void shouldRoundTripNullDescriptionAcrossDomainAndJpa() {
        InteractionReport domain = InteractionReport.createPending(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.SPAM,
                null,
                snapshot,
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
    @DisplayName("Throws on corrupt UUID or enum values during toDomain")
    void shouldThrowOnCorruptValuesInEntity() {
        InteractionReportJpaEntity corruptId = new InteractionReportJpaEntity(
                "invalid-uuid", commentId.toString(), reporterUserId.toString(),
                "SPAM", null, snapshot, "PENDING", createdAt, null, null
        );
        assertThatThrownBy(() -> mapper.toDomain(corruptId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid UUID format for Report ID");

        InteractionReportJpaEntity corruptReason = new InteractionReportJpaEntity(
                reportId.toString(), commentId.toString(), reporterUserId.toString(),
                "NON_EXISTENT_REASON", null, snapshot, "PENDING", createdAt, null, null
        );
        assertThatThrownBy(() -> mapper.toDomain(corruptReason))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown report reason");

        InteractionReportJpaEntity corruptStatus = new InteractionReportJpaEntity(
                reportId.toString(), commentId.toString(), reporterUserId.toString(),
                "SPAM", null, snapshot, "UNKNOWN_STATUS", createdAt, null, null
        );
        assertThatThrownBy(() -> mapper.toDomain(corruptStatus))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown report status");
    }
}
