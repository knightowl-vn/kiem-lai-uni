package com.universe.interaction.application.mutation;

import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ResolveCommentReportCommand and ReportModerationAction Unit Tests")
class ResolveCommentReportCommandTest {

    @Test
    @DisplayName("Successfully instantiates command with valid arguments")
    void shouldInstantiateWithValidArguments() {
        UUID reportId = UUID.randomUUID();
        UUID moderatorUserId = UUID.randomUUID();
        ReportModerationAction action = ReportModerationAction.DELETE_COMMENT;

        ResolveCommentReportCommand command = new ResolveCommentReportCommand(reportId, moderatorUserId, action);

        assertThat(command.reportId()).isEqualTo(reportId);
        assertThat(command.moderatorUserId()).isEqualTo(moderatorUserId);
        assertThat(command.action()).isEqualTo(ReportModerationAction.DELETE_COMMENT);
    }

    @Test
    @DisplayName("Rejects null reportId")
    void shouldRejectNullReportId() {
        UUID moderatorUserId = UUID.randomUUID();

        assertThatThrownBy(() -> new ResolveCommentReportCommand(null, moderatorUserId, ReportModerationAction.DELETE_COMMENT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Report ID cannot be null.");
    }

    @Test
    @DisplayName("Rejects null moderatorUserId")
    void shouldRejectNullModeratorUserId() {
        UUID reportId = UUID.randomUUID();

        assertThatThrownBy(() -> new ResolveCommentReportCommand(reportId, null, ReportModerationAction.DELETE_COMMENT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Moderator user ID cannot be null.");
    }

    @Test
    @DisplayName("Rejects null action")
    void shouldRejectNullAction() {
        UUID reportId = UUID.randomUUID();
        UUID moderatorUserId = UUID.randomUUID();

        assertThatThrownBy(() -> new ResolveCommentReportCommand(reportId, moderatorUserId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Report moderation action cannot be null.");
    }

    @Test
    @DisplayName("ReportModerationAction contains exactly DELETE_COMMENT and NO_ACTION")
    void shouldContainExpectedEnumValues() {
        assertThat(ReportModerationAction.values()).containsExactly(
                ReportModerationAction.DELETE_COMMENT,
                ReportModerationAction.NO_ACTION
        );
    }

    @Test
    @DisplayName("ReportAlreadyResolvedException constructs with expected code and message")
    void shouldConstructReportAlreadyResolvedException() {
        UUID reportId = UUID.randomUUID();

        ReportAlreadyResolvedException ex1 = new ReportAlreadyResolvedException(reportId);
        assertThat(ex1.getErrorCode()).isEqualTo("REPORT_ALREADY_RESOLVED");
        assertThat(ex1.getMessage()).contains(reportId.toString());

        ReportAlreadyResolvedException ex2 = new ReportAlreadyResolvedException(reportId, ReportStatus.RESOLVED_ACTION_TAKEN);
        assertThat(ex2.getErrorCode()).isEqualTo("REPORT_ALREADY_RESOLVED");
        assertThat(ex2.getMessage()).contains(reportId.toString()).contains("RESOLVED_ACTION_TAKEN");
    }
}
