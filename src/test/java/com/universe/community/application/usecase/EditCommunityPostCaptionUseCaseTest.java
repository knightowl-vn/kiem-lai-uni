package com.universe.community.application.usecase;

import com.universe.community.application.command.EditCommunityPostCaptionCommand;
import com.universe.community.application.port.out.CommunityPostRepositoryPort;
import com.universe.community.application.port.out.CommunityPostRevisionRepositoryPort;
import com.universe.community.domain.CommunityPost;
import com.universe.community.domain.CommunityPostRevision;
import com.universe.community.domain.exception.CommunityPostNotFoundException;
import com.universe.community.domain.exception.CommunityPostUnauthorizedException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EditCommunityPostCaptionUseCaseTest {

    @Mock
    private CommunityPostRepositoryPort communityPostRepositoryPort;

    @Mock
    private CommunityPostRevisionRepositoryPort communityPostRevisionRepositoryPort;

    @Mock
    private IdGeneratorPort idGeneratorPort;

    @Mock
    private ClockPort clockPort;

    private EditCommunityPostCaptionUseCase useCase;

    private static final UUID POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID AUTHOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID REVISION_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final Instant CREATED_AT = Instant.parse("2026-09-29T10:00:00Z");
    private static final Instant EDITED_AT = Instant.parse("2026-09-29T10:10:00Z");

    @BeforeEach
    void setUp() {
        useCase = new EditCommunityPostCaptionUseCase(
                communityPostRepositoryPort,
                communityPostRevisionRepositoryPort,
                idGeneratorPort,
                clockPort
        );
    }

    @Test
    @DisplayName("Should successfully edit caption and archive prior caption into revision")
    void shouldEditCaptionAndArchiveRevision() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CREATED_AT);

        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));
        when(clockPort.now()).thenReturn(EDITED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);
        when(communityPostRepositoryPort.save(any(CommunityPost.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "New updated caption"
        );

        CommunityPost updated = useCase.execute(command);

        assertThat(updated.getCaption()).isEqualTo("New updated caption");
        assertThat(updated.getContentVersion()).isEqualTo(1);
        assertThat(updated.getUpdatedAt()).isEqualTo(EDITED_AT);

        // Verify save order: post saved first, revision saved second
        org.mockito.InOrder inOrderVerifier = inOrder(communityPostRepositoryPort, communityPostRevisionRepositoryPort);
        inOrderVerifier.verify(communityPostRepositoryPort).save(existingPost);

        ArgumentCaptor<CommunityPostRevision> revCaptor = ArgumentCaptor.forClass(CommunityPostRevision.class);
        inOrderVerifier.verify(communityPostRevisionRepositoryPort).save(revCaptor.capture());
        CommunityPostRevision savedRevision = revCaptor.getValue();

        assertThat(savedRevision.getId()).isEqualTo(REVISION_ID);
        assertThat(savedRevision.getPostId()).isEqualTo(POST_ID);
        assertThat(savedRevision.getRevisionNumber()).isEqualTo(1);
        assertThat(savedRevision.getEditorUserId()).isEqualTo(AUTHOR_ID);
        assertThat(savedRevision.getPreviousCaption()).isEqualTo("Old caption");
        assertThat(savedRevision.getCaption()).isEqualTo("New updated caption");
        assertThat(savedRevision.getEditedAt()).isEqualTo(EDITED_AT);
    }

    @Test
    @DisplayName("Should be an idempotent no-op when new caption is identical")
    void shouldBeNoOpWhenCaptionIsIdentical() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CREATED_AT);

        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));
        when(clockPort.now()).thenReturn(EDITED_AT);

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                AUTHOR_ID,
                "  Old caption  "
        );

        CommunityPost result = useCase.execute(command);

        assertThat(result.getCaption()).isEqualTo("Old caption");
        assertThat(result.getContentVersion()).isEqualTo(0);
        assertThat(result.getUpdatedAt()).isEqualTo(CREATED_AT);

        verify(communityPostRevisionRepositoryPort, never()).save(any());
        verify(communityPostRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should throw CommunityPostNotFoundException when post does not exist")
    void shouldThrowNotFoundWhenPostMissing() {
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
    @DisplayName("Should throw CommunityPostUnauthorizedException when editor is not the author")
    void shouldThrowUnauthorizedWhenEditorIsNotAuthor() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CREATED_AT);
        when(communityPostRepositoryPort.findByIdForUpdate(POST_ID)).thenReturn(Optional.of(existingPost));

        EditCommunityPostCaptionCommand command = new EditCommunityPostCaptionCommand(
                POST_ID,
                OTHER_USER_ID,
                "Malicious caption"
        );

        assertThatThrownBy(() -> useCase.execute(command))
                .isInstanceOf(CommunityPostUnauthorizedException.class);

        verify(communityPostRevisionRepositoryPort, never()).save(any());
        verify(communityPostRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Should not reach revision save when post save fails")
    void shouldNotReachRevisionSaveWhenPostSaveFails() {
        CommunityPost existingPost = CommunityPost.create(POST_ID, AUTHOR_ID, "Old caption", null, CREATED_AT);
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
}
