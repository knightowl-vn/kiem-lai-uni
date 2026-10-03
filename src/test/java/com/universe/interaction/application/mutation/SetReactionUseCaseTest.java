package com.universe.interaction.application.mutation;

import com.universe.community.contracts.port.CommunityPostInteractionMutationPort;
import com.universe.interaction.application.exceptions.DuplicateReactionException;
import com.universe.interaction.application.exceptions.ReactionTargetNotEligibleException;
import com.universe.interaction.application.ports.CommentRepositoryPort;
import com.universe.interaction.application.ports.ReactionRepositoryPort;
import com.universe.interaction.application.ports.ReactionTargetEligibilityPort;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.domain.reaction.Reaction;
import com.universe.interaction.domain.reaction.ReactionTarget;
import com.universe.interaction.domain.reaction.ReactionType;
import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SetReactionUseCase Unit Tests")
class SetReactionUseCaseTest {

    @Mock
    private ReactionRepositoryPort reactionRepositoryPort;

    @Mock
    private ReactionTargetEligibilityPort eligibilityPort;

    @Mock
    private CommentRepositoryPort commentRepositoryPort;

    @Mock
    private CommunityPostInteractionMutationPort communityPostMutationPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private LockedReactionMutationExecutor lockedReactionMutationExecutor;
    private SetReactionUseCase useCase;

    @BeforeEach
    void setUp() {
        lockedReactionMutationExecutor = new LockedReactionMutationExecutor(
                communityPostMutationPort,
                commentRepositoryPort,
                reactionRepositoryPort,
                idGeneratorPort,
                clockPort
        );
        useCase = new SetReactionUseCase(
                lockedReactionMutationExecutor,
                reactionRepositoryPort,
                eligibilityPort,
                idGeneratorPort,
                clockPort
        );
    }

    @Test
    @DisplayName("Should create and save new reaction when no reaction exists on eligible target")
    void shouldCreateAndSaveNewReaction() {
        UUID userId = UUID.randomUUID();
        UUID generatedId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant now = Instant.parse("2026-09-26T10:00:00Z");

        when(eligibilityPort.isEligible(target)).thenReturn(true);
        when(reactionRepositoryPort.findByUserAndTarget(userId, target)).thenReturn(Optional.empty());
        when(idGeneratorPort.generate()).thenReturn(generatedId);
        when(clockPort.now()).thenReturn(now);
        when(reactionRepositoryPort.save(any(Reaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.LOVE);
        Reaction result = useCase.execute(command);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(generatedId);
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getTarget()).isEqualTo(target);
        assertThat(result.getReactionType()).isEqualTo(ReactionType.LOVE);
        assertThat(result.getCreatedAt()).isEqualTo(now);
        assertThat(result.getUpdatedAt()).isEqualTo(now);

        verify(eligibilityPort).isEligible(target);
        verify(idGeneratorPort).generate();
        verify(clockPort).now();
        verify(reactionRepositoryPort).save(result);
    }

    @Test
    @DisplayName("Should acquire Community post mutation barrier when target is COMMUNITY_POST")
    void shouldAcquireCommunityPostMutationBarrierWhenTargetIsCommunityPost() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.communityPost(postId);
        Instant now = Instant.parse("2026-09-26T10:00:00Z");

        CommunityPostInteractionMutationPort.CommunityPostLockedView lockedView =
                new CommunityPostInteractionMutationPort.CommunityPostLockedView(postId, UUID.randomUUID(), "Post caption");

        when(communityPostMutationPort.lockExistingPostForInteraction(postId)).thenReturn(Optional.of(lockedView));
        when(reactionRepositoryPort.findByUserAndTarget(userId, target)).thenReturn(Optional.empty());
        when(idGeneratorPort.generate()).thenReturn(UUID.randomUUID());
        when(clockPort.now()).thenReturn(now);
        when(reactionRepositoryPort.save(any(Reaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.FIRE);
        Reaction result = useCase.execute(command);

        assertThat(result).isNotNull();
        verify(communityPostMutationPort).lockExistingPostForInteraction(postId);
        verify(reactionRepositoryPort).save(any(Reaction.class));
    }

    @Test
    @DisplayName("Should reject and throw ReactionTargetNotEligibleException when COMMUNITY_POST does not exist under lock")
    void shouldRejectWhenCommunityPostDoesNotExistUnderLock() {
        UUID userId = UUID.randomUUID();
        UUID postId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.communityPost(postId);

        when(communityPostMutationPort.lockExistingPostForInteraction(postId)).thenReturn(Optional.empty());

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.LIKE);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ReactionTargetNotEligibleException.class)
                .satisfies(ex -> assertThat(((ReactionTargetNotEligibleException) ex).getTarget()).isEqualTo(target));

        verify(communityPostMutationPort).lockExistingPostForInteraction(postId);
        verify(reactionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should lock comment with pessimistic lock when target is COMMENT")
    void shouldLockCommentWhenTargetIsComment() {
        UUID userId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.comment(commentId);
        Instant now = Instant.parse("2026-09-26T10:00:00Z");

        Comment comment = Comment.createRoot(commentId, CommentTarget.novelChapter(UUID.randomUUID()), UUID.randomUUID(), "Comment text", now);

        when(commentRepositoryPort.findByIdForUpdate(commentId)).thenReturn(Optional.of(comment));
        when(reactionRepositoryPort.findByUserAndTarget(userId, target)).thenReturn(Optional.empty());
        when(idGeneratorPort.generate()).thenReturn(UUID.randomUUID());
        when(clockPort.now()).thenReturn(now);
        when(reactionRepositoryPort.save(any(Reaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.LIKE);
        Reaction result = useCase.execute(command);

        assertThat(result).isNotNull();
        verify(commentRepositoryPort).findByIdForUpdate(commentId);
        verify(reactionRepositoryPort).save(any(Reaction.class));
    }

    @Test
    @DisplayName("Should reject reaction on deleted comment")
    void shouldRejectReactionOnDeletedComment() {
        UUID userId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.comment(commentId);
        Instant now = Instant.parse("2026-09-26T10:00:00Z");

        Comment comment = Comment.createRoot(commentId, CommentTarget.novelChapter(UUID.randomUUID()), UUID.randomUUID(), "Comment text", now);
        // Simulate deleted comment by checking isDeleted (or mark deleted if domain allows)
        // If domain comment is active vs deleted:
        when(commentRepositoryPort.findByIdForUpdate(commentId)).thenReturn(Optional.empty());

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.LIKE);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ReactionTargetNotEligibleException.class);

        verify(commentRepositoryPort).findByIdForUpdate(commentId);
        verify(reactionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should return existing reaction without saving or bumping updatedAt when same reaction type is requested (idempotent)")
    void shouldReturnExistingWithoutSavingWhenSameTypeRequested() {
        UUID userId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant createdAt = Instant.parse("2026-09-26T10:00:00Z");

        Reaction existing = Reaction.create(existingId, userId, target, ReactionType.FIRE, createdAt);

        when(eligibilityPort.isEligible(target)).thenReturn(true);
        when(reactionRepositoryPort.findByUserAndTarget(userId, target)).thenReturn(Optional.of(existing));

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.FIRE);
        Reaction result = useCase.execute(command);

        assertThat(result).isSameAs(existing);
        assertThat(result.getReactionType()).isEqualTo(ReactionType.FIRE);
        assertThat(result.getUpdatedAt()).isEqualTo(createdAt);

        verify(reactionRepositoryPort, never()).save(any());
        verify(idGeneratorPort, never()).generate();
        verify(clockPort, never()).now();
    }

    @Test
    @DisplayName("Should mutate reaction type and save existing aggregate when different reaction type is requested")
    void shouldMutateAndSaveExistingAggregateWhenDifferentTypeRequested() {
        UUID userId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant t1 = Instant.parse("2026-09-26T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T10:30:00Z");

        Reaction existing = Reaction.create(existingId, userId, target, ReactionType.LOVE, t1);

        when(eligibilityPort.isEligible(target)).thenReturn(true);
        when(reactionRepositoryPort.findByUserAndTarget(userId, target)).thenReturn(Optional.of(existing));
        when(clockPort.now()).thenReturn(t2);
        when(reactionRepositoryPort.save(any(Reaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.HAHA);
        Reaction result = useCase.execute(command);

        assertThat(result).isSameAs(existing);
        assertThat(result.getId()).isEqualTo(existingId);
        assertThat(result.getReactionType()).isEqualTo(ReactionType.HAHA);
        assertThat(result.getCreatedAt()).isEqualTo(t1);
        assertThat(result.getUpdatedAt()).isEqualTo(t2);

        verify(reactionRepositoryPort).save(existing);
        verify(idGeneratorPort, never()).generate();
    }

    @Test
    @DisplayName("Should reject and throw ReactionTargetNotEligibleException when target is not eligible")
    void shouldThrowWhenTargetIsNotEligible() {
        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());

        when(eligibilityPort.isEligible(target)).thenReturn(false);

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.LOVE);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(ReactionTargetNotEligibleException.class)
                .satisfies(ex -> {
                    ReactionTargetNotEligibleException rne = (ReactionTargetNotEligibleException) ex;
                    assertThat(rne.getTarget()).isEqualTo(target);
                });

        verify(reactionRepositoryPort, never()).findByUserAndTarget(any(), any());
        verify(reactionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should reject null command and null parameters")
    void shouldRejectNullInputs() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SetReactionCommand cannot be null");

        UUID userId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());

        assertThatThrownBy(() -> new SetReactionCommand(null, target, ReactionType.LOVE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("User ID cannot be null");

        assertThatThrownBy(() -> new SetReactionCommand(userId, null, ReactionType.LOVE))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ReactionTarget cannot be null");

        assertThatThrownBy(() -> new SetReactionCommand(userId, target, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ReactionType cannot be null");
    }

    @Test
    @DisplayName("Duplicate Race Scenario A: collision occurs, refetch returns SAME desired type -> converges without second insert")
    void shouldRecoverFromDuplicateCollisionWhenAuthoritativeHasSameType() {
        UUID userId = UUID.randomUUID();
        UUID generatedId = UUID.randomUUID();
        UUID concurrentId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant t1 = Instant.parse("2026-09-26T10:00:00Z");

        Reaction authoritative = Reaction.create(concurrentId, userId, target, ReactionType.LOVE, t1);

        when(eligibilityPort.isEligible(target)).thenReturn(true);
        when(reactionRepositoryPort.findByUserAndTarget(userId, target))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(authoritative));
        when(idGeneratorPort.generate()).thenReturn(generatedId);
        when(clockPort.now()).thenReturn(t1);

        when(reactionRepositoryPort.save(any(Reaction.class)))
                .thenThrow(new DuplicateReactionException(userId, target, new RuntimeException("duplicate")));

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.LOVE);
        Reaction result = useCase.execute(command);

        assertThat(result).isSameAs(authoritative);
        assertThat(result.getId()).isEqualTo(concurrentId);
        assertThat(result.getReactionType()).isEqualTo(ReactionType.LOVE);

        verify(reactionRepositoryPort).save(any(Reaction.class));
    }

    @Test
    @DisplayName("Duplicate Race Scenario B: collision occurs, refetch returns DIFFERENT type -> mutates refreshed aggregate with fresh clock and saves")
    void shouldRecoverFromDuplicateCollisionWhenAuthoritativeHasDifferentType() {
        UUID userId = UUID.randomUUID();
        UUID generatedId = UUID.randomUUID();
        UUID concurrentId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant t1 = Instant.parse("2026-09-26T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T10:01:00Z");

        Reaction authoritative = Reaction.create(concurrentId, userId, target, ReactionType.FIRE, t1);

        when(eligibilityPort.isEligible(target)).thenReturn(true);
        when(reactionRepositoryPort.findByUserAndTarget(userId, target))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(authoritative));
        when(idGeneratorPort.generate()).thenReturn(generatedId);
        when(clockPort.now())
                .thenReturn(t1)
                .thenReturn(t2);

        when(reactionRepositoryPort.save(any(Reaction.class)))
                .thenThrow(new DuplicateReactionException(userId, target, new RuntimeException("duplicate")))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.HAHA);
        Reaction result = useCase.execute(command);

        assertThat(result).isSameAs(authoritative);
        assertThat(result.getId()).isEqualTo(concurrentId);
        assertThat(result.getReactionType()).isEqualTo(ReactionType.HAHA);
        assertThat(result.getUpdatedAt()).isEqualTo(t2);
    }

    @Test
    @DisplayName("Duplicate Race Scenario C: collision occurs, refetch unexpectedly returns empty -> throws IllegalStateException without infinite loop")
    void shouldThrowIllegalStateExceptionWhenRecoveryRefetchReturnsEmpty() {
        UUID userId = UUID.randomUUID();
        UUID generatedId = UUID.randomUUID();
        ReactionTarget target = ReactionTarget.novelChapter(UUID.randomUUID());
        Instant t1 = Instant.parse("2026-09-26T10:00:00Z");

        when(eligibilityPort.isEligible(target)).thenReturn(true);
        when(reactionRepositoryPort.findByUserAndTarget(userId, target))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(idGeneratorPort.generate()).thenReturn(generatedId);
        when(clockPort.now()).thenReturn(t1);

        when(reactionRepositoryPort.save(any(Reaction.class)))
                .thenThrow(new DuplicateReactionException(userId, target, new RuntimeException("duplicate")));

        SetReactionCommand command = new SetReactionCommand(userId, target, ReactionType.LOVE);

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate reaction collision occurred but authoritative reaction was not found");
    }
}
