package com.universe.screen.application.usecase;

import com.universe.media.contracts.dto.ChangeMediaVisibilityRequestDTO;
import com.universe.media.contracts.dto.MediaAssetDetailDTO;
import com.universe.media.contracts.dto.MediaAssetStatusDTO;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.dto.MediaUploadSessionResponseDTO;
import com.universe.media.contracts.dto.MediaVersionDTO;
import com.universe.media.contracts.dto.MediaVisibilityDTO;
import com.universe.media.contracts.interfaces.MediaContract;
import com.universe.screen.application.dto.ReconcileScreenVideoVisibilityResult;
import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoConflictException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleVideoConflictException;
import com.universe.screen.application.exceptions.ScreenVideoProvenanceException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenFranchiseMembership;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenPublicationStatus;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import com.universe.screen.infrastructure.media.ScreenMediaVisibilityAdapter;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
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
@DisplayName("Screen Video Visibility Lifecycle, Provenance, & Concurrency Matrix Tests")
class ScreenVideoVisibilityLifecycleTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepository;

    @Mock
    private ScreenEntryRepositoryPort entryRepository;

    @Mock
    private ScreenFranchiseMembershipRepositoryPort franchiseMembershipRepository;

    @Mock
    private ScreenMediaValidationPort mediaValidationPort;

    @Mock
    private ScreenMediaVisibilityPort mediaVisibilityPort;

    @Mock
    private MediaContract mediaContract;

    @Mock
    private ClockPort clockPort;

    private final UUID titleId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID entryId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID entryId2 = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID videoAssetId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final UUID supersededVideoAssetId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private final Instant t0 = Instant.parse("2026-10-06T10:00:00Z");
    private final Instant tPublish = Instant.parse("2026-10-06T11:00:00Z");
    private final Instant tArchive = Instant.parse("2026-10-06T12:00:00Z");

    @BeforeEach
    void setUp() {
    }

    @Nested
    @DisplayName("P0-A: Shared Asset Visibility Regression Scenarios")
    class SharedReferenceVisibilityTests {

        @Test
        @DisplayName("Scenario 1: Attaching shared asset A to draft Entry 2 must NOT demote A when Entry 1 is published")
        void scenario1_attachingSharedAssetToDraftEntry_doesNotDemote() {
            ScreenTitle draftTitle = ScreenTitle.createDraft(titleId, "series-1", "Series 1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            ScreenEntry draftEntry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);

            when(titleRepository.existsById(titleId)).thenReturn(true);
            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(draftTitle));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(draftEntry));
            when(clockPort.now()).thenReturn(tPublish);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            // Shared reference: another published entry references videoAssetId
            when(entryRepository.hasOtherPublishedReference(videoAssetId, entryId)).thenReturn(true);

            AttachScreenEntryVideoUseCase useCase = new AttachScreenEntryVideoUseCase(
                    titleRepository, entryRepository, mediaValidationPort, mediaVisibilityPort, clockPort
            );
            ScreenEntryDTO result = useCase.execute(titleId, entryId, videoAssetId);

            assertThat(result.videoMediaAssetId()).isEqualTo(videoAssetId);
            verify(mediaVisibilityPort, never()).makeVideoPrivate(videoAssetId);
            verify(mediaVisibilityPort, never()).makeVideoPublic(any());
        }

        @Test
        @DisplayName("Scenario 2: Replacing draft Entry 2 video with shared asset A must NOT demote A")
        void scenario2_replacingWithSharedAsset_doesNotDemote() {
            ScreenTitle draftTitle = ScreenTitle.createDraft(titleId, "series-1", "Series 1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            ScreenEntry draftEntry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            draftEntry.updateMedia(supersededVideoAssetId, null, t0);

            when(titleRepository.existsById(titleId)).thenReturn(true);
            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(draftTitle));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(draftEntry));
            when(clockPort.now()).thenReturn(tPublish);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            // videoAssetId is shared with another published entry
            when(entryRepository.hasOtherPublishedReference(videoAssetId, entryId)).thenReturn(true);
            // superseded asset is unreferenced elsewhere
            when(entryRepository.hasOtherPublishedReference(supersededVideoAssetId, entryId)).thenReturn(false);

            ReplaceScreenEntryVideoUseCase useCase = new ReplaceScreenEntryVideoUseCase(
                    titleRepository, entryRepository, mediaValidationPort, mediaVisibilityPort, clockPort
            );
            ScreenEntryDTO result = useCase.execute(titleId, entryId, videoAssetId, supersededVideoAssetId);

            assertThat(result.videoMediaAssetId()).isEqualTo(videoAssetId);
            verify(mediaVisibilityPort, never()).makeVideoPrivate(videoAssetId, entryId);
            // Superseded video was demoted
            verify(mediaVisibilityPort).makeVideoPrivate(supersededVideoAssetId, entryId);
        }

        @Test
        @DisplayName("Scenario 3: Reconciling draft Entry 2 with shared asset A must NOT demote A (returns KEPT_PUBLIC_BY_SHARED_REFERENCE)")
        void scenario3_reconcilingDraftEntry_preservesSharedPublicAsset() {
            ScreenTitle draftTitle = ScreenTitle.createDraft(titleId, "series-1", "Series 1", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            ScreenEntry draftEntry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            draftEntry.updateMedia(videoAssetId, null, t0);

            when(titleRepository.existsById(titleId)).thenReturn(true);
            when(entryRepository.findById(entryId)).thenReturn(Optional.of(draftEntry));
            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(draftTitle));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(draftEntry));

            // Shared reference exists
            when(entryRepository.hasOtherPublishedReference(videoAssetId, entryId)).thenReturn(true);

            ReconcileScreenEntryVideoVisibilityUseCase useCase = new ReconcileScreenEntryVideoVisibilityUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort
            );
            ReconcileScreenVideoVisibilityResult result = useCase.execute(titleId, entryId);

            assertThat(result.outcome()).isEqualTo(ReconcileScreenVideoVisibilityResult.Outcome.KEPT_PUBLIC_BY_SHARED_REFERENCE);
            assertThat(result.reconciledVisibility()).isEqualTo("PUBLIC");
            verify(mediaVisibilityPort, never()).makeVideoPrivate(videoAssetId);
        }

        @Test
        @DisplayName("Scenario 4: Publishing Entry 2 under draft Title 2 must NOT demote A while Entry 1 remains public")
        void scenario4_publishingUnderDraftTitle_preservesSharedAsset() {
            ScreenTitle draftTitle = ScreenTitle.createDraft(titleId, "series-2", "Series 2", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            ScreenEntry entry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            entry.updateMedia(videoAssetId, null, t0);

            when(entryRepository.findVideoMediaAssetIdById(entryId)).thenReturn(Optional.of(videoAssetId));
            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(draftTitle));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(tPublish);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            // Shared reference: another published entry references videoAssetId
            when(entryRepository.hasOtherPublishedReference(videoAssetId, entryId)).thenReturn(true);

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort, clockPort
            );
            ScreenEntryDTO result = useCase.execute(titleId, entryId);

            assertThat(result.status()).isEqualTo(ScreenPublicationStatus.PUBLISHED);
            verify(mediaVisibilityPort, never()).makeVideoPrivate(videoAssetId);
            verify(mediaVisibilityPort, never()).makeVideoPublic(any());
        }

        @Test
        @DisplayName("Scenario 5: Archiving Entry 1 keeps asset A public when another valid published reference remains")
        void scenario5_archivingEntry_keepsSharedAssetPublic() {
            ScreenEntry entry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            entry.updateMedia(videoAssetId, null, t0);
            entry.publish(tPublish);

            ScreenTitle title = ScreenTitle.createDraft(titleId, "series", "Series", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);

            when(titleRepository.existsById(titleId)).thenReturn(true);
            when(entryRepository.findVideoMediaAssetIdById(entryId)).thenReturn(Optional.of(videoAssetId));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(tArchive);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            // Another published reference exists!
            when(entryRepository.hasOtherPublishedReference(videoAssetId, entryId)).thenReturn(true);

            ArchiveScreenEntryUseCase useCase = new ArchiveScreenEntryUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort, clockPort
            );
            ScreenEntryDTO result = useCase.execute(titleId, entryId);

            assertThat(result.status()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
            verify(mediaVisibilityPort, never()).makeVideoPrivate(videoAssetId);
        }

        @Test
        @DisplayName("Scenario 6: Demotion is allowed only when no valid published Screen reference remains")
        void scenario6_demotionAllowedWhenNoOtherPublishedReferenceRemains() {
            ScreenEntry entry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            entry.updateMedia(videoAssetId, null, t0);
            entry.publish(tPublish);

            ScreenTitle title = ScreenTitle.createDraft(titleId, "series", "Series", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);

            when(titleRepository.existsById(titleId)).thenReturn(true);
            when(entryRepository.findVideoMediaAssetIdById(entryId)).thenReturn(Optional.of(videoAssetId));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(tArchive);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            // No other published reference remains
            when(entryRepository.hasOtherPublishedReference(videoAssetId, entryId)).thenReturn(false);

            ArchiveScreenEntryUseCase useCase = new ArchiveScreenEntryUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort, clockPort
            );
            ScreenEntryDTO result = useCase.execute(titleId, entryId);

            assertThat(result.status()).isEqualTo(ScreenPublicationStatus.ARCHIVED);
            verify(mediaVisibilityPort).makeVideoPrivate(videoAssetId, entryId);
        }
    }

    @Nested
    @DisplayName("P0-B: Global Lock Protocol Across Use Cases")
    class GlobalLockProtocolTests {

        @Test
        @DisplayName("PublishScreenEntry: locks MediaAsset FIRST, ScreenTitle SECOND, ScreenEntry THIRD")
        void publishEntry_enforcesGlobalLockHierarchy() {
            ScreenTitle pubTitle = ScreenTitle.createDraft(titleId, "series", "Series", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            pubTitle.publish(tPublish);
            ScreenEntry entry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            entry.updateMedia(videoAssetId, null, t0);

            when(entryRepository.findVideoMediaAssetIdById(entryId)).thenReturn(Optional.of(videoAssetId));
            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(pubTitle));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
            when(clockPort.now()).thenReturn(tPublish);
            when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(inv -> inv.getArgument(0));

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort, clockPort
            );
            useCase.execute(titleId, entryId);

            InOrder order = inOrder(mediaVisibilityPort, titleRepository, entryRepository);
            order.verify(mediaVisibilityPort).lockAndValidateActiveVideoAsset(videoAssetId);
            order.verify(titleRepository).findByIdForUpdate(titleId);
            order.verify(entryRepository).findByIdForUpdate(entryId);
            order.verify(mediaVisibilityPort).validateVideoProvenance(videoAssetId, entryId);
            order.verify(mediaVisibilityPort).makeVideoPublic(videoAssetId, entryId);
        }

        @Test
        @DisplayName("PublishScreenTitle: locks child MediaAssets FIRST in ascending order, ScreenTitle SECOND")
        void publishTitle_locksMediaAssetsFirstInAscendingOrder() {
            UUID asset1 = UUID.fromString("11111111-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            UUID asset2 = UUID.fromString("22222222-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

            ScreenTitle draftTitle = ScreenTitle.createDraft(titleId, "series", "Series", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            ScreenEntry entry1 = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            entry1.updateMedia(asset2, null, t0);
            entry1.publish(t0);

            ScreenEntry entry2 = ScreenEntry.createDraft(entryId2, titleId, "ep-2", ScreenEntryType.EPISODE, 2, "Ep 2", t0);
            entry2.updateMedia(asset1, null, t0);
            entry2.publish(t0);

            when(entryRepository.findPublishedByTitleId(titleId)).thenReturn(List.of(entry1, entry2));
            when(entryRepository.findPublishedEntryVideoBindingsByTitleIdCurrentRead(titleId)).thenReturn(List.of(
                    new ScreenEntryRepositoryPort.EntryVideoBinding(entryId, asset2),
                    new ScreenEntryRepositoryPort.EntryVideoBinding(entryId2, asset1)
            ));
            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(draftTitle));
            when(clockPort.now()).thenReturn(tPublish);
            when(titleRepository.save(any(ScreenTitle.class))).thenAnswer(inv -> inv.getArgument(0));

            PublishScreenTitleUseCase useCase = new PublishScreenTitleUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort, clockPort
            );
            useCase.execute(titleId);

            InOrder order = inOrder(mediaVisibilityPort, titleRepository);
            // Must lock asset1 then asset2 in ascending order before locking title
            order.verify(mediaVisibilityPort).lockAndValidateActiveVideoAsset(asset1);
            order.verify(mediaVisibilityPort).lockAndValidateActiveVideoAsset(asset2);
            order.verify(titleRepository).findByIdForUpdate(titleId);
        }
    }

    @Nested
    @DisplayName("P0-C: Unlocked Preliminary-ID Races Elimination")
    class PreliminaryIdRaceTests {

        @Test
        @DisplayName("PublishScreenEntry: throws ScreenEntryVideoConflictException if video changed concurrently after preliminary read")
        void publishEntry_throwsConflictIfVideoChangedConcurrently() {
            UUID staleAssetId = videoAssetId;
            UUID replacedAssetId = UUID.randomUUID();

            when(entryRepository.findVideoMediaAssetIdById(entryId)).thenReturn(Optional.of(staleAssetId));

            ScreenTitle title = ScreenTitle.createDraft(titleId, "series", "Series", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            ScreenEntry lockedEntry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            // Concurrently replaced by another transaction
            lockedEntry.updateMedia(replacedAssetId, null, t0);

            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(title));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(lockedEntry));

            PublishScreenEntryUseCase useCase = new PublishScreenEntryUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort, clockPort
            );

            assertThatThrownBy(() -> useCase.execute(titleId, entryId))
                    .isInstanceOf(ScreenEntryVideoConflictException.class);

            verify(mediaVisibilityPort, never()).makeVideoPublic(any());
            verify(mediaVisibilityPort, never()).makeVideoPrivate(any());
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("ArchiveScreenEntry: throws ScreenEntryVideoConflictException if video changed concurrently after preliminary read")
        void archiveEntry_throwsConflictIfVideoChangedConcurrently() {
            UUID staleAssetId = videoAssetId;
            UUID replacedAssetId = UUID.randomUUID();

            when(entryRepository.findVideoMediaAssetIdById(entryId)).thenReturn(Optional.of(staleAssetId));

            ScreenTitle title = ScreenTitle.createDraft(titleId, "series", "Series", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            ScreenEntry lockedEntry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            lockedEntry.updateMedia(replacedAssetId, null, t0);

            when(titleRepository.existsById(titleId)).thenReturn(true);
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(lockedEntry));

            ArchiveScreenEntryUseCase useCase = new ArchiveScreenEntryUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort, clockPort
            );

            assertThatThrownBy(() -> useCase.execute(titleId, entryId))
                    .isInstanceOf(ScreenEntryVideoConflictException.class);

            verify(mediaVisibilityPort, never()).makeVideoPrivate(any());
            verify(entryRepository, never()).save(any());
        }

        @Test
        @DisplayName("ReconcileScreenEntryVideoVisibility: throws ScreenEntryVideoConflictException if video changed concurrently")
        void reconcile_throwsConflictIfVideoChangedConcurrently() {
            ScreenEntry preliminaryEntry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            preliminaryEntry.updateMedia(videoAssetId, null, t0);

            when(titleRepository.existsById(titleId)).thenReturn(true);
            when(entryRepository.findById(entryId)).thenReturn(Optional.of(preliminaryEntry));

            ScreenTitle title = ScreenTitle.createDraft(titleId, "series", "Series", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            ScreenEntry lockedEntry = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            UUID concurrentAsset = UUID.randomUUID();
            lockedEntry.updateMedia(concurrentAsset, null, t0);

            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(title));
            when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(lockedEntry));

            ReconcileScreenEntryVideoVisibilityUseCase useCase = new ReconcileScreenEntryVideoVisibilityUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort
            );

            assertThatThrownBy(() -> useCase.execute(titleId, entryId))
                    .isInstanceOf(ScreenEntryVideoConflictException.class);

            verify(mediaVisibilityPort, never()).makeVideoPublic(any());
            verify(mediaVisibilityPort, never()).makeVideoPrivate(any());
        }

        @Test
        @DisplayName("PublishScreenTitle: throws ScreenTitleVideoConflictException if published child entries changed concurrently")
        void publishTitle_throwsConflictIfChildVideosChangedConcurrently() {
            ScreenEntry entry1 = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            entry1.updateMedia(videoAssetId, null, t0);
            entry1.publish(t0);

            UUID concurrentAsset = UUID.randomUUID();
            ScreenTitle title = ScreenTitle.createDraft(titleId, "series", "Series", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);

            // First call (preliminary) returns only entry1
            // Second call (authoritative scalar current-read under lock) returns entry1 and newly published entry2
            when(entryRepository.findPublishedByTitleId(titleId)).thenReturn(List.of(entry1));
            when(entryRepository.findPublishedEntryVideoBindingsByTitleIdCurrentRead(titleId)).thenReturn(List.of(
                    new ScreenEntryRepositoryPort.EntryVideoBinding(entryId, videoAssetId),
                    new ScreenEntryRepositoryPort.EntryVideoBinding(entryId2, concurrentAsset)
            ));

            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(title));

            PublishScreenTitleUseCase useCase = new PublishScreenTitleUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort, clockPort
            );

            assertThatThrownBy(() -> useCase.execute(titleId))
                    .isInstanceOf(ScreenTitleVideoConflictException.class);

            verify(titleRepository, never()).save(any());
        }

        @Test
        @DisplayName("PublishScreenTitle: throws ScreenTitleVideoConflictException on entry-level binding change even when distinct asset set is unchanged")
        void publishTitle_throwsConflictOnEntryLevelBindingChangeWithSameDistinctAssetSet() {
            ScreenEntry entry1 = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            entry1.updateMedia(videoAssetId, null, t0);
            entry1.publish(t0);

            ScreenTitle title = ScreenTitle.createDraft(titleId, "series", "Series", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);

            // Preliminary scan sees only entry1 with videoAssetId; authoritative current-read sees newly published entry2 sharing videoAssetId
            when(entryRepository.findPublishedByTitleId(titleId)).thenReturn(List.of(entry1));
            when(entryRepository.findPublishedEntryVideoBindingsByTitleIdCurrentRead(titleId)).thenReturn(List.of(
                    new ScreenEntryRepositoryPort.EntryVideoBinding(entryId, videoAssetId),
                    new ScreenEntryRepositoryPort.EntryVideoBinding(entryId2, videoAssetId)
            ));

            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(title));

            PublishScreenTitleUseCase useCase = new PublishScreenTitleUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort, clockPort
            );

            assertThatThrownBy(() -> useCase.execute(titleId))
                    .isInstanceOf(ScreenTitleVideoConflictException.class);

            verify(titleRepository, never()).save(any());
        }

        @Test
        @DisplayName("ArchiveScreenTitle: throws ScreenTitleVideoConflictException if child entries changed concurrently")
        void archiveTitle_throwsConflictIfChildVideosChangedConcurrently() {
            ScreenEntry entry1 = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            entry1.updateMedia(videoAssetId, null, t0);

            UUID concurrentAsset = UUID.randomUUID();
            ScreenTitle title = ScreenTitle.createDraft(titleId, "series", "Series", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            title.publish(t0);

            // First call (preliminary) returns entry1 with videoAssetId
            // Second call (authoritative scalar current-read under lock) observes entry1 replaced with concurrentAsset
            when(entryRepository.findByTitleId(titleId)).thenReturn(List.of(entry1));
            when(entryRepository.findEntryVideoBindingsByTitleIdCurrentRead(titleId)).thenReturn(List.of(
                    new ScreenEntryRepositoryPort.EntryVideoBinding(entryId, concurrentAsset)
            ));

            when(titleRepository.findByIdForUpdate(titleId)).thenReturn(Optional.of(title));

            ArchiveScreenTitleUseCase useCase = new ArchiveScreenTitleUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort, clockPort
            );

            assertThatThrownBy(() -> useCase.execute(titleId))
                    .isInstanceOf(ScreenTitleVideoConflictException.class);

            verify(titleRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Security Gate: Authoritative Video Provenance Verification")
    class VideoProvenanceSecurityTests {

        @Test
        @DisplayName("Provenance: fails closed and throws ScreenVideoProvenanceException if no upload session exists")
        void makePublic_failsClosedWhenNoUploadSessionExists() {
            ScreenMediaVisibilityAdapter adapter = new ScreenMediaVisibilityAdapter(mediaContract);
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> adapter.makeVideoPublic(videoAssetId))
                    .isInstanceOf(ScreenVideoProvenanceException.class)
                    .hasMessageContaining("Không tìm thấy phiên tải lên hoàn tất");

            verify(mediaContract, never()).changeVisibility(any());
        }

        private MediaUploadSessionResponseDTO createSession(String consumerContext, String targetEntityType, UUID targetEntityId) {
            return new MediaUploadSessionResponseDTO(
                    UUID.randomUUID(),
                    "COMPLETED",
                    "admin",
                    consumerContext,
                    targetEntityType,
                    targetEntityId,
                    1000L,
                    1000L,
                    1,
                    "video.mp4",
                    "screen-video",
                    videoAssetId,
                    t0,
                    t0.plusSeconds(3600),
                    tPublish
            );
        }

        @Test
        @DisplayName("Provenance: fails closed when video belongs to a foreign module (e.g. novel)")
        void makePublic_failsClosedWhenConsumerContextIsForeign() {
            ScreenMediaVisibilityAdapter adapter = new ScreenMediaVisibilityAdapter(mediaContract);
            MediaUploadSessionResponseDTO foreignSession = createSession("novel", "NOVEL_CHAPTER", UUID.randomUUID());
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.of(foreignSession));

            assertThatThrownBy(() -> adapter.makeVideoPublic(videoAssetId))
                    .isInstanceOf(ScreenVideoProvenanceException.class)
                    .hasMessageContaining("Consumer context [novel] không khớp với Screen context");

            verify(mediaContract, never()).changeVisibility(any());
        }

        @Test
        @DisplayName("Provenance: fails closed when target entity type is not SCREEN_ENTRY")
        void makePublic_failsClosedWhenTargetEntityTypeMismatch() {
            ScreenMediaVisibilityAdapter adapter = new ScreenMediaVisibilityAdapter(mediaContract);
            MediaUploadSessionResponseDTO wrongTargetSession = createSession("screen", "USER_AVATAR", UUID.randomUUID());
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.of(wrongTargetSession));

            assertThatThrownBy(() -> adapter.makeVideoPublic(videoAssetId))
                    .isInstanceOf(ScreenVideoProvenanceException.class)
                    .hasMessageContaining("Target entity type [USER_AVATAR] không phải SCREEN_ENTRY");

            verify(mediaContract, never()).changeVisibility(any());
        }

        @Test
        @DisplayName("Provenance: successfully promotes to PUBLIC when upload session is COMPLETED in screen context")
        void makePublic_succeedsWithValidScreenProvenance() {
            ScreenMediaVisibilityAdapter adapter = new ScreenMediaVisibilityAdapter(mediaContract);
            MediaUploadSessionResponseDTO validSession = createSession("screen", "SCREEN_ENTRY", entryId);
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.of(validSession));

            adapter.makeVideoPublic(videoAssetId);

            verify(mediaContract).changeVisibility(new ChangeMediaVisibilityRequestDTO(
                    videoAssetId, MediaVisibilityDTO.PUBLIC
            ));
        }

        @Test
        @DisplayName("Provenance: matching target succeeds")
        void makePublic_succeedsWhenTargetMatches() {
            ScreenMediaVisibilityAdapter adapter = new ScreenMediaVisibilityAdapter(
                    mediaContract, entryRepository, titleRepository, franchiseMembershipRepository
            );
            MediaUploadSessionResponseDTO validSession = createSession("screen", "SCREEN_ENTRY", entryId);
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.of(validSession));

            adapter.makeVideoPublic(videoAssetId, entryId);

            verify(mediaContract).changeVisibility(new ChangeMediaVisibilityRequestDTO(
                    videoAssetId, MediaVisibilityDTO.PUBLIC
            ));
        }

        @Test
        @DisplayName("Provenance: mismatched target without authorized reuse throws ScreenVideoProvenanceException")
        void makePublic_failsWhenMismatchedTargetAndNoAuthorizedReuse() {
            UUID originalTargetId = UUID.randomUUID();
            UUID currentTargetId = UUID.randomUUID();
            UUID otherTitleId = UUID.randomUUID();

            ScreenEntry currentEntry = ScreenEntry.createDraft(currentTargetId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            ScreenEntry originalEntry = ScreenEntry.createDraft(originalTargetId, otherTitleId, "ep-x", ScreenEntryType.EPISODE, 1, "Ep X", t0);

            when(entryRepository.findById(currentTargetId)).thenReturn(Optional.of(currentEntry));
            when(entryRepository.findById(originalTargetId)).thenReturn(Optional.of(originalEntry));
            when(franchiseMembershipRepository.findByTitleId(titleId)).thenReturn(List.of());
            when(franchiseMembershipRepository.findByTitleId(otherTitleId)).thenReturn(List.of());

            ScreenMediaVisibilityAdapter adapter = new ScreenMediaVisibilityAdapter(
                    mediaContract, entryRepository, titleRepository, franchiseMembershipRepository
            );
            MediaUploadSessionResponseDTO session = createSession("screen", "SCREEN_ENTRY", originalTargetId);
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> adapter.makeVideoPublic(videoAssetId, currentTargetId))
                    .isInstanceOf(ScreenVideoProvenanceException.class)
                    .hasMessageContaining("Vi phạm nguồn gốc video: Tái sử dụng asset không được phép");

            verify(mediaContract, never()).changeVisibility(any());
        }

        @Test
        @DisplayName("Provenance: authorized reuse under same ScreenTitle succeeds")
        void makePublic_succeedsWithAuthorizedReuseUnderSameTitle() {
            UUID originalTargetId = UUID.randomUUID();
            UUID currentTargetId = UUID.randomUUID();

            ScreenEntry currentEntry = ScreenEntry.createDraft(currentTargetId, titleId, "ep-2", ScreenEntryType.EPISODE, 2, "Ep 2", t0);
            ScreenEntry originalEntry = ScreenEntry.createDraft(originalTargetId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);

            when(entryRepository.findById(currentTargetId)).thenReturn(Optional.of(currentEntry));
            when(entryRepository.findById(originalTargetId)).thenReturn(Optional.of(originalEntry));

            ScreenMediaVisibilityAdapter adapter = new ScreenMediaVisibilityAdapter(
                    mediaContract, entryRepository, titleRepository, franchiseMembershipRepository
            );
            MediaUploadSessionResponseDTO session = createSession("screen", "SCREEN_ENTRY", originalTargetId);
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.of(session));

            adapter.makeVideoPublic(videoAssetId, currentTargetId);

            verify(mediaContract).changeVisibility(new ChangeMediaVisibilityRequestDTO(
                    videoAssetId, MediaVisibilityDTO.PUBLIC
            ));
        }

        @Test
        @DisplayName("Provenance: authorized reuse under same ScreenFranchise succeeds")
        void makePublic_succeedsWithAuthorizedReuseUnderSameFranchise() {
            UUID originalTargetId = UUID.randomUUID();
            UUID currentTargetId = UUID.randomUUID();
            UUID otherTitleId = UUID.randomUUID();
            UUID franchiseId = UUID.randomUUID();

            ScreenEntry currentEntry = ScreenEntry.createDraft(currentTargetId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            ScreenEntry originalEntry = ScreenEntry.createDraft(originalTargetId, otherTitleId, "ep-x", ScreenEntryType.EPISODE, 1, "Ep X", t0);

            when(entryRepository.findById(currentTargetId)).thenReturn(Optional.of(currentEntry));
            when(entryRepository.findById(originalTargetId)).thenReturn(Optional.of(originalEntry));

            ScreenFranchiseMembership mem1 = new ScreenFranchiseMembership(UUID.randomUUID(), franchiseId, titleId, 0);
            ScreenFranchiseMembership mem2 = new ScreenFranchiseMembership(UUID.randomUUID(), franchiseId, otherTitleId, 1);

            when(franchiseMembershipRepository.findByTitleId(titleId)).thenReturn(List.of(mem1));
            when(franchiseMembershipRepository.findByTitleId(otherTitleId)).thenReturn(List.of(mem2));

            ScreenMediaVisibilityAdapter adapter = new ScreenMediaVisibilityAdapter(
                    mediaContract, entryRepository, titleRepository, franchiseMembershipRepository
            );
            MediaUploadSessionResponseDTO session = createSession("screen", "SCREEN_ENTRY", originalTargetId);
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.of(session));

            adapter.makeVideoPublic(videoAssetId, currentTargetId);

            verify(mediaContract).changeVisibility(new ChangeMediaVisibilityRequestDTO(
                    videoAssetId, MediaVisibilityDTO.PUBLIC
            ));
        }

        @Test
        @DisplayName("Provenance: published original entry alone does NOT authorize reuse under unrelated Title and Franchise")
        void makePublic_failsWhenOriginalEntryIsPublishedUnderUnrelatedTitleAndFranchise() {
            UUID originalTargetId = UUID.randomUUID();
            UUID currentTargetId = UUID.randomUUID();
            UUID otherTitleId = UUID.randomUUID();
            UUID franchise1Id = UUID.randomUUID();
            UUID franchise2Id = UUID.randomUUID();

            ScreenEntry currentEntry = ScreenEntry.createDraft(currentTargetId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            ScreenEntry originalEntry = ScreenEntry.createDraft(originalTargetId, otherTitleId, "ep-x", ScreenEntryType.EPISODE, 1, "Ep X", t0);
            originalEntry.updateMedia(videoAssetId, null, t0);
            originalEntry.publish(tPublish);

            when(entryRepository.findById(currentTargetId)).thenReturn(Optional.of(currentEntry));
            when(entryRepository.findById(originalTargetId)).thenReturn(Optional.of(originalEntry));

            ScreenFranchiseMembership memCurrent = new ScreenFranchiseMembership(UUID.randomUUID(), franchise1Id, titleId, 0);
            ScreenFranchiseMembership memOriginal = new ScreenFranchiseMembership(UUID.randomUUID(), franchise2Id, otherTitleId, 0);
            when(franchiseMembershipRepository.findByTitleId(titleId)).thenReturn(List.of(memCurrent));
            when(franchiseMembershipRepository.findByTitleId(otherTitleId)).thenReturn(List.of(memOriginal));

            ScreenMediaVisibilityAdapter adapter = new ScreenMediaVisibilityAdapter(
                    mediaContract, entryRepository, titleRepository, franchiseMembershipRepository
            );
            MediaUploadSessionResponseDTO session = createSession("screen", "SCREEN_ENTRY", originalTargetId);
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> adapter.makeVideoPublic(videoAssetId, currentTargetId))
                    .isInstanceOf(ScreenVideoProvenanceException.class)
                    .hasMessageContaining("Tái sử dụng asset không được phép");

            verify(mediaContract, never()).changeVisibility(any());
        }

        @Test
        @DisplayName("Provenance: attaching video from published Entry A to Entry B under unrelated Title and Franchise fails closed")
        void attachVideo_failsWhenOriginalEntryIsPublishedUnderUnrelatedTitleAndFranchise() {
            UUID originalTargetId = UUID.randomUUID();
            UUID currentTargetId = entryId;
            UUID otherTitleId = UUID.randomUUID();
            UUID franchise1Id = UUID.randomUUID();
            UUID franchise2Id = UUID.randomUUID();

            ScreenTitle currentTitle = ScreenTitle.createDraft(titleId, "series-b", "Series B", ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, t0);
            currentTitle.publish(tPublish);
            ScreenEntry currentEntry = ScreenEntry.createDraft(currentTargetId, titleId, "ep-b1", ScreenEntryType.EPISODE, 1, "Ep B1", t0);

            ScreenEntry originalEntry = ScreenEntry.createDraft(originalTargetId, otherTitleId, "ep-a1", ScreenEntryType.EPISODE, 1, "Ep A1", t0);
            originalEntry.updateMedia(videoAssetId, null, t0);
            originalEntry.publish(tPublish);

            when(titleRepository.existsById(titleId)).thenReturn(true);
            when(entryRepository.findById(currentTargetId)).thenReturn(Optional.of(currentEntry));
            when(entryRepository.findById(originalTargetId)).thenReturn(Optional.of(originalEntry));

            ScreenFranchiseMembership memCurrent = new ScreenFranchiseMembership(UUID.randomUUID(), franchise1Id, titleId, 0);
            ScreenFranchiseMembership memOriginal = new ScreenFranchiseMembership(UUID.randomUUID(), franchise2Id, otherTitleId, 0);
            when(franchiseMembershipRepository.findByTitleId(titleId)).thenReturn(List.of(memCurrent));
            when(franchiseMembershipRepository.findByTitleId(otherTitleId)).thenReturn(List.of(memOriginal));

            MediaVersionDTO version = new MediaVersionDTO(
                    UUID.randomUUID(), videoAssetId, 1, "https://media.kiemlai.test/video.mp4",
                    "video/mp4", 1048576L, "video.mp4", t0
            );
            MediaAssetDetailDTO detail = new MediaAssetDetailDTO(
                    videoAssetId, MediaTypeDTO.VIDEO, MediaVisibilityDTO.PUBLIC, MediaAssetStatusDTO.ACTIVE,
                    1, t0, t0, version
            );
            when(mediaContract.lockAndValidateActiveAsset(videoAssetId, MediaTypeDTO.VIDEO)).thenReturn(detail);

            MediaUploadSessionResponseDTO session = createSession("screen", "SCREEN_ENTRY", originalTargetId);
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.of(session));

            ScreenMediaVisibilityAdapter realAdapter = new ScreenMediaVisibilityAdapter(
                    mediaContract, entryRepository, titleRepository, franchiseMembershipRepository
            );
            AttachScreenEntryVideoUseCase attachUseCase = new AttachScreenEntryVideoUseCase(
                    titleRepository, entryRepository, mediaValidationPort, realAdapter, clockPort
            );

            assertThatThrownBy(() -> attachUseCase.execute(titleId, currentTargetId, videoAssetId))
                    .isInstanceOf(ScreenVideoProvenanceException.class)
                    .hasMessageContaining("Tái sử dụng asset không được phép");

            verify(entryRepository, never()).save(any());
            verify(mediaContract, never()).changeVisibility(any());
        }

        @Test
        @DisplayName("Provenance: demotion fails closed when mismatched target without authorized reuse")
        void makePrivate_failsWhenMismatchedTargetAndNoAuthorizedReuse() {
            UUID originalTargetId = UUID.randomUUID();
            UUID currentTargetId = UUID.randomUUID();
            UUID otherTitleId = UUID.randomUUID();

            ScreenEntry currentEntry = ScreenEntry.createDraft(currentTargetId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);
            ScreenEntry originalEntry = ScreenEntry.createDraft(originalTargetId, otherTitleId, "ep-x", ScreenEntryType.EPISODE, 1, "Ep X", t0);

            when(entryRepository.findById(currentTargetId)).thenReturn(Optional.of(currentEntry));
            when(entryRepository.findById(originalTargetId)).thenReturn(Optional.of(originalEntry));
            when(franchiseMembershipRepository.findByTitleId(titleId)).thenReturn(List.of());
            when(franchiseMembershipRepository.findByTitleId(otherTitleId)).thenReturn(List.of());

            ScreenMediaVisibilityAdapter adapter = new ScreenMediaVisibilityAdapter(
                    mediaContract, entryRepository, titleRepository, franchiseMembershipRepository
            );
            MediaUploadSessionResponseDTO session = createSession("screen", "SCREEN_ENTRY", originalTargetId);
            when(mediaContract.findCompletedSessionByAssetId(videoAssetId)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> adapter.makeVideoPrivate(videoAssetId, currentTargetId))
                    .isInstanceOf(ScreenVideoProvenanceException.class)
                    .hasMessageContaining("Vi phạm nguồn gốc video: Tái sử dụng asset không được phép");

            verify(mediaContract, never()).changeVisibility(any());
        }
    }

    @Nested
    @DisplayName("Admin Reconciliation Hardening: Existence & Binding Checks")
    class AdminReconciliationHardeningTests {

        @Test
        @DisplayName("Reconcile: throws ScreenTitleNotFoundException when titleId does not exist")
        void reconcile_throws404WhenTitleNotFound() {
            when(titleRepository.existsById(titleId)).thenReturn(false);

            ReconcileScreenEntryVideoVisibilityUseCase useCase = new ReconcileScreenEntryVideoVisibilityUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort
            );

            assertThatThrownBy(() -> useCase.execute(titleId, entryId))
                    .isInstanceOf(ScreenTitleNotFoundException.class);
        }

        @Test
        @DisplayName("Reconcile: throws ScreenEntryNotFoundException when entry does not belong to title")
        void reconcile_throws404WhenEntryDoesNotBelongToTitle() {
            UUID otherTitleId = UUID.randomUUID();
            ScreenEntry entryUnderOtherTitle = ScreenEntry.createDraft(entryId, otherTitleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);

            when(titleRepository.existsById(titleId)).thenReturn(true);
            when(entryRepository.findById(entryId)).thenReturn(Optional.of(entryUnderOtherTitle));

            ReconcileScreenEntryVideoVisibilityUseCase useCase = new ReconcileScreenEntryVideoVisibilityUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort
            );

            assertThatThrownBy(() -> useCase.execute(titleId, entryId))
                    .isInstanceOf(ScreenEntryNotFoundException.class);
        }

        @Test
        @DisplayName("Reconcile: returns NO_VIDEO_ATTACHED only after validating title and entry existence")
        void reconcile_returnsNoVideoAttachedAfterValidatingExistence() {
            ScreenEntry entryNoVideo = ScreenEntry.createDraft(entryId, titleId, "ep-1", ScreenEntryType.EPISODE, 1, "Ep 1", t0);

            when(titleRepository.existsById(titleId)).thenReturn(true);
            when(entryRepository.findById(entryId)).thenReturn(Optional.of(entryNoVideo));

            ReconcileScreenEntryVideoVisibilityUseCase useCase = new ReconcileScreenEntryVideoVisibilityUseCase(
                    titleRepository, entryRepository, mediaVisibilityPort
            );
            ReconcileScreenVideoVisibilityResult result = useCase.execute(titleId, entryId);

            assertThat(result.outcome()).isEqualTo(ReconcileScreenVideoVisibilityResult.Outcome.NO_VIDEO_ATTACHED);
            assertThat(result.videoMediaAssetId()).isNull();
            verify(mediaVisibilityPort, never()).makeVideoPublic(any());
            verify(mediaVisibilityPort, never()).makeVideoPrivate(any());
        }
    }
}
