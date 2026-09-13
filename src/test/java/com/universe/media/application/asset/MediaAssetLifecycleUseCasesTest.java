package com.universe.media.application.asset;

import com.universe.media.application.exceptions.MediaAssetNotFoundException;
import com.universe.media.application.ports.MediaAssetRepositoryPort;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
                FIXED_CLOCK
        );
        restoreUseCase = new RestoreMediaAssetUseCase(
                mediaAssetRepositoryPort,
                FIXED_CLOCK
        );
        deleteUseCase = new DeleteMediaAssetUseCase(
                mediaAssetRepositoryPort,
                FIXED_CLOCK
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
            when(mediaAssetRepositoryPort.findById(ASSET_ID))
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
            when(mediaAssetRepositoryPort.findById(ASSET_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> archiveUseCase.execute(new ArchiveMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(MediaAssetNotFoundException.class)
                    .hasMessageContaining(ASSET_ID.toString());

            verify(mediaAssetRepositoryPort, never()).save(any());
        }
    }

    @Nested
    @DisplayName("RestoreMediaAssetUseCase")
    class RestoreTests {

        @Test
        @DisplayName("successful restore mutates asset status from ARCHIVED to ACTIVE and saves")
        void shouldRestoreSuccessfully() {
            MediaAsset asset = createActiveAsset();
            asset.archive(T0);

            when(mediaAssetRepositoryPort.findById(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            restoreUseCase.execute(new RestoreMediaAssetCommand(ASSET_ID));

            ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
            verify(mediaAssetRepositoryPort).save(captor.capture());

            MediaAsset saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(MediaAssetStatus.ACTIVE);
            assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("asset not found throws MediaAssetNotFoundException")
        void shouldThrowWhenAssetNotFound() {
            when(mediaAssetRepositoryPort.findById(ASSET_ID))
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
            when(mediaAssetRepositoryPort.findById(ASSET_ID))
                    .thenReturn(Optional.of(asset));

            deleteUseCase.execute(new DeleteMediaAssetCommand(ASSET_ID));

            ArgumentCaptor<MediaAsset> captor = ArgumentCaptor.forClass(MediaAsset.class);
            verify(mediaAssetRepositoryPort).save(captor.capture());

            MediaAsset saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(MediaAssetStatus.DELETED);
            assertThat(saved.getUpdatedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("asset not found throws MediaAssetNotFoundException")
        void shouldThrowWhenAssetNotFound() {
            when(mediaAssetRepositoryPort.findById(ASSET_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> deleteUseCase.execute(new DeleteMediaAssetCommand(ASSET_ID)))
                    .isInstanceOf(MediaAssetNotFoundException.class)
                    .hasMessageContaining(ASSET_ID.toString());

            verify(mediaAssetRepositoryPort, never()).save(any());
        }
    }
}
