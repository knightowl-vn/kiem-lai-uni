package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenEntryDTO;
import com.universe.screen.application.exceptions.ScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.ScreenEntryVideoAlreadyAttachedException;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaValidationPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachScreenEntryVideoUseCaseTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepository;

    @Mock
    private ScreenEntryRepositoryPort entryRepository;

    @Mock
    private ScreenMediaValidationPort mediaValidationPort;

    @Mock
    private ClockPort clockPort;

    private AttachScreenEntryVideoUseCase useCase;

    private final UUID titleId = UUID.randomUUID();
    private final UUID entryId = UUID.randomUUID();
    private final UUID videoAssetId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new AttachScreenEntryVideoUseCase(
                titleRepository,
                entryRepository,
                mediaValidationPort,
                clockPort
        );
    }

    @Test
    @DisplayName("Attach: successfully attaches video to entry without existing video")
    void shouldSuccessfullyAttachVideo() {
        ScreenEntry entry = createEntry(entryId, titleId, null);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));
        when(clockPort.now()).thenReturn(now);
        when(entryRepository.save(any(ScreenEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ScreenEntryDTO result = useCase.execute(titleId, entryId, videoAssetId);

        assertThat(result).isNotNull();
        assertThat(result.videoMediaAssetId()).isEqualTo(videoAssetId);
        assertThat(result.updatedAt()).isEqualTo(now);

        verify(mediaValidationPort).lockAndValidateVideoMediaAsset(videoAssetId);
        verify(entryRepository).findByIdForUpdate(entryId);
        verify(entryRepository).save(entry);
    }

    @Test
    @DisplayName("Attach: fails when title does not exist")
    void shouldFailWhenTitleNotFound() {
        when(titleRepository.existsById(titleId)).thenReturn(false);

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, videoAssetId))
                .isInstanceOf(ScreenTitleNotFoundException.class);

        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
        verify(mediaValidationPort, never()).lockAndValidateVideoMediaAsset(any());
    }

    @Test
    @DisplayName("Attach: fails when entry does not exist")
    void shouldFailWhenEntryNotFound() {
        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, videoAssetId))
                .isInstanceOf(ScreenEntryNotFoundException.class);

        verify(mediaValidationPort).lockAndValidateVideoMediaAsset(videoAssetId);
        verify(entryRepository).findByIdForUpdate(entryId);
        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Attach: fails when entry belongs to different title")
    void shouldFailWhenTitleMismatch() {
        UUID otherTitleId = UUID.randomUUID();
        ScreenEntry entry = createEntry(entryId, otherTitleId, null);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, videoAssetId))
                .isInstanceOf(ScreenEntryNotFoundException.class);

        verify(mediaValidationPort).lockAndValidateVideoMediaAsset(videoAssetId);
        verify(entryRepository).findByIdForUpdate(entryId);
        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Attach: idempotently returns existing entry when identical video is already attached")
    void shouldIdempotentlyReturnWhenIdenticalVideoAlreadyAttached() {
        ScreenEntry entry = createEntry(entryId, titleId, videoAssetId);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        ScreenEntryDTO result = useCase.execute(titleId, entryId, videoAssetId);

        assertThat(result).isNotNull();
        assertThat(result.videoMediaAssetId()).isEqualTo(videoAssetId);

        verify(mediaValidationPort).lockAndValidateVideoMediaAsset(videoAssetId);
        verify(entryRepository).findByIdForUpdate(entryId);
        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Attach: rejects when entry already has a different video attached (no silent overwrite)")
    void shouldRejectWhenDifferentVideoAlreadyAttached() {
        UUID existingVideoId = UUID.randomUUID();
        ScreenEntry entry = createEntry(entryId, titleId, existingVideoId);

        when(titleRepository.existsById(titleId)).thenReturn(true);
        when(entryRepository.findByIdForUpdate(entryId)).thenReturn(Optional.of(entry));

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, videoAssetId))
                .isInstanceOf(ScreenEntryVideoAlreadyAttachedException.class);

        verify(mediaValidationPort).lockAndValidateVideoMediaAsset(videoAssetId);
        verify(entryRepository).findByIdForUpdate(entryId);
        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Attach: fails if media validation port rejects the video asset")
    void shouldFailIfMediaValidationRejects() {
        when(titleRepository.existsById(titleId)).thenReturn(true);
        doThrow(new IllegalArgumentException("Invalid media")).when(mediaValidationPort).lockAndValidateVideoMediaAsset(videoAssetId);

        assertThatThrownBy(() -> useCase.execute(titleId, entryId, videoAssetId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid media");

        verify(mediaValidationPort).lockAndValidateVideoMediaAsset(videoAssetId);
        verify(entryRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).save(any());
    }

    private ScreenEntry createEntry(UUID id, UUID tId, UUID currentVideoId) {
        Instant t = Instant.parse("2026-10-10T10:00:00Z");
        return ScreenEntry.createDraft(
                id,
                tId,
                "tap-1",
                ScreenEntryType.EPISODE,
                1,
                "Tập 1",
                "Tóm tắt tập 1",
                currentVideoId,
                null,
                1200L,
                t
        );
    }
}
