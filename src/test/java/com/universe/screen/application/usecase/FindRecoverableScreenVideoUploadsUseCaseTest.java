package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.FindRecoverableScreenVideoUploadsQuery;
import com.universe.screen.application.dto.RecoverableVideoUploadPageDTO;
import com.universe.screen.application.dto.RecoverableVideoUploadView;
import com.universe.screen.application.dto.RecoveryClassification;
import com.universe.screen.application.dto.ScreenMediaAssetInspectionDTO;
import com.universe.screen.application.dto.ScreenUploadSessionInfoDTO;
import com.universe.screen.application.dto.ScreenUploadSessionPageDTO;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FindRecoverableScreenVideoUploadsUseCase Unit Tests")
class FindRecoverableScreenVideoUploadsUseCaseTest {

    @Mock
    private ScreenMediaValidationPort mediaValidationPort;

    @Mock
    private ScreenEntryRepositoryPort entryRepository;

    private FindRecoverableScreenVideoUploadsUseCase useCase;

    private final String actor = "admin-user-1";
    private final UUID titleId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-20T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new FindRecoverableScreenVideoUploadsUseCase(mediaValidationPort, entryRepository);
    }

    private ScreenUploadSessionInfoDTO buildSession(String clientTag, UUID registeredAssetId) {
        return new ScreenUploadSessionInfoDTO(
                sessionId,
                "COMPLETED",
                actor,
                "screen",
                "SCREEN_ENTRY",
                entryId,
                registeredAssetId,
                "sample-video.mp4",
                1024L,
                clientTag,
                now.minusSeconds(60),
                now
        );
    }

    private ScreenEntry buildDraftEntry(UUID id, UUID videoAssetId) {
        return ScreenEntry.createDraft(
                id,
                titleId,
                "entry-slug",
                ScreenEntryType.EPISODE,
                1,
                "Test Entry Title",
                "Synopsis",
                videoAssetId,
                null,
                120L,
                now
        );
    }

    // =========================================================================
    // 1. ACTOR ISOLATION & QUERY SCOPING
    // =========================================================================

    @Test
    @DisplayName("Actor Isolation: delegates strictly to media port using authenticatedActor")
    void shouldIsolateSessionsByAuthenticatedActor() {
        FindRecoverableScreenVideoUploadsQuery query = FindRecoverableScreenVideoUploadsQuery.of("actor-alice", null, 0, 20);

        when(mediaValidationPort.findCompletedUploadSessions("actor-alice", null, 0, 20))
                .thenReturn(ScreenUploadSessionPageDTO.empty(0, 20));

        RecoverableVideoUploadPageDTO page = useCase.execute(query);

        assertThat(page.items()).isEmpty();
        verify(mediaValidationPort).findCompletedUploadSessions("actor-alice", null, 0, 20);
        verify(mediaValidationPort, never()).findCompletedUploadSessions(eq("actor-bob"), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Actor Isolation: SUPER_ADMIN principal is passed without bypass")
    void shouldNotBypassActorIsolationForSuperAdmin() {
        FindRecoverableScreenVideoUploadsQuery query = FindRecoverableScreenVideoUploadsQuery.of("superadmin-uuid", entryId, 1, 10);

        when(mediaValidationPort.findCompletedUploadSessions("superadmin-uuid", entryId, 1, 10))
                .thenReturn(ScreenUploadSessionPageDTO.empty(1, 10));

        RecoverableVideoUploadPageDTO page = useCase.execute(query);

        assertThat(page.items()).isEmpty();
        verify(mediaValidationPort).findCompletedUploadSessions("superadmin-uuid", entryId, 1, 10);
    }

    // =========================================================================
    // 2. UNATTACHED CLASSIFICATION & INTENT EVALUATION
    // =========================================================================

    @Test
    @DisplayName("UNATTACHED: Initial upload when target entry has NO video -> canRetry=true, canDiscard=true")
    void shouldClassifyUnattachedInitialUploadWhenTargetEntryHasNoVideo() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, null);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ACTIVE")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(Collections.emptyList());

        FindRecoverableScreenVideoUploadsQuery query = FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20);
        RecoverableVideoUploadPageDTO result = useCase.execute(query);

        assertThat(result.items()).hasSize(1);
        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.UNATTACHED);
        assertThat(view.intent()).isEqualTo("INITIAL");
        assertThat(view.replacementExpectedAssetId()).isNull();
        assertThat(view.targetEntryId()).isEqualTo(entryId);
        assertThat(view.targetEntryTitle()).isEqualTo("Test Entry Title");
        assertThat(view.targetTitleId()).isEqualTo(titleId);
        assertThat(view.canRetry()).isTrue();
        assertThat(view.canDiscard()).isTrue();
    }

    @Test
    @DisplayName("UNATTACHED: Initial upload when target entry ALREADY has a video -> canRetry=false, canDiscard=true")
    void shouldClassifyUnattachedInitialUploadWhenTargetEntryAlreadyHasVideo() {
        UUID existingVideoId = UUID.randomUUID();
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, existingVideoId);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ACTIVE")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(Collections.emptyList());

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.UNATTACHED);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isTrue();
    }

    @Test
    @DisplayName("UNATTACHED: Replacement upload matching current target entry video -> canRetry=true, canDiscard=true")
    void shouldClassifyUnattachedReplacementUploadWhenMatchingCurrentVideo() {
        UUID oldVideoId = UUID.randomUUID();
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-replacement:" + oldVideoId, assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, oldVideoId);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ACTIVE")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(Collections.emptyList());

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.UNATTACHED);
        assertThat(view.intent()).isEqualTo("REPLACEMENT");
        assertThat(view.replacementExpectedAssetId()).isEqualTo(oldVideoId);
        assertThat(view.canRetry()).isTrue();
        assertThat(view.canDiscard()).isTrue();
    }

    @Test
    @DisplayName("UNATTACHED: Replacement upload mismatched with current entry video -> canRetry=false, canDiscard=true")
    void shouldClassifyUnattachedReplacementUploadWhenMismatchedCurrentVideo() {
        UUID oldVideoId = UUID.randomUUID();
        UUID differentCurrentVideoId = UUID.randomUUID();
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-replacement:" + oldVideoId, assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, differentCurrentVideoId);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ACTIVE")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(Collections.emptyList());

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.UNATTACHED);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isTrue();
    }

    // =========================================================================
    // 3. ATTACHED & REFERENCED CLASSIFICATIONS
    // =========================================================================

    @Test
    @DisplayName("ATTACHED_TO_TARGET: registered video is currently referenced by target entry -> canRetry=false, canDiscard=false")
    void shouldClassifyAttachedToTarget() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, assetId);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ACTIVE")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(List.of(entryId));

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.ATTACHED_TO_TARGET);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isFalse();
    }

    @Test
    @DisplayName("REFERENCED_ELSEWHERE: registered video is referenced by another entry -> canRetry=false, canDiscard=false")
    void shouldClassifyReferencedElsewhere() {
        UUID otherEntryId = UUID.randomUUID();
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, null);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ACTIVE")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(List.of(otherEntryId));

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.REFERENCED_ELSEWHERE);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isFalse();
    }

    // =========================================================================
    // 4. TARGET MISSING & ALREADY DISCARDED
    // =========================================================================

    @Test
    @DisplayName("TARGET_MISSING: target entry does not exist, asset active & unreferenced -> canDiscard=true, canRetry=false")
    void shouldClassifyTargetMissing() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.empty());
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ACTIVE")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(Collections.emptyList());

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.TARGET_MISSING);
        assertThat(view.targetEntryTitle()).isEqualTo(FindRecoverableScreenVideoUploadsUseCase.TARGET_DELETED_TITLE);
        assertThat(view.targetTitleId()).isNull();
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isTrue();
    }

    @Test
    @DisplayName("ALREADY_DISCARDED: asset status is DELETED in media with no ScreenEntry references -> canDiscard=false, canRetry=false")
    void shouldClassifyAlreadyDiscardedWhenUnreferenced() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, null);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "DELETED")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(Collections.emptyList());

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.ALREADY_DISCARDED);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isFalse();
        verify(entryRepository).findEntryIdsByVideoMediaAssetId(assetId);
    }

    @Test
    @DisplayName("INCONSISTENT: asset status is DELETED in media but still referenced by ScreenEntry -> canDiscard=false, canRetry=false")
    void shouldClassifyDeletedAssetWithReferencesAsInconsistent() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, null);
        UUID referencingEntryId = UUID.randomUUID();

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "DELETED")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(List.of(referencingEntryId));

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.INCONSISTENT);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isFalse();
        verify(entryRepository).findEntryIdsByVideoMediaAssetId(assetId);
    }

    // =========================================================================
    // 5. INCONSISTENT STATES
    // =========================================================================

    @Test
    @DisplayName("INCONSISTENT: session has null registeredAssetId")
    void shouldClassifyInconsistentWhenRegisteredAssetIdIsNull() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", null);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(buildDraftEntry(entryId, null)));

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.INCONSISTENT);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isFalse();
        verify(mediaValidationPort, never()).getMediaAssetInspection(any());
    }

    @Test
    @DisplayName("INCONSISTENT: asset not found in media system")
    void shouldClassifyInconsistentWhenAssetMissingInMedia() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(buildDraftEntry(entryId, null)));
        when(mediaValidationPort.getMediaAssetInspection(assetId)).thenReturn(Optional.empty());

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.INCONSISTENT);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isFalse();
    }

    @Test
    @DisplayName("INCONSISTENT: asset mediaType is not VIDEO (e.g. IMAGE)")
    void shouldClassifyInconsistentWhenAssetIsNotVideo() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(buildDraftEntry(entryId, null)));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "IMAGE", "ACTIVE")));

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.INCONSISTENT);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isFalse();
    }

    @Test
    @DisplayName("INCONSISTENT: asset status is ARCHIVED (not ACTIVE or DELETED)")
    void shouldClassifyInconsistentWhenAssetIsArchived() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(buildDraftEntry(entryId, null)));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ARCHIVED")));

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.classification()).isEqualTo(RecoveryClassification.INCONSISTENT);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isFalse();
    }

    // =========================================================================
    // 6. SAFE CLIENT TAG FALLBACK & ZERO PESSIMISTIC LOCKING
    // =========================================================================

    @Test
    @DisplayName("Intent Fallback: unknown client tag safely defaults to UNKNOWN with canRetry=false and canDiscard=true")
    void shouldFallbackSafelyOnUnknownClientTag() {
        ScreenUploadSessionInfoDTO session = buildSession("unrecognized-custom-tag-123", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, null);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ACTIVE")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(Collections.emptyList());

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.intent()).isEqualTo("UNKNOWN");
        assertThat(view.classification()).isEqualTo(RecoveryClassification.UNATTACHED);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isTrue();
    }

    @Test
    @DisplayName("Intent Fallback: malformed replacement client tag fails closed to UNKNOWN with canRetry=false")
    void shouldClassifyMalformedReplacementAsUnknownWithCanRetryFalse() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-replace:not-a-valid-uuid", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, null);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ACTIVE")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(Collections.emptyList());

        RecoverableVideoUploadPageDTO result = useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        RecoverableVideoUploadView view = result.items().get(0);
        assertThat(view.intent()).isEqualTo("UNKNOWN");
        assertThat(view.replacementExpectedAssetId()).isNull();
        assertThat(view.classification()).isEqualTo(RecoveryClassification.UNATTACHED);
        assertThat(view.canRetry()).isFalse();
        assertThat(view.canDiscard()).isTrue();
    }

    @Test
    @DisplayName("Zero Locking: use case strictly calls non-locking read methods")
    void shouldStrictlyUseNonLockingRepositoryMethods() {
        ScreenUploadSessionInfoDTO session = buildSession("screen-video-initial", assetId);
        ScreenUploadSessionPageDTO pageDTO = new ScreenUploadSessionPageDTO(List.of(session), 0, 20, 1L, 1, true, true);
        ScreenEntry entry = buildDraftEntry(entryId, null);

        when(mediaValidationPort.findCompletedUploadSessions(actor, null, 0, 20)).thenReturn(pageDTO);
        when(entryRepository.findById(entryId)).thenReturn(Optional.of(entry));
        when(mediaValidationPort.getMediaAssetInspection(assetId))
                .thenReturn(Optional.of(new ScreenMediaAssetInspectionDTO(assetId, "VIDEO", "ACTIVE")));
        when(entryRepository.findEntryIdsByVideoMediaAssetId(assetId)).thenReturn(Collections.emptyList());

        useCase.execute(FindRecoverableScreenVideoUploadsQuery.of(actor, null, 0, 20));

        verify(entryRepository).findById(entryId);
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository).findEntryIdsByVideoMediaAssetId(assetId);
        verify(entryRepository, never()).findEntryIdsByVideoMediaAssetIdForUpdate(any());
        verify(mediaValidationPort).getMediaAssetInspection(assetId);
        verify(mediaValidationPort, never()).lockAndInspectVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Validation: null query throws NullPointerException")
    void shouldThrowWhenQueryIsNull() {
        assertThatThrownBy(() -> useCase.execute(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("FindRecoverableScreenVideoUploadsQuery cannot be null.");
    }
}
