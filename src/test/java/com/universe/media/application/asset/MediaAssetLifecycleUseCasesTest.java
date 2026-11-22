package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.hls.CancelOrSupersedeMediaVideoPackagingUseCase;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.exceptions.MediaAssetReferencedException;
import com.universe.media.contracts.exceptions.MediaReferenceCheckUnavailableException;
import com.universe.media.contracts.interfaces.MediaAssetReferenceCheckPort;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.shared.time.ClockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetLifecycleUseCasesTest {

    private static final Instant T0 =
            Instant.parse("2026-09-01T10:00:00Z");

    private static final Instant FIXED_NOW =
            Instant.parse("2026-09-01T12:00:00Z");

    private static final ClockPort FIXED_CLOCK =
            () -> FIXED_NOW;

    private static final UUID ASSET_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetReferenceCheckPort referenceCheckPort;

    @Mock
    private CancelOrSupersedeMediaVideoPackagingUseCase cancelOrSupersedeUseCase;

    private ChangeMediaVisibilityUseCase changeVisibilityUseCase;
    private ArchiveMediaAssetUseCase archiveUseCase;
    private RestoreMediaAssetUseCase restoreUseCase;
    private DeleteMediaAssetUseCase deleteUseCase;

    @BeforeEach
    void setUp() {
        changeVisibilityUseCase = new ChangeMediaVisibilityUseCase(
                mediaAssetRepositoryPort,
                FIXED_CLOCK
        );
        archiveUseCase = new ArchiveMediaAssetUseCase(
                mediaAssetRepositoryPort,
                FIXED_CLOCK,
                List.of(),
                cancelOrSupersedeUseCase
        );
        restoreUseCase = new RestoreMediaAssetUseCase(
                mediaAssetRepositoryPort,
                FIXED_CLOCK
        );
        deleteUseCase = new DeleteMediaAssetUseCase(
                mediaAssetRepositoryPort,
                FIXED_CLOCK,
                List.of(),
                cancelOrSupersedeUseCase
        );
    }

    private MediaAsset createActiveAsset() {
        return MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.IMAGE,
                MediaVisibility.PUBLIC,
                T0
        );
    }

    private MediaAsset createActiveVideoAsset() {
        return MediaAsset.registerInitial(
                ASSET_ID,
                MediaType.VIDEO,
                MediaVisibility.PUBLIC,
                T0
        );
    }

    @Nested
    @DisplayName("ChangeMediaVisibilityUseCase")
    class ChangeVisibilityTests {

        @Test
        @DisplayName("successful visibility change mutates asset and saves")
        void shouldChangeVisibilitySuccessfully() {
            MediaAsset asset = createActiveAsset();
            when(mediaAssetRepositoryPort.findById(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            changeVisibilityUseCase.execute(
                    new ChangeMediaVisibilityCommand(ASSET_ID, MediaVisibility.PRIVATE)
            );

            ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
            verify(mediaAssetRepositoryPort).save(captor.capture());

            MediaAsset saved = captor.getValue();
            assertThat(saved.getVisibility()).isEqualTo(MediaVisibility.PRIVATE);
            assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("asset not found throws MediaAssetNotFoundException")
        void shouldThrowWhenAssetNotFound() {
            when(mediaAssetRepositoryPort.findById(ASSET_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> changeVisibilityUseCase.execute(
                    new ChangeMediaVisibilityCommand(ASSET_ID, MediaVisibility.PRIVATE)
            )).isInstanceOf(MediaAssetNotFoundException.class)
                    .hasMessageContaining(ASSET_ID.toString());

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("same visibility is a no-op and does not call save")
        void shouldNotCallSaveWhenVisibilityIsUnchanged() {
            MediaAsset asset = createActiveAsset();
            when(mediaAssetRepositoryPort.findById(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            changeVisibilityUseCase.execute(
                    new ChangeMediaVisibilityCommand(ASSET_ID, MediaVisibility.PUBLIC)
            );

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("visibility change on DELETED asset propagates domain exception without saving")
        void shouldRejectVisibilityChangeOnDeletedAsset() {
            MediaAsset asset = createActiveAsset();
            asset.markDeleted(T0);

            when(mediaAssetRepositoryPort.findById(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> changeVisibilityUseCase.execute(
                    new ChangeMediaVisibilityCommand(ASSET_ID, MediaVisibility.RESTRICTED)
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DELETED");

            verify(mediaAssetRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("ArchiveMediaAssetUseCase")
    class ArchiveTests {

        @Test
        @DisplayName("successful archive mutates asset status to ARCHIVED and saves")
        void shouldArchiveSuccessfully() {
            MediaAsset asset = createActiveAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            archiveUseCase.execute(new ArchiveMediaAssetCommand(ASSET_ID));

            ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
            verify(mediaAssetRepositoryPort).save(captor.capture());

            MediaAsset saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(MediaAssetStatus.ARCHIVED);
            assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("asset not found throws MediaAssetNotFoundException")
        void shouldThrowWhenAssetNotFound() {
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> archiveUseCase.execute(new ArchiveMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(MediaAssetNotFoundException.class)
                    .hasMessageContaining(ASSET_ID.toString());

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("archive rejects when asset is referenced by consumer guard")
        void shouldRejectArchiveWhenAssetIsReferencedByConsumer() {
            ArchiveMediaAssetUseCase guardedArchiveUseCase = new ArchiveMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of(referenceCheckPort),
                    cancelOrSupersedeUseCase
            );

            MediaAsset asset = createActiveVideoAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));
            when(referenceCheckPort.supports(MediaTypeDTO.VIDEO)).thenReturn(true);
            doThrow(new MediaAssetReferencedException(ASSET_ID))
                    .when(referenceCheckPort).ensureNotReferenced(ASSET_ID);

            assertThatThrownBy(() -> guardedArchiveUseCase.execute(new ArchiveMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(MediaAssetReferencedException.class)
                    .hasMessageContaining(ASSET_ID.toString());

            verify(referenceCheckPort).ensureNotReferenced(ASSET_ID);
            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(cancelOrSupersedeUseCase, never()).cancelActivePackagingForArchiveInCurrentTransaction(any(), any());
            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        }

        @Test
        @DisplayName("archive rejects with MediaReferenceCheckUnavailableException when VIDEO asset has no registered checker")
        void shouldRejectArchiveWhenVideoAssetHasNoRegisteredReferenceCheckPort() {
            ArchiveMediaAssetUseCase unguardedArchiveUseCase = new ArchiveMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of(),
                    cancelOrSupersedeUseCase
            );

            MediaAsset asset = createActiveVideoAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> unguardedArchiveUseCase.execute(new ArchiveMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(MediaReferenceCheckUnavailableException.class)
                    .hasMessageContaining(ASSET_ID.toString())
                    .satisfies(ex -> {
                        MediaReferenceCheckUnavailableException unavail = (MediaReferenceCheckUnavailableException) ex;
                        assertThat(unavail.getErrorCode()).isEqualTo("MEDIA_REFERENCE_CHECK_UNAVAILABLE");
                        assertThat(unavail.getMediaType()).isEqualTo(MediaTypeDTO.VIDEO);
                    });

            verify(mediaAssetRepositoryPort, never()).save(any());
            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        }

        @Test
        @DisplayName("archive allows IMAGE asset to proceed when no reference checker is registered (Media-only workflow)")
        void shouldAllowArchiveWhenImageAssetHasNoRegisteredReferenceCheckPort() {
            ArchiveMediaAssetUseCase unguardedArchiveUseCase = new ArchiveMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of()
            );

            MediaAsset asset = createActiveAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            unguardedArchiveUseCase.execute(new ArchiveMediaAssetCommand(ASSET_ID));

            ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
            verify(mediaAssetRepositoryPort).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(MediaAssetStatus.ARCHIVED);
            verify(cancelOrSupersedeUseCase, never()).cancelActivePackagingForArchiveInCurrentTransaction(any(), any());
        }

        @Test
        @DisplayName("archive cancels active VIDEO packaging in current tx and fails closed if lifecycle coordinator is absent")
        void shouldCancelActivePackagingOnVideoArchiveAndFailClosedIfMissing() {
            ArchiveMediaAssetUseCase guardedArchiveUseCase = new ArchiveMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of(referenceCheckPort),
                    cancelOrSupersedeUseCase
            );
            MediaAsset asset = createActiveVideoAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset));
            when(referenceCheckPort.supports(MediaTypeDTO.VIDEO)).thenReturn(true);

            guardedArchiveUseCase.execute(new ArchiveMediaAssetCommand(ASSET_ID));

            verify(cancelOrSupersedeUseCase).cancelActivePackagingForArchiveInCurrentTransaction(
                    org.mockito.ArgumentMatchers.eq(ASSET_ID),
                    org.mockito.ArgumentMatchers.contains("archived")
            );

            ArchiveMediaAssetUseCase legacyArchiveWithoutCancel = new ArchiveMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of(referenceCheckPort)
            );
            MediaAsset asset2 = createActiveVideoAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(asset2));
            assertThatThrownBy(() -> legacyArchiveWithoutCancel.execute(new ArchiveMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CancelOrSupersedeMediaVideoPackagingUseCase");
            verify(mediaAssetRepositoryPort, org.mockito.Mockito.times(1)).save(any());
        }
    }

    @Nested
    @DisplayName("RestoreMediaAssetUseCase")
    class RestoreTests {

        @Test
        @DisplayName("successful restore acquires FOR UPDATE lock, mutates ARCHIVED to ACTIVE, and saves")
        void shouldRestoreSuccessfully() {
            MediaAsset asset = createActiveAsset();
            asset.archive(T0);

            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            restoreUseCase.execute(new RestoreMediaAssetCommand(ASSET_ID));

            verify(mediaAssetRepositoryPort).findByIdForUpdate(ASSET_ID);
            ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
            verify(mediaAssetRepositoryPort).save(captor.capture());

            MediaAsset saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
            assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("asset not found throws MediaAssetNotFoundException")
        void shouldThrowWhenAssetNotFound() {
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> restoreUseCase.execute(new RestoreMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(MediaAssetNotFoundException.class)
                    .hasMessageContaining(ASSET_ID.toString());

            verify(mediaAssetRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("DeleteMediaAssetUseCase")
    class DeleteTests {

        @Test
        @DisplayName("successful delete mutates asset status to DELETED and saves")
        void shouldDeleteSuccessfully() {
            MediaAsset asset = createActiveAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            deleteUseCase.execute(new DeleteMediaAssetCommand(ASSET_ID));

            ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
            verify(mediaAssetRepositoryPort).save(captor.capture());

            MediaAsset saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(MediaAssetStatus.DELETED);
            assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("deleting an already DELETED asset is an idempotent no-op and does not save")
        void shouldBeIdempotentNoopWhenAlreadyDeleted() {
            MediaAsset asset = createActiveAsset();
            asset.markDeleted(T0);
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            deleteUseCase.execute(new DeleteMediaAssetCommand(ASSET_ID));

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("asset not found throws MediaAssetNotFoundException")
        void shouldThrowWhenAssetNotFound() {
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> deleteUseCase.execute(new DeleteMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(MediaAssetNotFoundException.class)
                    .hasMessageContaining(ASSET_ID.toString());

            verify(mediaAssetRepositoryPort, never()).save(any());
        }

        @Test
        @DisplayName("delete rejects when asset is referenced by consumer guard")
        void shouldRejectDeleteWhenAssetIsReferencedByConsumer() {
            DeleteMediaAssetUseCase guardedDeleteUseCase = new DeleteMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of(referenceCheckPort),
                    cancelOrSupersedeUseCase
            );

            MediaAsset asset = createActiveVideoAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));
            when(referenceCheckPort.supports(MediaTypeDTO.VIDEO)).thenReturn(true);
            doThrow(new MediaAssetReferencedException(ASSET_ID))
                    .when(referenceCheckPort).ensureNotReferenced(ASSET_ID);

            assertThatThrownBy(() -> guardedDeleteUseCase.execute(new DeleteMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(MediaAssetReferencedException.class)
                    .hasMessageContaining(ASSET_ID.toString());

            verify(referenceCheckPort).ensureNotReferenced(ASSET_ID);
            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(cancelOrSupersedeUseCase, never()).cancelAllForAssetDeletionInCurrentTransaction(any(), any());
            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        }

        @Test
        @DisplayName("delete succeeds when consumer guard finds no references and cancels VIDEO HLS in current tx")
        void shouldAllowDeleteWhenConsumerGuardPasses() {
            DeleteMediaAssetUseCase guardedDeleteUseCase = new DeleteMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of(referenceCheckPort),
                    cancelOrSupersedeUseCase
            );

            MediaAsset asset = createActiveVideoAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));
            when(referenceCheckPort.supports(MediaTypeDTO.VIDEO)).thenReturn(true);

            guardedDeleteUseCase.execute(new DeleteMediaAssetCommand(ASSET_ID));

            verify(referenceCheckPort).ensureNotReferenced(ASSET_ID);
            ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
            verify(mediaAssetRepositoryPort).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(MediaAssetStatus.DELETED);
            verify(cancelOrSupersedeUseCase).cancelAllForAssetDeletionInCurrentTransaction(
                    org.mockito.ArgumentMatchers.eq(ASSET_ID),
                    org.mockito.ArgumentMatchers.contains("deleted")
            );
        }

        @Test
        @DisplayName("delete fails closed on VIDEO when lifecycle coordinator is absent and cleans pre-A3a DELETED retry safely")
        void shouldFailClosedWithoutLifecycleCoordinatorAndCleanAlreadyDeletedVideoRetry() {
            DeleteMediaAssetUseCase legacyDeleteWithoutCancel = new DeleteMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of(referenceCheckPort)
            );
            MediaAsset activeVideo = createActiveVideoAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(activeVideo));
            when(referenceCheckPort.supports(MediaTypeDTO.VIDEO)).thenReturn(true);

            assertThatThrownBy(() -> legacyDeleteWithoutCancel.execute(new DeleteMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CancelOrSupersedeMediaVideoPackagingUseCase");
            verify(mediaAssetRepositoryPort, never()).save(any());

            MediaAsset alreadyDeletedVideo = createActiveVideoAsset();
            alreadyDeletedVideo.markDeleted(T0);
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID)).thenReturn(Optional.of(alreadyDeletedVideo));
            DeleteMediaAssetUseCase guardedDeleteUseCase = new DeleteMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of(referenceCheckPort),
                    cancelOrSupersedeUseCase
            );

            guardedDeleteUseCase.execute(new DeleteMediaAssetCommand(ASSET_ID));
            verify(mediaAssetRepositoryPort, never()).save(any());
            verify(cancelOrSupersedeUseCase).cancelAllForAssetDeletionInCurrentTransaction(
                    org.mockito.ArgumentMatchers.eq(ASSET_ID),
                    org.mockito.ArgumentMatchers.contains("already DELETED")
            );
        }

        @Test
        @DisplayName("delete rejects with MediaReferenceCheckUnavailableException when VIDEO asset has no registered checker")
        void shouldRejectDeleteWhenVideoAssetHasNoRegisteredReferenceCheckPort() {
            DeleteMediaAssetUseCase unguardedDeleteUseCase = new DeleteMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of(),
                    cancelOrSupersedeUseCase
            );

            MediaAsset asset = createActiveVideoAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            assertThatThrownBy(() -> unguardedDeleteUseCase.execute(new DeleteMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(MediaReferenceCheckUnavailableException.class)
                    .hasMessageContaining(ASSET_ID.toString())
                    .satisfies(ex -> {
                        MediaReferenceCheckUnavailableException unavail = (MediaReferenceCheckUnavailableException) ex;
                        assertThat(unavail.getErrorCode()).isEqualTo("MEDIA_REFERENCE_CHECK_UNAVAILABLE");
                        assertThat(unavail.getMediaType()).isEqualTo(MediaTypeDTO.VIDEO);
                    });

            verify(mediaAssetRepositoryPort, never()).save(any());
            assertThat(asset.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
        }

        @Test
        @DisplayName("delete allows IMAGE asset to proceed when no reference checker is registered (Media-only workflow)")
        void shouldAllowDeleteWhenImageAssetHasNoRegisteredReferenceCheckPort() {
            DeleteMediaAssetUseCase unguardedDeleteUseCase = new DeleteMediaAssetUseCase(
                    mediaAssetRepositoryPort,
                    FIXED_CLOCK,
                    List.of()
            );

            MediaAsset asset = createActiveAsset();
            when(mediaAssetRepositoryPort.findByIdForUpdate(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            unguardedDeleteUseCase.execute(new DeleteMediaAssetCommand(ASSET_ID));

            ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
            verify(mediaAssetRepositoryPort).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(MediaAssetStatus.DELETED);
        }
    }
}
