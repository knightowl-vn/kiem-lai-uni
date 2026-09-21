package com.universe.interaction.application.query;

import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.InteractionReportRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.InteractionReport;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetInteractionReportDetailUseCaseTest {

    @Mock
    private InteractionReportRepositoryPort reportRepositoryPort;

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    private GetInteractionReportDetailUseCase useCase;

    private final UUID reportId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID commentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID reporterUserId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID authorUserId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private final UUID targetId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final Instant baseTime = Instant.parse("2026-09-21T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetInteractionReportDetailUseCase(reportRepositoryPort, commentRepositoryPort);
    }

    @Test
    @DisplayName("Case A: Active comment - report and active comment found, all fields mapped accurately")
    void shouldReturnDetailWithActiveComment() {
        InteractionReport report = InteractionReport.reconstitute(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.SPAM,
                "Quảng cáo cờ bạc trái phép",
                "Mua acc vip tại web abc.xyz",
                ReportStatus.PENDING,
                baseTime,
                null,
                null
        );

        Comment comment = Comment.createRoot(
                commentId,
                new CommentTarget(CommentTargetType.NOVEL_CHAPTER, targetId),
                authorUserId,
                "Mua acc vip tại web abc.xyz (edited)",
                baseTime.minusSeconds(600)
        );

        when(reportRepositoryPort.findById(reportId)).thenReturn(Optional.of(report));
        when(commentRepositoryPort.findById(commentId)).thenReturn(Optional.of(comment));

        InteractionReportDetailResult result = useCase.execute(reportId);

        assertThat(result).isNotNull();
        // Report fields verification
        assertThat(result.reportId()).isEqualTo(reportId);
        assertThat(result.commentId()).isEqualTo(commentId);
        assertThat(result.reporterUserId()).isEqualTo(reporterUserId);
        assertThat(result.reason()).isEqualTo(ReportReason.SPAM);
        assertThat(result.description()).isEqualTo("Quảng cáo cờ bạc trái phép");
        assertThat(result.reportedBodySnapshot()).isEqualTo("Mua acc vip tại web abc.xyz");
        assertThat(result.status()).isEqualTo(ReportStatus.PENDING);
        assertThat(result.createdAt()).isEqualTo(baseTime);
        assertThat(result.resolvedByUserId()).isNull();
        assertThat(result.resolvedAt()).isNull();

        // Current comment state verification
        assertThat(result.currentCommentAvailable()).isTrue();
        assertThat(result.commentAuthorUserId()).isEqualTo(authorUserId);
        assertThat(result.currentCommentStatus()).isEqualTo(CommentStatus.ACTIVE);
        assertThat(result.currentCommentBody()).isEqualTo("Mua acc vip tại web abc.xyz (edited)");
        assertThat(result.targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(result.targetId()).isEqualTo(targetId);
        assertThat(result.commentCreatedAt()).isEqualTo(baseTime.minusSeconds(600));
        assertThat(result.commentUpdatedAt()).isEqualTo(baseTime.minusSeconds(600));
        assertThat(result.commentDeletedAt()).isNull();

        verify(reportRepositoryPort, times(1)).findById(reportId);
        verify(commentRepositoryPort, times(1)).findById(commentId);
    }

    @Test
    @DisplayName("Case B: Deleted comment tombstone - report evidence intact, current body null, deletedAt preserved")
    void shouldReturnDetailWithDeletedCommentTombstone() {
        UUID resolverUserId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        Instant resolvedAt = baseTime.plusSeconds(300);

        InteractionReport report = InteractionReport.reconstitute(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.HARASSMENT,
                "Xúc phạm thành viên khác",
                "Nội dung xúc phạm nặng nề",
                ReportStatus.RESOLVED_ACTION_TAKEN,
                baseTime,
                resolverUserId,
                resolvedAt
        );

        Instant commentCreatedAt = baseTime.minusSeconds(1200);
        Instant commentDeletedAt = baseTime.plusSeconds(100);

        Comment deletedComment = Comment.rehydrate(
                commentId,
                new CommentTarget(CommentTargetType.WIKI_ARTICLE, targetId),
                authorUserId,
                null,
                null,
                null, // Domain invariant: body is null for DELETED comments
                CommentStatus.DELETED,
                commentCreatedAt,
                commentDeletedAt,
                commentDeletedAt
        );

        when(reportRepositoryPort.findById(reportId)).thenReturn(Optional.of(report));
        when(commentRepositoryPort.findById(commentId)).thenReturn(Optional.of(deletedComment));

        InteractionReportDetailResult result = useCase.execute(reportId);

        assertThat(result).isNotNull();
        // Report evidence remains intact
        assertThat(result.reportId()).isEqualTo(reportId);
        assertThat(result.reportedBodySnapshot()).isEqualTo("Nội dung xúc phạm nặng nề");
        assertThat(result.status()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
        assertThat(result.resolvedByUserId()).isEqualTo(resolverUserId);
        assertThat(result.resolvedAt()).isEqualTo(resolvedAt);

        // Deleted comment tombstone semantics
        assertThat(result.currentCommentAvailable()).isTrue();
        assertThat(result.currentCommentStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(result.currentCommentBody()).isNull(); // Live body is null
        assertThat(result.commentDeletedAt()).isEqualTo(commentDeletedAt);
        assertThat(result.targetType()).isEqualTo(CommentTargetType.WIKI_ARTICLE);
        assertThat(result.targetId()).isEqualTo(targetId);

        verify(reportRepositoryPort, times(1)).findById(reportId);
        verify(commentRepositoryPort, times(1)).findById(commentId);
    }

    @Test
    @DisplayName("Case C: Missing current comment - report returned with currentCommentAvailable = false")
    void shouldReturnDetailWhenCurrentCommentIsMissing() {
        InteractionReport report = InteractionReport.reconstitute(
                reportId,
                commentId,
                reporterUserId,
                ReportReason.OTHER,
                "Lý do khác",
                "Bằng chứng lịch sử của bình luận",
                ReportStatus.PENDING,
                baseTime,
                null,
                null
        );

        when(reportRepositoryPort.findById(reportId)).thenReturn(Optional.of(report));
        when(commentRepositoryPort.findById(commentId)).thenReturn(Optional.empty());

        InteractionReportDetailResult result = useCase.execute(reportId);

        assertThat(result).isNotNull();
        // Report evidence is preserved
        assertThat(result.reportId()).isEqualTo(reportId);
        assertThat(result.commentId()).isEqualTo(commentId);
        assertThat(result.reportedBodySnapshot()).isEqualTo("Bằng chứng lịch sử của bình luận");

        // Current comment availability flag is false, fields are null
        assertThat(result.currentCommentAvailable()).isFalse();
        assertThat(result.commentAuthorUserId()).isNull();
        assertThat(result.currentCommentStatus()).isNull();
        assertThat(result.currentCommentBody()).isNull();
        assertThat(result.targetType()).isNull();
        assertThat(result.targetId()).isNull();
        assertThat(result.commentCreatedAt()).isNull();
        assertThat(result.commentUpdatedAt()).isNull();
        assertThat(result.commentDeletedAt()).isNull();

        verify(reportRepositoryPort, times(1)).findById(reportId);
        verify(commentRepositoryPort, times(1)).findById(commentId);
    }

    @Test
    @DisplayName("Case D: Missing report - throws InteractionReportNotFoundException and does not query comment")
    void shouldThrowWhenReportNotFound() {
        when(reportRepositoryPort.findById(reportId)).thenReturn(Optional.empty());

        assertThrows(InteractionReportNotFoundException.class, () -> useCase.execute(reportId));

        verify(reportRepositoryPort, times(1)).findById(reportId);
        verifyNoInteractions(commentRepositoryPort);
    }

    @Test
    @DisplayName("Case E: Null reportId - throws IllegalArgumentException without querying repositories")
    void shouldThrowWhenReportIdIsNull() {
        assertThrows(IllegalArgumentException.class, () -> useCase.execute(null));

        verifyNoInteractions(reportRepositoryPort);
        verifyNoInteractions(commentRepositoryPort);
    }
}
