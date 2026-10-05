package com.universe.community.entry.admin;

import com.universe.community.application.command.HideCommunityPostCommand;
import com.universe.community.application.usecase.HideCommunityPostUseCase;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.exceptions.ReportAlreadyResolvedException;
import com.universe.interaction.application.exceptions.UnsupportedReportModerationActionException;
import com.universe.interaction.application.mutation.ResolveInteractionReportCommand;
import com.universe.interaction.application.mutation.ResolveInteractionReportUseCase;
import com.universe.interaction.application.query.GetInteractionReportDetailUseCase;
import com.universe.interaction.application.query.InteractionReportDetailResult;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.domain.report.ReportTargetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResolveCommunityPostReportUseCase Tests (Entry Coordinator)")
class ResolveCommunityPostReportUseCaseTest {

    @Mock
    private GetInteractionReportDetailUseCase getInteractionReportDetailUseCase;

    @Mock
    private HideCommunityPostUseCase hideCommunityPostUseCase;

    @Mock
    private ResolveInteractionReportUseCase resolveInteractionReportUseCase;

    private ResolveCommunityPostReportUseCase useCase;

    private final UUID reportId = UUID.randomUUID();
    private final UUID postId = UUID.randomUUID();
    private final UUID moderatorUserId = UUID.randomUUID();
    private final UUID reporterUserId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-03T12:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new ResolveCommunityPostReportUseCase(
                getInteractionReportDetailUseCase,
                hideCommunityPostUseCase,
                resolveInteractionReportUseCase
        );
    }

    private InteractionReportDetailResult createPostReportDetail(ReportStatus status) {
        return new InteractionReportDetailResult(
                reportId,
                ReportTargetType.COMMUNITY_POST,
                postId,
                reporterUserId,
                ReportReason.SPAM,
                "Description",
                "Snapshot caption",
                status,
                now.minusSeconds(60),
                null,
                null,
                null,
                null,
                false,
                null, null, null, null, null, null, null, null, null
        );
    }

    private InteractionReportDetailResult createCommentReportDetail() {
        return new InteractionReportDetailResult(
                reportId,
                ReportTargetType.COMMENT,
                UUID.randomUUID(),
                reporterUserId,
                ReportReason.SPAM,
                "Description",
                "Comment body",
                ReportStatus.PENDING,
                now.minusSeconds(60),
                null,
                null,
                null,
                null,
                false,
                null, null, null, null, null, null, null, null, null
        );
    }

    @Test
    @DisplayName("Resolves report with NO_ACTION: delegates to interaction mutation, skips community post hide")
    void shouldResolveReportWithNoAction() {
        ResolveCommunityPostReportCommand command = new ResolveCommunityPostReportCommand(
                reportId, moderatorUserId, ReportModerationAction.NO_ACTION, "Dismiss report"
        );

        useCase.execute(command);

        ArgumentCaptor<ResolveInteractionReportCommand> captor = ArgumentCaptor.forClass(ResolveInteractionReportCommand.class);
        verify(resolveInteractionReportUseCase).execute(captor.capture());
        assertThat(captor.getValue().reportId()).isEqualTo(reportId);
        assertThat(captor.getValue().moderatorUserId()).isEqualTo(moderatorUserId);
        assertThat(captor.getValue().action()).isEqualTo(ReportModerationAction.NO_ACTION);

        verifyNoInteractions(getInteractionReportDetailUseCase);
        verifyNoInteractions(hideCommunityPostUseCase);
    }

    @Test
    @DisplayName("Resolves report with CONTENT_HIDDEN: discovers target, hides post first, resolves report second")
    void shouldResolveReportWithContentHidden() {
        when(getInteractionReportDetailUseCase.execute(reportId))
                .thenReturn(createPostReportDetail(ReportStatus.PENDING));

        ResolveCommunityPostReportCommand command = new ResolveCommunityPostReportCommand(
                reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN, "Violates policy"
        );

        useCase.execute(command);

        InOrder inOrder = inOrder(getInteractionReportDetailUseCase, hideCommunityPostUseCase, resolveInteractionReportUseCase);
        inOrder.verify(getInteractionReportDetailUseCase).execute(reportId);

        ArgumentCaptor<HideCommunityPostCommand> hideCaptor = ArgumentCaptor.forClass(HideCommunityPostCommand.class);
        inOrder.verify(hideCommunityPostUseCase).execute(hideCaptor.capture());
        assertThat(hideCaptor.getValue().postId()).isEqualTo(postId);
        assertThat(hideCaptor.getValue().moderatorUserId()).isEqualTo(moderatorUserId);
        assertThat(hideCaptor.getValue().reason()).isEqualTo("Violates policy");

        ArgumentCaptor<ResolveInteractionReportCommand> resolveCaptor = ArgumentCaptor.forClass(ResolveInteractionReportCommand.class);
        inOrder.verify(resolveInteractionReportUseCase).execute(resolveCaptor.capture());
        assertThat(resolveCaptor.getValue().reportId()).isEqualTo(reportId);
        assertThat(resolveCaptor.getValue().moderatorUserId()).isEqualTo(moderatorUserId);
        assertThat(resolveCaptor.getValue().action()).isEqualTo(ReportModerationAction.CONTENT_HIDDEN);
    }

    @Test
    @DisplayName("Rejects CONTENT_HIDDEN if target type is not COMMUNITY_POST")
    void shouldRejectContentHiddenForNonCommunityPost() {
        when(getInteractionReportDetailUseCase.execute(reportId))
                .thenReturn(createCommentReportDetail());

        ResolveCommunityPostReportCommand command = new ResolveCommunityPostReportCommand(
                reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN, null
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(UnsupportedReportModerationActionException.class);

        verifyNoInteractions(hideCommunityPostUseCase);
        verifyNoInteractions(resolveInteractionReportUseCase);
    }

    @Test
    @DisplayName("Rejects CONTENT_HIDDEN if report is already in terminal status")
    void shouldRejectContentHiddenIfAlreadyResolved() {
        when(getInteractionReportDetailUseCase.execute(reportId))
                .thenReturn(createPostReportDetail(ReportStatus.RESOLVED_ACTION_TAKEN));

        ResolveCommunityPostReportCommand command = new ResolveCommunityPostReportCommand(
                reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN, null
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ReportAlreadyResolvedException.class);

        verifyNoInteractions(hideCommunityPostUseCase);
        verifyNoInteractions(resolveInteractionReportUseCase);
    }

    @Test
    @DisplayName("Propagates CommunityPostNotFoundException from HideCommunityPostUseCase")
    void shouldPropagateCommunityPostNotFoundException() {
        when(getInteractionReportDetailUseCase.execute(reportId))
                .thenReturn(createPostReportDetail(ReportStatus.PENDING));
        doThrow(new CommunityPostNotFoundException(postId))
                .when(hideCommunityPostUseCase).execute(any());

        ResolveCommunityPostReportCommand command = new ResolveCommunityPostReportCommand(
                reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN, null
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommunityPostNotFoundException.class);

        verifyNoInteractions(resolveInteractionReportUseCase);
    }

    @Test
    @DisplayName("Propagates IllegalStateException when post is not in PUBLISHED state")
    void shouldPropagateIllegalStateException() {
        when(getInteractionReportDetailUseCase.execute(reportId))
                .thenReturn(createPostReportDetail(ReportStatus.PENDING));
        doThrow(new IllegalStateException("Cannot hide post with status: HIDDEN"))
                .when(hideCommunityPostUseCase).execute(any());

        ResolveCommunityPostReportCommand command = new ResolveCommunityPostReportCommand(
                reportId, moderatorUserId, ReportModerationAction.CONTENT_HIDDEN, null
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot hide post with status: HIDDEN");

        verifyNoInteractions(resolveInteractionReportUseCase);
    }
}
