package com.universe.community.application.usecase;

import com.universe.community.application.port.out.CommunityPostInteractionCleanupPort;
import com.universe.community.application.port.out.CommunityPostReportQueryPort;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.exception.CommunityPostHiddenDeleteForbiddenException;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostPendingReportConflictException;
import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeleteCommunityPostUseCase Unit Tests")
class DeleteCommunityPostUseCaseTest {

    private static final UUID AUTHOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID STRANGER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID POST_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID IMAGE_ASSET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    @Mock
    private CommunityPostRepositoryPort postRepositoryPort;

    @Mock
    private CommunityPostInteractionCleanupPort interactionCleanupPort;

    @Mock
    private CommunityPostReportQueryPort reportQueryPort;

    @Mock
    private MediaContract mediaContract;

    @Mock
    private ClockPort clockPort;

    private DeleteCommunityPostUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new DeleteCommunityPostUseCase(
                postRepositoryPort,
                interactionCleanupPort,
                reportQueryPort,
                mediaContract,
                clockPort
        );
    }

    @Test
    @DisplayName("Preconditions: Rejects null actorUserId or null postId")
    void shouldRejectNullArguments() {
        assertThatThrownBy(() -> useCase.execute(null, POST_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Actor user ID cannot be null.");

        assertThatThrownBy(() -> useCase.execute(AUTHOR_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Post ID cannot be null.");
    }

    @Test
    @DisplayName("Case A: Post does not exist -> throws CommunityPostNotFoundException, 0 side effects")
    void shouldThrowNotFoundWhenPostDoesNotExist() {
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID))
                .isInstanceOf(CommunityPostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());

        verify(interactionCleanupPort, never()).cleanupCommunityPostInteractions(any(), any());
        verify(mediaContract, never()).delete(any());
        verify(postRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("Case B: Actor is not author -> throws CommunityPostUnauthorizedException, 0 side effects")
    void shouldThrowUnauthorizedWhenActorIsNotAuthor() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Caption", null, CommunityPostStatus.PUBLISHED, NOW);
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> useCase.execute(STRANGER_ID, POST_ID))
                .isInstanceOf(CommunityPostUnauthorizedException.class);

        verify(interactionCleanupPort, never()).cleanupCommunityPostInteractions(any(), any());
        verify(mediaContract, never()).delete(any());
        verify(postRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("Moderation barrier: HIDDEN post cannot be deleted -> throws CommunityPostHiddenDeleteForbiddenException, 0 side effects")
    void shouldRejectDeletionWhenPostIsHidden() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Hidden post", IMAGE_ASSET_ID, CommunityPostStatus.HIDDEN, NOW);
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID))
                .isInstanceOf(CommunityPostHiddenDeleteForbiddenException.class)
                .hasMessageContaining(POST_ID.toString());

        verify(reportQueryPort, never()).hasPendingReports(any());
        verify(interactionCleanupPort, never()).cleanupCommunityPostInteractions(any(), any());
        verify(mediaContract, never()).delete(any());
        verify(postRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("Anti-evasion barrier: Post with pending abuse reports cannot be deleted -> throws CommunityPostPendingReportConflictException, 0 side effects")
    void shouldRejectDeletionWhenPostHasPendingReports() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Reported post", IMAGE_ASSET_ID, CommunityPostStatus.PUBLISHED, NOW);
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(reportQueryPort.hasPendingReports(POST_ID)).thenReturn(true);

        assertThatThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID))
                .isInstanceOf(CommunityPostPendingReportConflictException.class)
                .hasMessageContaining(POST_ID.toString());

        verify(interactionCleanupPort, never()).cleanupCommunityPostInteractions(any(), any());
        verify(mediaContract, never()).delete(any());
        verify(postRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("Case C: Caption-only post deletion succeeds -> cleans up interactions, deletes post, 0 media delete")
    void shouldDeleteCaptionOnlyPostSuccessfully() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Caption only", null, CommunityPostStatus.PUBLISHED, NOW);
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(clockPort.now()).thenReturn(NOW);

        assertThatNoException().isThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID));

        InOrder inOrder = inOrder(postRepositoryPort, clockPort, interactionCleanupPort, mediaContract);
        inOrder.verify(postRepositoryPort).findByIdForUpdate(POST_ID);
        inOrder.verify(clockPort).now();
        inOrder.verify(interactionCleanupPort).cleanupCommunityPostInteractions(POST_ID, NOW);
        inOrder.verify(postRepositoryPort).deleteById(POST_ID);

        verify(mediaContract, never()).delete(any());
    }

    @Test
    @DisplayName("Case D: Image post deletion succeeds -> cleans up interactions, marks media DELETED, deletes post")
    void shouldDeleteImagePostSuccessfully() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Post with image", IMAGE_ASSET_ID, CommunityPostStatus.PUBLISHED, NOW);
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(clockPort.now()).thenReturn(NOW);

        assertThatNoException().isThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID));

        InOrder inOrder = inOrder(postRepositoryPort, clockPort, interactionCleanupPort, mediaContract);
        inOrder.verify(postRepositoryPort).findByIdForUpdate(POST_ID);
        inOrder.verify(clockPort).now();
        inOrder.verify(interactionCleanupPort).cleanupCommunityPostInteractions(POST_ID, NOW);
        inOrder.verify(mediaContract).delete(IMAGE_ASSET_ID);
        inOrder.verify(postRepositoryPort).deleteById(POST_ID);
    }

    @Test
    @DisplayName("Deletion of PENDING_REVIEW post succeeds when no pending reports exist")
    void shouldAllowDeletingPendingReviewPostWhenNoPendingReports() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Pending post", IMAGE_ASSET_ID, CommunityPostStatus.PENDING_REVIEW, NOW);
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(reportQueryPort.hasPendingReports(POST_ID)).thenReturn(false);
        when(clockPort.now()).thenReturn(NOW);

        assertThatNoException().isThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID));

        verify(interactionCleanupPort).cleanupCommunityPostInteractions(POST_ID, NOW);
        verify(mediaContract).delete(IMAGE_ASSET_ID);
        verify(postRepositoryPort).deleteById(POST_ID);
    }

    @Test
    @DisplayName("Deletion of REJECTED post succeeds when no pending reports exist")
    void shouldAllowDeletingRejectedPostWhenNoPendingReports() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Rejected post", null, CommunityPostStatus.REJECTED, NOW);
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(reportQueryPort.hasPendingReports(POST_ID)).thenReturn(false);
        when(clockPort.now()).thenReturn(NOW);

        assertThatNoException().isThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID));

        verify(interactionCleanupPort).cleanupCommunityPostInteractions(POST_ID, NOW);
        verify(postRepositoryPort).deleteById(POST_ID);
    }

    @Test
    @DisplayName("Case E: Interaction cleanup fails -> exception propagates, 0 media delete, 0 post delete")
    void shouldPropagateExceptionWhenInteractionCleanupFails() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Post with image", IMAGE_ASSET_ID, CommunityPostStatus.PUBLISHED, NOW);
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(clockPort.now()).thenReturn(NOW);
        doThrow(new RuntimeException("Simulated Interaction cleanup failure"))
                .when(interactionCleanupPort).cleanupCommunityPostInteractions(POST_ID, NOW);

        assertThatThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated Interaction cleanup failure");

        verify(mediaContract, never()).delete(any());
        verify(postRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("Case F: Media delete fails -> exception propagates, 0 post delete")
    void shouldPropagateExceptionWhenMediaDeleteFails() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Post with image", IMAGE_ASSET_ID, CommunityPostStatus.PUBLISHED, NOW);
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(clockPort.now()).thenReturn(NOW);
        doThrow(new RuntimeException("Simulated Media delete failure"))
                .when(mediaContract).delete(IMAGE_ASSET_ID);

        assertThatThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated Media delete failure");

        verify(interactionCleanupPort).cleanupCommunityPostInteractions(POST_ID, NOW);
        verify(postRepositoryPort, never()).deleteById(any());
    }

    @Test
    @DisplayName("Case G: Verifies strict execution ordering across all ports")
    void shouldVerifyStrictExecutionOrdering() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Ordered post", IMAGE_ASSET_ID, CommunityPostStatus.PUBLISHED, NOW);
        when(postRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(clockPort.now()).thenReturn(NOW);

        useCase.execute(AUTHOR_ID, POST_ID);

        InOrder inOrder = inOrder(postRepositoryPort, clockPort, interactionCleanupPort, mediaContract);
        inOrder.verify(postRepositoryPort).findByIdForUpdate(POST_ID);
        inOrder.verify(clockPort).now();
        inOrder.verify(interactionCleanupPort).cleanupCommunityPostInteractions(POST_ID, NOW);
        inOrder.verify(mediaContract).delete(IMAGE_ASSET_ID);
        inOrder.verify(postRepositoryPort).deleteById(POST_ID);
    }

    @Test
    @DisplayName("Repeated Delete: First deletion succeeds -> Second deletion throws NotFound with zero side effects")
    void shouldHandleRepeatedDeleteDeterministically() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Repeated delete post", IMAGE_ASSET_ID, CommunityPostStatus.PUBLISHED, NOW);
        // First execution finds post, second finds nothing
        when(postRepositoryPort.findByIdForUpdate(POST_ID))
                .thenReturn(Optional.of(post))
                .thenReturn(Optional.empty());
        when(clockPort.now()).thenReturn(NOW);

        // 1. First delete succeeds
        assertThatNoException().isThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID));

        // 2. Second delete throws CommunityPostNotFoundException
        assertThatThrownBy(() -> useCase.execute(AUTHOR_ID, POST_ID))
                .isInstanceOf(CommunityPostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());

        // Verify side effects occurred exactly ONCE across both attempts
        verify(interactionCleanupPort, org.mockito.Mockito.times(1)).cleanupCommunityPostInteractions(POST_ID, NOW);
        verify(mediaContract, org.mockito.Mockito.times(1)).delete(IMAGE_ASSET_ID);
        verify(postRepositoryPort, org.mockito.Mockito.times(1)).deleteById(POST_ID);
    }
}
