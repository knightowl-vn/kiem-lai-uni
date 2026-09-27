package com.universe.interaction.infrastructure.eligibility;

import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.novel.application.ports.ReaderChapterAccessQueryPort;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReactionTargetEligibilityAdapter Unit Tests")
class ReactionTargetEligibilityAdapterTest {

    @Mock
    private ReaderChapterAccessQueryPort readerChapterAccessQueryPort;

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    private ReactionTargetEligibilityAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReactionTargetEligibilityAdapter(
                readerChapterAccessQueryPort,
                commentRepositoryPort
        );
    }

    // =========================================================================
    // NOVEL_CHAPTER ELIGIBILITY
    // =========================================================================

    @Test
    @DisplayName("NOVEL_CHAPTER: published and reader-accessible chapter is eligible")
    void shouldReturnTrueForPublishedNovelChapter() {
        UUID chapterId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(chapterId);

        when(readerChapterAccessQueryPort.findPublishedById(chapterId))
                .thenReturn(Optional.of(new ReaderChapterAccessQueryPort.ReadableChapterReference(chapterId, 1)));

        boolean eligible = adapter.isEligible(target);

        assertThat(eligible).isTrue();
        verify(readerChapterAccessQueryPort).findPublishedById(chapterId);
        verifyNoInteractions(commentRepositoryPort);
    }

    @Test
    @DisplayName("NOVEL_CHAPTER: missing or unpublished chapter is not eligible")
    void shouldReturnFalseForMissingOrUnpublishedNovelChapter() {
        UUID chapterId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(chapterId);

        when(readerChapterAccessQueryPort.findPublishedById(chapterId))
                .thenReturn(Optional.empty());

        boolean eligible = adapter.isEligible(target);

        assertThat(eligible).isFalse();
        verify(readerChapterAccessQueryPort).findPublishedById(chapterId);
        verifyNoInteractions(commentRepositoryPort);
    }

    // =========================================================================
    // COMMENT ELIGIBILITY
    // =========================================================================

    @Test
    @DisplayName("COMMENT: ACTIVE root comment is eligible")
    void shouldReturnTrueForActiveRootComment() {
        UUID commentId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.comment(commentId);

        Comment activeRoot = Comment.createRoot(
                commentId,
                CommentTarget.novelChapter(UUID.randomUUID()),
                UUID.randomUUID(),
                "Active root comment",
                Instant.parse("2026-09-26T10:00:00Z")
        );

        when(commentRepositoryPort.findById(commentId)).thenReturn(Optional.of(activeRoot));

        boolean eligible = adapter.isEligible(target);

        assertThat(eligible).isTrue();
        verify(commentRepositoryPort).findById(commentId);
        verifyNoInteractions(readerChapterAccessQueryPort);
    }

    @Test
    @DisplayName("COMMENT: ACTIVE reply comment is eligible")
    void shouldReturnTrueForActiveReplyComment() {
        UUID commentId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.comment(commentId);

        Comment root = Comment.createRoot(
                UUID.randomUUID(),
                CommentTarget.novelChapter(UUID.randomUUID()),
                UUID.randomUUID(),
                "Root",
                Instant.parse("2026-09-26T10:00:00Z")
        );

        Comment activeReply = Comment.createReply(
                commentId,
                root,
                UUID.randomUUID(),
                "Active reply",
                Instant.parse("2026-09-26T10:05:00Z")
        );

        when(commentRepositoryPort.findById(commentId)).thenReturn(Optional.of(activeReply));

        boolean eligible = adapter.isEligible(target);

        assertThat(eligible).isTrue();
        verify(commentRepositoryPort).findById(commentId);
        verifyNoInteractions(readerChapterAccessQueryPort);
    }

    @Test
    @DisplayName("COMMENT: DELETED comment (tombstone) is not eligible")
    void shouldReturnFalseForDeletedComment() {
        UUID commentId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.comment(commentId);

        Comment comment = Comment.createRoot(
                commentId,
                CommentTarget.novelChapter(UUID.randomUUID()),
                UUID.randomUUID(),
                "To delete",
                Instant.parse("2026-09-26T10:00:00Z")
        );
        comment.delete(Instant.parse("2026-09-26T10:10:00Z"));

        when(commentRepositoryPort.findById(commentId)).thenReturn(Optional.of(comment));

        boolean eligible = adapter.isEligible(target);

        assertThat(eligible).isFalse();
        verify(commentRepositoryPort).findById(commentId);
        verifyNoInteractions(readerChapterAccessQueryPort);
    }

    @Test
    @DisplayName("COMMENT: missing comment is not eligible")
    void shouldReturnFalseForMissingComment() {
        UUID commentId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.comment(commentId);

        when(commentRepositoryPort.findById(commentId)).thenReturn(Optional.empty());

        boolean eligible = adapter.isEligible(target);

        assertThat(eligible).isFalse();
        verify(commentRepositoryPort).findById(commentId);
        verifyNoInteractions(readerChapterAccessQueryPort);
    }

    // =========================================================================
    // DONGHUA_EPISODE ELIGIBILITY
    // =========================================================================

    @Test
    @DisplayName("DONGHUA_EPISODE: explicitly fails closed (returns false)")
    void shouldFailClosedForDonghuaEpisode() {
        UUID episodeId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.donghuaEpisode(episodeId);

        boolean eligible = adapter.isEligible(target);

        assertThat(eligible).isFalse();
        verifyNoInteractions(readerChapterAccessQueryPort);
        verifyNoInteractions(commentRepositoryPort);
    }

    // =========================================================================
    // NULL TARGET
    // =========================================================================

    @Test
    @DisplayName("Null target returns false")
    void shouldReturnFalseForNullTarget() {
        boolean eligible = adapter.isEligible(null);

        assertThat(eligible).isFalse();
        verifyNoInteractions(readerChapterAccessQueryPort);
        verifyNoInteractions(commentRepositoryPort);
    }
}
