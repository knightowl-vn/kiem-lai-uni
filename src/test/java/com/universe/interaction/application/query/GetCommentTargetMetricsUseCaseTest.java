package com.universe.interaction.application.query;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.CommentTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetCommentTargetMetricsUseCase Unit Tests")
class GetCommentTargetMetricsUseCaseTest {

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    private GetCommentTargetMetricsUseCase useCase;

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        useCase = new GetCommentTargetMetricsUseCase(commentRepositoryPort);
    }

    @Test
    @DisplayName("Should reject null dependency in constructor")
    void shouldRejectNullDependency() {
        assertThatThrownBy(() -> new GetCommentTargetMetricsUseCase(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("CommentRepositoryPort cannot be null.");
    }

    @Test
    @DisplayName("Should reject null target in execute")
    void shouldRejectNullTarget() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CommentTarget cannot be null.");
    }

    @Test
    @DisplayName("A. Should return zero metrics when target has no comments")
    void shouldReturnZeroMetricsWhenNoComments() {
        CommentTarget target = CommentTarget.wikiArticle(TARGET_ID);
        when(commentRepositoryPort.getMetricsForTarget(target))
                .thenReturn(CommentTargetMetrics.EMPTY);

        CommentTargetMetrics metrics = useCase.execute(target);

        assertThat(metrics.threadCount()).isZero();
        assertThat(metrics.commentCount()).isZero();
        verify(commentRepositoryPort).getMetricsForTarget(target);
    }

    @Test
    @DisplayName("B. Should return accurate metrics for 2 active roots + 4 active replies (threadCount=2, commentCount=6)")
    void shouldReturnAccurateMetricsForActiveRootsAndReplies() {
        CommentTarget target = CommentTarget.wikiArticle(TARGET_ID);
        CommentTargetMetrics expected = new CommentTargetMetrics(2, 6);
        when(commentRepositoryPort.getMetricsForTarget(target)).thenReturn(expected);

        CommentTargetMetrics metrics = useCase.execute(target);

        assertThat(metrics.threadCount()).isEqualTo(2);
        assertThat(metrics.commentCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("Record Invariant: commentCount cannot be less than threadCount")
    void shouldEnforceCommentCountGteThreadCount() {
        assertThatThrownBy(() -> new CommentTargetMetrics(5, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be less than threadCount");
    }

    @Test
    @DisplayName("Record Invariant: counts cannot be negative")
    void shouldEnforceNonNegativeCounts() {
        assertThatThrownBy(() -> new CommentTargetMetrics(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CommentTargetMetrics(0, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
