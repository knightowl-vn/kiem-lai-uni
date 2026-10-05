package com.universe.community.application.usecase;

import com.universe.community.application.command.EditCommunityPostCaptionCommand;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRevisionRepositoryPort;
import com.universe.community.application.port.out.CommunitySettingsRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostRevision;
import com.universe.community.domain.CommunityPostStatus;
import com.universe.community.domain.CommunityPublicationMode;
import com.universe.community.domain.CommunitySettings;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostPendingEditConflictException;
import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
import com.universe.community.domain.exception.CommunityPostValidationException;
import com.universe.shared.id.IdGeneratorPort;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EditCommunityPostCaptionUseCase Unit Tests")
class EditCommunityPostCaptionUseCaseTest {

    private static final UUID POST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AUTHOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID STRANGER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REVISION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T10:00:00Z");
    private static final Instant EDITED_AT = Instant.parse("2026-09-29T11:00:00Z");

    @Mock
    private CommunityPostRepositoryPort communityPostRepositoryPort;

    @Mock
    private CommunityPostRevisionRepositoryPort communityPostRevisionRepositoryPort;

    @Mock
    private CommunitySettingsRepositoryPort communitySettingsRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private EditCommunityPostCaptionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new EditCommunityPostCaptionUseCase(
                communityPostRepositoryPort,
                communityPostRevisionRepositoryPort,
                communitySettingsRepositoryPort,
                idGeneratorPort,
                clockPort
        );
        lenient().when(communitySettingsRepositoryPort.getSettings())
                .thenReturn(CommunitySettings.defaultSettings());
    }

    @Test
    @DisplayName("Should throw CommunityPostNotFoundException when target post does not exist")
    void shouldThrowNotFoundWhenPostDoesNotExist() {
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.empty());

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "New caption"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommunityPostNotFoundException.class)
                .hasMessageContaining(POST_ID.toString());

        verify(communityPostRevisionRepositoryPort, never()).save(any());
        verify(communityPostRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should successfully edit caption and archive prior caption into revision")
    void shouldEditCaptionAndArchiveRevision() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null);

        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));
        when(clockPort.now()).thenReturn(EDITED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);
        when(communityPostRepositoryPort.save(any(CommunityPost.class))).thenAnswer(inv -> inv.getArgument(0));

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "New updated caption"
        );

        CommunityPost result = useCase.execute(command);

        assertThat(result.getCaption()).isEqualTo("New updated caption");
        assertThat(result.getContentVersion()).isEqualTo(1);
        assertThat(result.getUpdatedAt()).isEqualTo(EDITED_AT);
        assertThat(result.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(result.getPublishedAt()).isEqualTo(CREATED_AT);
        assertThat(result.getReviewRequestedAt()).isNull();

        // Verify revision persistence
        ArgumentCaptor<CommunityPostRevision> revisionCaptor = ArgumentCaptor.forClass(CommunityPostRevision.class);
        verify(communityPostRevisionRepositoryPort).save(revisionCaptor.capture());

        CommunityPostRevision savedRevision = revisionCaptor.getValue();
        assertThat(savedRevision.id()).isEqualTo(REVISION_ID);
        assertThat(savedRevision.postId()).isEqualTo(POST_ID);
        assertThat(savedRevision.revisionNumber()).isEqualTo(1);
        assertThat(savedRevision.editorUserId()).isEqualTo(AUTHOR_ID);
        assertThat(savedRevision.previousCaption()).isEqualTo("Old caption");
        assertThat(savedRevision.caption()).isEqualTo("New updated caption");
        assertThat(savedRevision.editedAt()).isEqualTo(EDITED_AT);

        // Verify order: post saved before revision
        var inOrder = inOrder(communityPostRepositoryPort, communityPostRevisionRepositoryPort);
        inOrder.verify(communityPostRepositoryPort).save(existingPost);
        inOrder.verify(communityPostRevisionRepositoryPort).save(savedRevision);
    }

    @Test
    @DisplayName("Should set pendingCaption and retain PUBLISHED status without saving revision when editing post under PRE_MODERATION mode")
    void shouldSetPendingCaptionWhenEditingUnderPreModerationMode() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null);

        when(communitySettingsRepositoryPort.getSettings())
                .thenReturn(CommunitySettings.defaultSettings().withPublicationMode(CommunityPublicationMode.PRE_MODERATION, AUTHOR_ID, EDITED_AT));
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));
        when(clockPort.now()).thenReturn(EDITED_AT);
        when(communityPostRepositoryPort.save(any(CommunityPost.class))).thenAnswer(inv -> inv.getArgument(0));

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "New updated caption under pre-moderation"
        );

        CommunityPost result = useCase.execute(command);

        assertThat(result.getCaption()).isEqualTo("Old caption");
        assertThat(result.getPendingCaption()).isEqualTo("New updated caption under pre-moderation");
        assertThat(result.getContentVersion()).isEqualTo(0);
        assertThat(result.getStatus()).isEqualTo(CommunityPostStatus.PUBLISHED);
        assertThat(result.getPublishedAt()).isEqualTo(CREATED_AT); // preserves historical publication
        assertThat(result.getReviewRequestedAt()).isEqualTo(EDITED_AT); // stamps review requested timestamp
        verify(communityPostRevisionRepositoryPort, never()).save(any()); // No revision archived yet!
    }

    @Test
    @DisplayName("Single Candidate Barrier: should reject second edit with 409 conflict when pendingCaption exists")
    void shouldRejectSecondEditWhenPendingCaptionExists() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null);
        existingPost.editCaption(AUTHOR_ID, "First candidate", EDITED_AT, CommunityPublicationMode.PRE_MODERATION);

        when(communitySettingsRepositoryPort.getSettings())
                .thenReturn(CommunitySettings.defaultSettings().withPublicationMode(CommunityPublicationMode.PRE_MODERATION, AUTHOR_ID, EDITED_AT));
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));
        when(clockPort.now()).thenReturn(EDITED_AT.plusSeconds(60));

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "Second candidate edit"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommunityPostPendingEditConflictException.class)
                .hasMessageContaining("Bản chỉnh sửa hiện tại đang chờ quản trị viên duyệt.");

        verify(communityPostRepositoryPort, never()).save(any());
        verify(communityPostRevisionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should be an idempotent no-op when new caption is identical")
    void shouldBeNoOpWhenCaptionIsIdentical() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null);

        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));
        when(clockPort.now()).thenReturn(EDITED_AT);

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "   Old caption   " // trims to identical
        );

        CommunityPost result = useCase.execute(command);

        assertThat(result.getCaption()).isEqualTo("Old caption");
        assertThat(result.getContentVersion()).isEqualTo(0);
        assertThat(result.getUpdatedAt()).isEqualTo(CREATED_AT);

        verify(communityPostRepositoryPort, never()).save(any());
        verify(communityPostRevisionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should throw CommunityPostValidationException when editing post in PENDING_REVIEW status")
    void shouldRejectEditingWhenPostIsPendingReview() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Pending caption", null, CommunityPostStatus.PENDING_REVIEW, CREATED_AT, null, CREATED_AT);
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(clockPort.now()).thenReturn(EDITED_AT);

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "Attempted edit"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("Only PUBLISHED posts can be edited");

        verify(communityPostRepositoryPort, never()).save(any());
        verify(communityPostRevisionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should throw CommunityPostValidationException when editing post in HIDDEN status")
    void shouldRejectEditingWhenPostIsHidden() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Hidden caption", null, CommunityPostStatus.HIDDEN, CREATED_AT, CREATED_AT, null);
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(clockPort.now()).thenReturn(EDITED_AT);

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "Attempted edit"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("Only PUBLISHED posts can be edited");

        verify(communityPostRepositoryPort, never()).save(any());
        verify(communityPostRevisionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should throw CommunityPostValidationException when editing post in REJECTED status")
    void shouldRejectEditingWhenPostIsRejected() {
        CommunityPost post = CommunityPost.create(POST_ID, AUTHOR_ID, "Rejected caption", null, CommunityPostStatus.REJECTED, CREATED_AT, null, null);
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(post));
        when(clockPort.now()).thenReturn(EDITED_AT);

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "Attempted edit"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("Only PUBLISHED posts can be edited");

        verify(communityPostRepositoryPort, never()).save(any());
        verify(communityPostRevisionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should throw CommunityPostUnauthorizedException when editor is not the author")
    void shouldThrowUnauthorizedWhenEditorIsNotAuthor() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null);
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                STRANGER_ID,
                "Malicious edit attempt"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommunityPostUnauthorizedException.class);

        verify(communityPostRevisionRepositoryPort, never()).save(any());
        verify(communityPostRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should not reach revision save when post save fails")
    void shouldNotReachRevisionSaveWhenPostSaveFails() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null);
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));
        when(clockPort.now()).thenReturn(EDITED_AT);
        when(communityPostRepositoryPort.save(any(CommunityPost.class)))
                .thenThrow(new RuntimeException("Simulated post save database failure"));

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "New updated caption"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated post save database failure");

        verify(communityPostRepositoryPort).save(existingPost);
        verify(communityPostRevisionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should fail closed when publication settings are missing from repository on edit")
    void shouldFailClosedWhenSettingsMissingOnEdit() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null);
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));
        when(clockPort.now()).thenReturn(EDITED_AT);
        when(communitySettingsRepositoryPort.getSettings()).thenReturn(null);

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "New caption without settings"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Community publication settings are not configured.");

        verify(communityPostRepositoryPort, never()).save(any());
        verify(communityPostRevisionRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should fail closed when publication mode is null/corrupt on edit")
    void shouldFailClosedWhenPublicationModeIsNullOnEdit() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CommunityPostStatus.PUBLISHED, CREATED_AT, CREATED_AT, null);
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));
        when(clockPort.now()).thenReturn(EDITED_AT);
        CommunitySettings mockSettings = mock(CommunitySettings.class);
        when(mockSettings.getPublicationMode()).thenReturn(null);
        when(communitySettingsRepositoryPort.getSettings()).thenReturn(mockSettings);

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "New caption with corrupt settings"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Community publication settings are not configured.");

        verify(communityPostRepositoryPort, never()).save(any());
        verify(communityPostRevisionRepositoryPort, never()).save(any());
    }
}
