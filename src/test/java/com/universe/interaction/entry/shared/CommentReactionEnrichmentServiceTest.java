package com.universe.interaction.entry.shared;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.universe.interaction.application.query.GetBatchReactionSummariesUseCase;
import com.universe.interaction.application.query.ReactionSummary;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionTargetType;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.interaction.entry.shared.CommentReactionEnrichmentService.ReactionEnrichmentResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommentReactionEnrichmentService Unit Tests")
class CommentReactionEnrichmentServiceTest {

    private static final UUID COMMENT_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMMENT_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VIEWER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Mock
    private GetBatchReactionSummariesUseCase getBatchReactionSummariesUseCase;

    private CommentReactionEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new CommentReactionEnrichmentService(getBatchReactionSummariesUseCase);
    }

    @Test
    @DisplayName("Empty comment ID list skips Reaction use case and marks failure=false")
    void shouldSkipEmptyInput() {
        ReactionEnrichmentResult result = service.loadCommentReactionSummaries("screen title", List.of(), VIEWER_ID, true);

        assertThat(result.summariesByCommentId()).isEmpty();
        assertThat(result.failed()).isFalse();
        assertThat(result.elapsedNanos()).isZero();
        verifyNoInteractions(getBatchReactionSummariesUseCase);
    }

    @Test
    @DisplayName("Maps counts and viewer state accurately into immutable DTO map")
    void shouldMapSummariesAndPreserveViewerState() {
        when(getBatchReactionSummariesUseCase.execute(ReactionTargetType.COMMENT, List.of(COMMENT_1, COMMENT_2), VIEWER_ID))
                .thenReturn(Map.of(
                        COMMENT_1, ReactionSummary.of(ReactionTarget.comment(COMMENT_1), Map.of(ReactionType.LIKE, 4L), ReactionType.LIKE),
                        COMMENT_2, ReactionSummary.of(ReactionTarget.comment(COMMENT_2), Map.of(ReactionType.LOVE, 2L), null)
                ));

        ReactionEnrichmentResult result = service.loadCommentReactionSummaries(
                "screen title feed",
                List.of(COMMENT_1, COMMENT_2),
                VIEWER_ID,
                true
        );

        assertThat(result.failed()).isFalse();
        assertThat(result.summariesByCommentId()).containsKeys(COMMENT_1, COMMENT_2);
        assertThat(result.summariesByCommentId().get(COMMENT_1).totalCount()).isEqualTo(4L);
        assertThat(result.summariesByCommentId().get(COMMENT_1).currentUserReaction()).isEqualTo("LIKE");
        assertThat(result.summariesByCommentId().get(COMMENT_2).totalCount()).isEqualTo(2L);
        assertThat(result.summariesByCommentId().get(COMMENT_2).currentUserReaction()).isNull();

        assertThatThrownBy(() -> result.summariesByCommentId().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Distinguishes empty successful Reaction lookup from failed lookup")
    void shouldDistinguishEmptySuccessFromFailure() {
        Logger logger = (Logger) LoggerFactory.getLogger(CommentReactionEnrichmentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(getBatchReactionSummariesUseCase.execute(ReactionTargetType.COMMENT, List.of(COMMENT_1), null))
                    .thenReturn(Map.of());

            ReactionEnrichmentResult emptySuccess = service.loadCommentReactionSummaries(
                    "wiki",
                    List.of(COMMENT_1),
                    null,
                    false
            );

            assertThat(emptySuccess.failed()).isFalse();
            assertThat(emptySuccess.summariesByCommentId()).isEmpty();
            assertThat(emptySuccess.elapsedNanos()).isZero();
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("Catches QueryTimeoutException / RuntimeException, marks failed=true, and logs sanitized warning")
    void shouldIsolateFailureWithSanitizedWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(CommentReactionEnrichmentService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(getBatchReactionSummariesUseCase.execute(ReactionTargetType.COMMENT, List.of(COMMENT_1, COMMENT_2), VIEWER_ID))
                    .thenThrow(new QueryTimeoutException("SQL bind secret_token_xyz failed for " + VIEWER_ID + " and " + COMMENT_1));

            ReactionEnrichmentResult failedResult = service.loadCommentReactionSummaries(
                    "screen title feed",
                    List.of(COMMENT_1, COMMENT_2),
                    VIEWER_ID,
                    true
            );

            assertThat(failedResult.failed()).isTrue();
            assertThat(failedResult.summariesByCommentId()).isEmpty();

            List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage())
                    .contains("screen title feed")
                    .contains("QueryTimeoutException")
                    .contains("activeCommentCount=2")
                    .contains("authenticatedViewer=true")
                    .doesNotContain(COMMENT_1.toString())
                    .doesNotContain(COMMENT_2.toString())
                    .doesNotContain(VIEWER_ID.toString())
                    .doesNotContain("secret_token_xyz");
            assertThat(warnings.get(0).getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
