package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.exceptions.UnsupportedReportModerationActionException;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;
import com.universe.shared.time.ClockPort;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResolveInteractionReportUseCase Unit Tests")
class ResolveInteractionReportUseCaseTest {

    @Mock
    private InteractionReportRepositoryPort reportRepositoryPort;

    @Mock
    private ClockPort clockPort;

    private ResolveInteractionReportUseCase useCase;

    private final UUID reportId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final UUID reporterUserId = UUID.randomUUID();
    private final UUID moderatorUserId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-03T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new ResolveInteractionReportUseCase(reportRepositoryPort, clockPort);
    }

    private InteractionReport createPendingPostReport() {
        return InteractionReport.createPending(
                reportId,
                ReportTargetType.COMMUNITY_POST,
                targetId,
                reporterUserId,
                ReportReason.SPAM,
                "Description",
                "Snapshot content",
                null,
                now.minusSeconds(60)
        );
    }

    private InteractionReport createPendingCommentReport() {
        return InteractionReport.createPending(
                reportId,
                ReportTargetType.COMMENT,
                targetId,
                reporterUserId,
                ReportReason.SPAM,
                "Description",
                "Snapshot content",
                null,
                now.minusSeconds(60)
        );
    }

    @Test
    @DisplayName("Resolves report with NO_ACTION successfully")
    void shouldResolveReportWithNoAction() {
        InteractionReport report = createPendingPostReport();
        when(reportRepositoryPort.findByIdForUpdate(reportId)).thenReturn(Optional.of(report));
        when(clockPort.now()).thenReturn(now);

        ResolveInteractionReportCommand command = new ResolveInteractionReportCommand(
                reportId, moderatorUserId, ReportModerationAction.NO_ACTION
        );

        useCase.execute(command);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_NO_ACTION);
        assertThat(report.getModerationAction()).isEqualTo(ReportModerationAction.NO_ACTION);
        assertThat(report.getResolvedByUserId()).isEqualTo(moderatorUserId);
        assertThat(report.getResolvedAt()).isEqualTo(now);

        verify(reportRepositoryPort).save(report);
    }

    @Test
    @DisplayName("Resolves COMMUNITY_POST report with CONTENT_HIDDEN successfully")
    void shouldResolveCommunityPostReportWithContentHidden() {
        InteractionReport report = createPendingPostReport();
        when(reportRepositoryPort.findByIdForUpdate(reportId)).thenReturn(Optional.of(report));
        when(clockPort.now()).thenReturn(now);

        ResolveInteractionReportCommand command = new ResolveInteractionReportCommand(
                reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN
        );

        useCase.execute(command);

        assertThat(report.getStatus()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
        assertThat(report.getModerationAction()).isEqualTo(ReportModerationAction.CONTENT_HIDDEN);
        assertThat(report.getResolvedByUserId()).isEqualTo(moderatorUserId);
        assertThat(report.getResolvedAt()).isEqualTo(now);

        verify(reportRepositoryPort).save(report);
    }

    @Test
    @DisplayName("Rejects CONTENT_HIDDEN if report target is not COMMUNITY_POST")
    void shouldRejectContentHiddenForNonCommunityPost() {
        InteractionReport report = createPendingCommentReport();
        when(reportRepositoryPort.findByIdForUpdate(reportId)).thenReturn(Optional.of(report));

        ResolveInteractionReportCommand command = new ResolveInteractionReportCommand(
                reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(UnsupportedReportModerationActionException.class);

        verify(reportRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Throws InteractionReportNotFoundException when report does not exist")
    void shouldThrowWhenReportNotFound() {
        when(reportRepositoryPort.findByIdForUpdate(reportId)).thenReturn(Optional.empty());

        ResolveInteractionReportCommand command = new ResolveInteractionReportCommand(
                reportId, moderatorUserId, ReportModerationAction.NO_ACTION
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(InteractionReportNotFoundException.class);

        verify(reportRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Throws ReportAlreadyResolvedException when report is not pending")
    void shouldThrowWhenReportAlreadyResolved() {
        InteractionReport report = InteractionReport.reconstitute(
                reportId, ReportTargetType.COMMUNITY_POST, targetId, reporterUserId,
                ReportReason.SPAM, "Desc", "Content", null,
                ReportStatus.RESOLVED_NO_ACTION, now.minusSeconds(100),
                moderatorUserId, now.minusSeconds(10), ReportModerationAction.NO_ACTION, null
        );
        when(reportRepositoryPort.findByIdForUpdate(reportId)).thenReturn(Optional.of(report));

        ResolveInteractionReportCommand command = new ResolveInteractionReportCommand(
                reportId, moderatorUserId, ReportModerationAction.NO_ACTION
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ReportAlreadyResolvedException.class);

        verify(reportRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Command rejects null arguments and invalid actions")
    void commandValidations() {
        assertThatThrownBy(() -> new ResolveInteractionReportCommand(null, moderatorUserId, ReportModerationAction.NO_ACTION))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResolveInteractionReportCommand(reportId, null, ReportModerationAction.NO_ACTION))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResolveInteractionReportCommand(reportId, moderatorUserId, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResolveInteractionReportCommand(reportId, moderatorUserId, ReportModerationAction.DELETE_COMMENT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
