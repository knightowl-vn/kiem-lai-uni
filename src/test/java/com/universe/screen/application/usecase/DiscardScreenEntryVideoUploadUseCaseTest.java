package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadCommand;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult;
import com.universe.screen.application.dto.DiscardScreenEntryVideoUploadResult.DiscardOutcome;
import com.universe.screen.application.dto.ScreenMediaAssetInspectionDTO;
import com.universe.screen.application.dto.ScreenUploadSessionInfoDTO;
import com.universe.screen.application.exceptions.InvalidScreenMediaAssetTypeException;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenMediaAssetNotFoundException;
import com.universe.screen.application.exceptions.ScreenUploadSessionNotFoundException;
import com.universe.screen.application.exceptions.ScreenVideoAlreadyReferencedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardAccessDeniedException;
import com.universe.screen.application.exceptions.ScreenVideoDiscardValidationException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
@DisplayName("DiscardScreenEntryVideoUploadUseCase Unit Tests")
class DiscardScreenEntryVideoUploadUseCaseTest {

    @Mock
    private ScreenEntryRepositoryPort entryRepository;

    @Mock
    private ScreenMediaValidationPort mediaValidationPort;

    private DiscardScreenEntryVideoUploadUseCase useCase;

    private final UUID titleId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID videoMediaAssetId = UUID.randomUUID();
    private final String actor = "admin-actor-1";

    private ScreenEntry entry;
    private ScreenUploadSessionInfoDTO session;

    @BeforeEach
    void setUp() {
        useCase = new DiscardScreenEntryVideoUploadUseCase(
                entryRepository,
                mediaValidationPort
        );

        entry = ScreenEntry.createDraft(
                entryId,
                titleId,
                "ep-1",
                ScreenEntryType.EPISODE,
                1,
                "Episode 1",
                Instant.now()
        );

        session = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                actor,
                "screen",
                "SCREEN_ENTRY",
                entryId,
                videoMediaAssetId
        );
    }

    private DiscardScreenEntryVideoUploadCommand validCommand() {
        return new DiscardScreenEntryVideoUploadCommand(
                titleId,
                entryId,
                sessionId,
                videoMediaAssetId,
                actor
        );
    }

    // =========================================================================
    // 1. HAPPY PATH: ACTIVE DISCARD & ALREADY_DISCARDED IDEMPOTENCY
    // =========================================================================

    @Test
    @DisplayName("Active Discard: unreferenced ACTIVE video is soft-deleted and returns DISCARDED")
    void shouldSuccessfullyDiscardActiveUnreferencedVideo() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "ACTIVE"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId))
                .thenReturn(Collections.emptyList());

        DiscardScreenEntryVideoUploadResult result = useCase.execute(validCommand());

        assertThat(result).isNotNull();
        assertThat(result.videoMediaAssetId()).isEqualTo(videoMediaAssetId);
        assertThat(result.outcome()).isEqualTo(DiscardScreenEntryVideoUploadResult.DiscardOutcome.DISCARDED);

        verify(mediaValidationPort).deleteMediaAsset(videoMediaAssetId);
        verify(entryRepository, never()).findById(any());

        // Verify strict physical lock and execution order:
        // 1. Upload session lookup & actor check
        // 3. MediaAsset exclusive lock (Step 5)
        // 4. Target ScreenEntry exclusive row lock (Step 6)
        // 5. Referencing ScreenEntries current-read lock (Step 7)
        // 6. Media soft-delete (Step 8)
        InOrder inOrder = inOrder(mediaValidationPort, entryRepository);
        inOrder.verify(mediaValidationPort).getUploadSession(sessionId);
        inOrder.verify(mediaValidationPort).lockAndInspectVideoMediaAsset(videoMediaAssetId);
        inOrder.verify(entryRepository).findByIdForUpdate(entryId);
        inOrder.verify(entryRepository).findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId);
        inOrder.verify(mediaValidationPort).deleteMediaAsset(videoMediaAssetId);
    }

    @Test
    @DisplayName("Idempotency: unreferenced DELETED video returns ALREADY_DISCARDED without deleting")
    void shouldReturnAlreadyDiscardedWhenAssetIsDeletedAndUnreferenced() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "DELETED"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId))
                .thenReturn(Collections.emptyList());

        DiscardScreenEntryVideoUploadResult result = useCase.execute(validCommand());

        assertThat(result).isNotNull();
        assertThat(result.videoMediaAssetId()).isEqualTo(videoMediaAssetId);
        assertThat(result.outcome()).isEqualTo(DiscardScreenEntryVideoUploadResult.DiscardOutcome.ALREADY_DISCARDED);

        verify(mediaValidationPort, never()).deleteMediaAsset(any());
        verify(entryRepository, never()).findById(any());

        InOrder inOrder = inOrder(mediaValidationPort, entryRepository);
        inOrder.verify(mediaValidationPort).getUploadSession(sessionId);
        inOrder.verify(mediaValidationPort).lockAndInspectVideoMediaAsset(videoMediaAssetId);
        inOrder.verify(entryRepository).findByIdForUpdate(entryId);
        inOrder.verify(entryRepository).findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId);
    }

    // =========================================================================
    // 2. REFERENCE GUARD REJECTION
    // =========================================================================

    @Test
    @DisplayName("Reference Guard: ACTIVE video referenced by ScreenEntry is rejected without soft-delete")
    void shouldRejectDiscardWhenActiveAssetIsReferencedByScreenEntry() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "ACTIVE"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId))
                .thenReturn(List.of(entryId));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoAlreadyReferencedException.class)
                .hasMessageContaining(videoMediaAssetId.toString())
                .hasMessageNotContaining(entryId.toString())
                .satisfies(ex -> {
                    ScreenVideoAlreadyReferencedException refEx = (ScreenVideoAlreadyReferencedException) ex;
                    assertThat(refEx.getVideoMediaAssetId()).isEqualTo(videoMediaAssetId);
                    assertThat(refEx.getReferencingEntryIds()).containsExactly(entryId);
                });

        verify(entryRepository, never()).findById(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Reference Guard: DELETED video referenced by ScreenEntry is rejected before ALREADY_DISCARDED")
    void shouldRejectDiscardWhenDeletedAssetIsReferencedByScreenEntry() {
        UUID referencingEntryId = UUID.randomUUID();
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "DELETED"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId))
                .thenReturn(List.of(referencingEntryId));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoAlreadyReferencedException.class)
                .hasMessageContaining(videoMediaAssetId.toString())
                .hasMessageNotContaining(referencingEntryId.toString())
                .satisfies(ex -> {
                    ScreenVideoAlreadyReferencedException refEx = (ScreenVideoAlreadyReferencedException) ex;
                    assertThat(refEx.getVideoMediaAssetId()).isEqualTo(videoMediaAssetId);
                    assertThat(refEx.getReferencingEntryIds()).containsExactly(referencingEntryId);
                });

        verify(entryRepository, never()).findById(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    // =========================================================================
    // 3. SESSION BINDING & PROVENANCE CHECKS
    // =========================================================================

    @Test
    @DisplayName("Session Mismatch: missing upload session throws ScreenUploadSessionNotFoundException")
    void shouldRejectWhenUploadSessionNotFound() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenUploadSessionNotFoundException.class)
                .hasMessageContaining(sessionId.toString());

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findById(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"INITIATED", "ALLOCATED", "COMPLETING", "ABORTED"})
    @DisplayName("Session Mismatch: non-COMPLETED session status is rejected")
    void shouldRejectWhenUploadSessionNotCompleted(String invalidStatus) {
        ScreenUploadSessionInfoDTO nonCompletedSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                invalidStatus,
                actor,
                "screen",
                "SCREEN_ENTRY",
                entryId,
                videoMediaAssetId
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(nonCompletedSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardValidationException.class)
                .hasMessageContaining("UPLOAD_SESSION_NOT_COMPLETED")
                .hasMessageContaining(invalidStatus);

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findById(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Session Mismatch: consumerContext not 'screen' is rejected")
    void shouldRejectWhenConsumerContextMismatch() {
        ScreenUploadSessionInfoDTO wrongContextSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                actor,
                "novel",
                "SCREEN_ENTRY",
                entryId,
                videoMediaAssetId
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(wrongContextSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardValidationException.class)
                .hasMessageContaining("INVALID_CONSUMER_CONTEXT")
                .hasMessageContaining("novel");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findById(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Session Mismatch: targetEntityType not 'SCREEN_ENTRY' is rejected")
    void shouldRejectWhenTargetEntityTypeMismatch() {
        ScreenUploadSessionInfoDTO wrongTargetTypeSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                actor,
                "screen",
                "NOVEL_CHAPTER",
                entryId,
                videoMediaAssetId
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(wrongTargetTypeSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardValidationException.class)
                .hasMessageContaining("INVALID_TARGET_ENTITY_TYPE")
                .hasMessageContaining("NOVEL_CHAPTER");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findById(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Session Mismatch: targetEntityId mismatch with requested entryId is rejected")
    void shouldRejectWhenTargetEntityIdMismatch() {
        UUID otherEntryId = UUID.randomUUID();
        ScreenUploadSessionInfoDTO wrongTargetIdSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                actor,
                "screen",
                "SCREEN_ENTRY",
                otherEntryId,
                videoMediaAssetId
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(wrongTargetIdSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardValidationException.class)
                .hasMessageContaining("TARGET_ENTRY_ID_MISMATCH");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findById(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Session Mismatch: registeredAssetId mismatch with requested videoMediaAssetId is rejected")
    void shouldRejectWhenRegisteredAssetIdMismatch() {
        UUID otherAssetId = UUID.randomUUID();
        ScreenUploadSessionInfoDTO wrongAssetSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                actor,
                "screen",
                "SCREEN_ENTRY",
                entryId,
                otherAssetId
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(wrongAssetSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardValidationException.class)
                .hasMessageContaining("REGISTERED_ASSET_ID_MISMATCH");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findById(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Session Mismatch: registeredAssetId null on session is rejected")
    void shouldRejectWhenRegisteredAssetIdIsNull() {
        ScreenUploadSessionInfoDTO nullAssetSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                actor,
                "screen",
                "SCREEN_ENTRY",
                entryId,
                null
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(nullAssetSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardValidationException.class)
                .hasMessageContaining("REGISTERED_ASSET_ID_MISMATCH");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findById(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    // =========================================================================
    // 4. OWNERSHIP / ACTOR AUTHORIZATION
    // =========================================================================

    @Test
    @DisplayName("Ownership Rejection: actor not matching session initiator throws ScreenVideoDiscardAccessDeniedException with safe message")
    void shouldRejectWhenAuthenticatedActorDoesNotMatchInitiator() {
        ScreenUploadSessionInfoDTO otherInitiatorSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                "original-initiator",
                "screen",
                "SCREEN_ENTRY",
                entryId,
                videoMediaAssetId
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(otherInitiatorSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardAccessDeniedException.class)
                .hasMessageContaining("not authorized")
                .hasMessageNotContaining("original-initiator");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findById(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Ownership Rejection: non-owner rejected before checking non-COMPLETED session status (no info leak)")
    void shouldRejectNonOwnerEvenWhenSessionStatusIsInvalid() {
        ScreenUploadSessionInfoDTO otherInitiatorSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "INITIATED",
                "original-initiator",
                "screen",
                "SCREEN_ENTRY",
                entryId,
                videoMediaAssetId
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(otherInitiatorSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardAccessDeniedException.class)
                .hasMessageContaining("not authorized")
                .hasMessageNotContaining("INITIATED")
                .hasMessageNotContaining("UPLOAD_SESSION_NOT_COMPLETED");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Ownership Rejection: non-owner rejected before checking mismatched consumer context (no info leak)")
    void shouldRejectNonOwnerEvenWhenConsumerContextIsInvalid() {
        ScreenUploadSessionInfoDTO otherInitiatorSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                "original-initiator",
                "novel",
                "SCREEN_ENTRY",
                entryId,
                videoMediaAssetId
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(otherInitiatorSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardAccessDeniedException.class)
                .hasMessageContaining("not authorized")
                .hasMessageNotContaining("novel")
                .hasMessageNotContaining("INVALID_CONSUMER_CONTEXT");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Ownership Rejection: non-owner rejected before checking target entity type or ID (no info leak)")
    void shouldRejectNonOwnerEvenWhenTargetEntityIsMismatched() {
        ScreenUploadSessionInfoDTO otherInitiatorSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                "original-initiator",
                "screen",
                "NOVEL_CHAPTER",
                UUID.randomUUID(),
                videoMediaAssetId
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(otherInitiatorSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardAccessDeniedException.class)
                .hasMessageContaining("not authorized")
                .hasMessageNotContaining("NOVEL_CHAPTER")
                .hasMessageNotContaining("INVALID_TARGET_ENTITY_TYPE");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Ownership Rejection: non-owner rejected before checking registered asset ID (no info leak)")
    void shouldRejectNonOwnerEvenWhenRegisteredAssetIsMismatched() {
        ScreenUploadSessionInfoDTO otherInitiatorSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                "original-initiator",
                "screen",
                "SCREEN_ENTRY",
                entryId,
                UUID.randomUUID()
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(otherInitiatorSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardAccessDeniedException.class)
                .hasMessageContaining("not authorized")
                .hasMessageNotContaining("REGISTERED_ASSET_ID_MISMATCH");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Ownership Rejection: non-owner rejected before checking title existence (no info leak)")
    void shouldRejectNonOwnerEvenWhenTitleDoesNotExist() {
        ScreenUploadSessionInfoDTO otherInitiatorSession = new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                "original-initiator",
                "screen",
                "SCREEN_ENTRY",
                entryId,
                videoMediaAssetId
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(otherInitiatorSession));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardAccessDeniedException.class)
                .hasMessageContaining("not authorized");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Ownership Rejection: null authenticated actor is rejected fail-closed")
    void shouldRejectWhenAuthenticatedActorIsNull() {
        DiscardScreenEntryVideoUploadCommand nullActorCmd = new DiscardScreenEntryVideoUploadCommand(
                titleId, entryId, sessionId, videoMediaAssetId, null
        );

        assertThatThrownBy(() -> useCase.execute(nullActorCmd))
                .isInstanceOf(ScreenVideoDiscardValidationException.class)
                .hasMessageContaining("AUTHENTICATED_ACTOR_REQUIRED");

        verify(mediaValidationPort, never()).getUploadSession(any());
        verify(entryRepository, never()).findById(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Ownership Rejection: blank authenticated actor is rejected fail-closed")
    void shouldRejectWhenAuthenticatedActorIsBlank() {
        DiscardScreenEntryVideoUploadCommand blankActorCmd = new DiscardScreenEntryVideoUploadCommand(
                titleId, entryId, sessionId, videoMediaAssetId, "   "
        );

        assertThatThrownBy(() -> useCase.execute(blankActorCmd))
                .isInstanceOf(ScreenVideoDiscardValidationException.class)
                .hasMessageContaining("AUTHENTICATED_ACTOR_REQUIRED");

        verify(mediaValidationPort, never()).getUploadSession(any());
        verify(entryRepository, never()).findById(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
    }

    // =========================================================================
    // 5. TARGET TITLE & ENTRY CHECKS
    // =========================================================================

    @Test
    @DisplayName("Missing target title: non-existent ScreenTitle permits successful discard when unreferenced")
    void shouldDiscardWhenTargetTitleIsMissingAndUnreferenced() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "ACTIVE"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId)).thenReturn(List.of());

        DiscardScreenEntryVideoUploadResult result = useCase.execute(validCommand());

        assertThat(result.outcome()).isEqualTo(DiscardOutcome.DISCARDED);
        assertThat(result.videoMediaAssetId()).isEqualTo(videoMediaAssetId);
        verify(mediaValidationPort).deleteMediaAsset(videoMediaAssetId);
    }

    @Test
    @DisplayName("Missing target entry: non-existent ScreenEntry permits successful discard when unreferenced")
    void shouldDiscardWhenTargetEntryMissingInLockedLookup() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "ACTIVE"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId)).thenReturn(List.of());

        DiscardScreenEntryVideoUploadResult result = useCase.execute(validCommand());

        assertThat(result.outcome()).isEqualTo(DiscardOutcome.DISCARDED);
        assertThat(result.videoMediaAssetId()).isEqualTo(videoMediaAssetId);

        InOrder inOrder = inOrder(mediaValidationPort, entryRepository);
        inOrder.verify(mediaValidationPort).getUploadSession(sessionId);
        inOrder.verify(mediaValidationPort).lockAndInspectVideoMediaAsset(videoMediaAssetId);
        inOrder.verify(entryRepository).findByIdForUpdate(entryId);
        inOrder.verify(entryRepository).findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId);
        inOrder.verify(mediaValidationPort).deleteMediaAsset(videoMediaAssetId);
    }

    @Test
    @DisplayName("Missing target entry: referenced asset throws ScreenVideoAlreadyReferencedException without delete")
    void shouldRejectMissingTargetWhenAssetIsReferenced() {
        UUID referencingEntryId = UUID.randomUUID();
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "ACTIVE"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId))
                .thenReturn(List.of(referencingEntryId));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoAlreadyReferencedException.class)
                .hasMessageContaining(videoMediaAssetId.toString());

        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Missing target entry: already DELETED unreferenced asset returns ALREADY_DISCARDED")
    void shouldReturnAlreadyDiscardedWhenMissingTargetAndAssetAlreadyDeleted() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "DELETED"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId)).thenReturn(List.of());

        DiscardScreenEntryVideoUploadResult result = useCase.execute(validCommand());

        assertThat(result.outcome()).isEqualTo(DiscardOutcome.ALREADY_DISCARDED);
        assertThat(result.videoMediaAssetId()).isEqualTo(videoMediaAssetId);
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Wrong title from locked lookup: ScreenEntry belonging to different title throws ScreenEntryNotFoundException without delete")
    void shouldRejectWhenTargetHasWrongTitleInLockedLookup() {
        UUID differentTitleId = UUID.randomUUID();
        ScreenEntry entryWithChangedTitle = ScreenEntry.createDraft(
                entryId,
                differentTitleId,
                "ep-1",
                ScreenEntryType.EPISODE,
                1,
                "Episode 1",
                Instant.now()
        );
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "ACTIVE"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entryWithChangedTitle));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenEntryNotFoundException.class)
                .hasMessageContaining(entryId.toString());

        InOrder inOrder = inOrder(mediaValidationPort, entryRepository);
        inOrder.verify(mediaValidationPort).getUploadSession(sessionId);
        inOrder.verify(mediaValidationPort).lockAndInspectVideoMediaAsset(videoMediaAssetId);
        inOrder.verify(entryRepository).findByIdForUpdate(entryId);

        verify(entryRepository, never()).findById(any());
        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetIdForUpdate(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    // =========================================================================
    // 6. MEDIA ASSET TYPE & STATUS CHECKS
    // =========================================================================

    @Test
    @DisplayName("Media Asset Rejection: non-existent asset throws ScreenMediaAssetNotFoundException")
    void shouldRejectWhenAssetNotFoundInMedia() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenThrow(new ScreenMediaAssetNotFoundException(videoMediaAssetId));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenMediaAssetNotFoundException.class)
                .hasMessageContaining(videoMediaAssetId.toString());

        verify(entryRepository, never()).findById(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetIdForUpdate(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Media Asset Rejection: non-VIDEO asset type throws InvalidScreenMediaAssetTypeException")
    void shouldRejectWhenAssetTypeNotVideo() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "IMAGE", "ACTIVE"));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(InvalidScreenMediaAssetTypeException.class)
                .hasMessageContaining("IMAGE")
                .hasMessageContaining("VIDEO");

        verify(entryRepository, never()).findById(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetIdForUpdate(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Media Asset Rejection: ARCHIVED status is rejected as unsupported for discard")
    void shouldRejectWhenAssetStatusIsArchived() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "ARCHIVED"));

        assertThatThrownBy(() -> useCase.execute(validCommand()))
                .isInstanceOf(ScreenVideoDiscardValidationException.class)
                .hasMessageContaining("UNSUPPORTED_MEDIA_ASSET_STATUS")
                .hasMessageContaining("ARCHIVED");

        verify(entryRepository, never()).findById(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetIdForUpdate(any());
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    // =========================================================================
    // 7. TRANSACTION CONTRACT VERIFICATION
    // =========================================================================

    @Test
    @DisplayName("Transaction Contract: class is annotated with @Transactional")
    void shouldHaveTransactionalAnnotationOnClass() {
        Transactional annotation = DiscardScreenEntryVideoUploadUseCase.class.getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull();
    }

    // =========================================================================
    // 8. S3-3B2 SESSION-ROOTED DISCARD BEHAVIOR
    // =========================================================================

    @Test
    @DisplayName("Session-Rooted Discard: successfully discards unreferenced active video when target entry is missing")
    void sessionRooted_shouldDiscardWhenTargetEntryIsMissing() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "ACTIVE"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId)).thenReturn(List.of());

        DiscardScreenEntryVideoUploadCommand cmd = DiscardScreenEntryVideoUploadCommand.forSession(sessionId, actor);
        DiscardScreenEntryVideoUploadResult result = useCase.execute(cmd);

        assertThat(result.outcome()).isEqualTo(DiscardOutcome.DISCARDED);
        assertThat(result.videoMediaAssetId()).isEqualTo(videoMediaAssetId);

        InOrder inOrder = inOrder(mediaValidationPort, entryRepository);
        inOrder.verify(mediaValidationPort).getUploadSession(sessionId);
        inOrder.verify(mediaValidationPort).lockAndInspectVideoMediaAsset(videoMediaAssetId);
        inOrder.verify(entryRepository).findByIdForUpdate(entryId);
        inOrder.verify(entryRepository).findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId);
        inOrder.verify(mediaValidationPort).deleteMediaAsset(videoMediaAssetId);
    }

    @Test
    @DisplayName("Session-Rooted Discard: successfully discards unreferenced active video when target entry is present")
    void sessionRooted_shouldDiscardWhenTargetEntryIsPresent() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "ACTIVE"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId)).thenReturn(List.of());

        DiscardScreenEntryVideoUploadCommand cmd = DiscardScreenEntryVideoUploadCommand.forSession(sessionId, videoMediaAssetId, actor);
        DiscardScreenEntryVideoUploadResult result = useCase.execute(cmd);

        assertThat(result.outcome()).isEqualTo(DiscardOutcome.DISCARDED);
        assertThat(result.videoMediaAssetId()).isEqualTo(videoMediaAssetId);

        InOrder inOrder = inOrder(mediaValidationPort, entryRepository);
        inOrder.verify(mediaValidationPort).getUploadSession(sessionId);
        inOrder.verify(mediaValidationPort).lockAndInspectVideoMediaAsset(videoMediaAssetId);
        inOrder.verify(entryRepository).findByIdForUpdate(entryId);
        inOrder.verify(entryRepository).findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId);
        inOrder.verify(mediaValidationPort).deleteMediaAsset(videoMediaAssetId);
    }

    @Test
    @DisplayName("Session-Rooted Discard: returns ALREADY_DISCARDED when unreferenced asset is already DELETED")
    void sessionRooted_shouldReturnAlreadyDiscardedWhenAssetIsDeleted() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "DELETED"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId)).thenReturn(List.of());

        DiscardScreenEntryVideoUploadCommand cmd = DiscardScreenEntryVideoUploadCommand.forSession(sessionId, actor);
        DiscardScreenEntryVideoUploadResult result = useCase.execute(cmd);

        assertThat(result.outcome()).isEqualTo(DiscardOutcome.ALREADY_DISCARDED);
        assertThat(result.videoMediaAssetId()).isEqualTo(videoMediaAssetId);
        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Session-Rooted Discard: rejects with 409 conflict when video is referenced by any entry")
    void sessionRooted_shouldRejectWhenVideoIsReferenced() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        when(mediaValidationPort.lockAndInspectVideoMediaAsset(videoMediaAssetId))
                .thenReturn(new ScreenMediaAssetInspectionDTO(videoMediaAssetId, "VIDEO", "ACTIVE"));
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());
        UUID referencingEntryId = UUID.randomUUID();
        when(entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(videoMediaAssetId)).thenReturn(List.of(referencingEntryId));

        DiscardScreenEntryVideoUploadCommand cmd = DiscardScreenEntryVideoUploadCommand.forSession(sessionId, actor);

        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(ScreenVideoAlreadyReferencedException.class)
                .hasMessageContaining(videoMediaAssetId.toString());

        verify(mediaValidationPort, never()).deleteMediaAsset(any());
    }

    @Test
    @DisplayName("Session-Rooted Discard: rejects when client-provided videoMediaAssetId mismatches session registered asset")
    void sessionRooted_shouldRejectWhenAssetIdMismatches() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));
        UUID mismatchedAssetId = UUID.randomUUID();

        DiscardScreenEntryVideoUploadCommand cmd = DiscardScreenEntryVideoUploadCommand.forSession(sessionId, mismatchedAssetId, actor);

        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(ScreenVideoDiscardValidationException.class)
                .hasMessageContaining("REGISTERED_ASSET_ID_MISMATCH");

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("Session-Rooted Discard: rejects when non-owner actor attempts discard (fail-closed)")
    void sessionRooted_shouldRejectWhenNonOwnerAttemptsDiscard() {
        when(mediaValidationPort.getUploadSession(sessionId)).thenReturn(Optional.of(session));

        DiscardScreenEntryVideoUploadCommand cmd = DiscardScreenEntryVideoUploadCommand.forSession(sessionId, "impostor@universe.local");

        assertThatThrownBy(() -> useCase.execute(cmd))
                .isInstanceOf(ScreenVideoDiscardAccessDeniedException.class);

        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
        verify(entryRepository, never()).findByIdForUpdate(any());
    }
}
